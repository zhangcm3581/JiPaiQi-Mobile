package com.jpq.mobile;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.*;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.*;
import org.json.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

public final class CaptureService extends Service {
    public static volatile CaptureService current;
    public volatile Ledger ledger;public volatile Pack pack;
    public volatile String status="正在启动";
    public volatile boolean automatic=false;
    public volatile long frameMillis;
    public volatile long freshFrames;public volatile boolean observedStart,observedEnd;public volatile String lastGap="";
    public volatile String handStatus="未配置手牌模板";private final HandSnapshots handSnapshots=new HandSnapshots();private volatile HandUpload handUpload;
    private RoundSession roundSession;private RoundSocket roundSocket;
    private volatile boolean identityConfigured;
    private volatile HandOverlay handOverlay;private final HandResultState handResult=new HandResultState();private volatile String panelStatus="点击启动，等待新局";
    private Image pendingImage;private Vision vision;private MediaProjection projection;private VirtualDisplay display;private ImageReader reader;
    private HandlerThread thread;private Handler worker;private final Handler main=new Handler(Looper.getMainLooper());
    private boolean dockBottomRight=true;
    private WindowManager windows;private CounterStrip strip;private WindowManager.LayoutParams params;
    private volatile boolean closing=false,obscured=false;private volatile android.graphics.Rect overlayBounds=new android.graphics.Rect();
    private long lastPersist=0,lastImageAt=-1;private Vision.Frame cachedFrame;private int captureW,captureH;private double[] viewport={0,0,1,1};private boolean hadGap=false;
    private final Runnable captureTick=new Runnable(){public void run(){
        if(closing||reader==null)return;
        long begin=SystemClock.elapsedRealtime();onImage(reader);
        if(!closing)worker.postDelayed(this,Math.max(16,captureIntervalMs()-(SystemClock.elapsedRealtime()-begin)));
    }};
    private long captureIntervalMs(){
        long configured=pack.timing.optLong("sample_interval_ms",200);
        return automatic&&observedStart&&!observedEnd&&roundSession!=null&&roundSession.needsHands()?Math.min(configured,80):configured;
    }
    private final Runnable ticker=new Runnable(){public void run(){if(!closing){updateStrip();worker.post(CaptureService.this::publishPanel);if(strip!=null)strip.pulse((SystemClock.elapsedRealtime()/500)%2==0);main.postDelayed(this,250);}}};
    // Read the phase, reply and status together on the session's worker, then render on main.
    private void publishPanel(){
        boolean visible=roundSession!=null&&roundSession.panel;
        HandReply data=roundSession==null?null:roundSession.result;
        String label=roundSession==null?panelStatus:roundSession.status;
        main.post(()->{if(!closing&&handOverlay!=null)handOverlay.update(automatic,data,label,automatic&&visible&&!obscured);});
    }
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onCreate(){super.onCreate();current=this;thread=new HandlerThread("card-recognition");thread.start();worker=new Handler(thread.getLooper());windows=(WindowManager)getSystemService(WINDOW_SERVICE);}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null||"stop".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(projection!=null)return START_NOT_STICKY;
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);nm.createNotificationChannel(new NotificationChannel("capture","记牌识别",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,CaptureService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification=new Notification.Builder(this,"capture").setSmallIcon(com.jpq.mobile.R.drawable.ic_cards).setContentTitle("记牌器正在识别").setContentText("本机识别手牌 · 连接服务器").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"停止",stop).build()).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);else startForeground(1,notification);
        try{
            Intent data=intent.getParcelableExtra("projection");if(data==null)throw new IllegalArgumentException("需要重新授权屏幕录制");
            projection=((MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE)).getMediaProjection(intent.getIntExtra("result",Activity.RESULT_CANCELED),data);
            if(projection==null)throw new IllegalStateException("屏幕授权失效");
            projection.registerCallback(new MediaProjection.Callback(){@Override public void onStop(){stopSelf();}@Override public void onCapturedContentResize(int w,int h){if(worker!=null)worker.post(()->resize(w,h));}},main);
            worker.post(()->{try{
                pack=Pack.current(this);vision=new Vision(pack);
                configureIdentity();

                ledger=new Ledger(HandSnapshots.deck(),Map.of("me",13),pack.guard.getLong("settlement_hold_ms"),pack.guard.getLong("restart_clear_ms"),pack.timing.getLong("empty_hold_ms"),pack.timing.getInt("confirm_samples"),pack.guard.getInt("restart_confirm_samples"));
                android.content.SharedPreferences p=getSharedPreferences("capture",0);viewport=new double[]{p.getFloat("x",0),p.getFloat("y",0),p.getFloat("w",1),p.getFloat("h",1)};
                DisplayMetrics metrics=new DisplayMetrics();windows.getDefaultDisplay().getRealMetrics(metrics);resize(metrics.widthPixels,metrics.heightPixels);
                status=roundSession==null?"请点击设置填写租户 ID 和客户端 ID":"等待开局 · 请在发牌前开启";main.post(()->{if(!closing){showOverlay();ticker.run();}});
            }catch(Exception e){fatal(e);}});
        }catch(Exception e){fatal(e);}
        return START_NOT_STICKY;
    }
    // Worker-owned connection replacement keeps the projection and control window alive.
    private void configureIdentity() throws Exception {
        SharedPreferences pref=getSharedPreferences("hand_upload",0);
        String tenant=pref.getString("tenant_id","").trim(),client=pref.getString("client_id","").trim();
        if(roundSession!=null&&tenant.equals(roundSession.tenant)&&client.equals(roundSession.client))return;
        automatic=false;identityConfigured=false;
        if(roundSocket!=null){roundSocket.close();roundSocket=null;}
        roundSession=null;cachedFrame=null;handSnapshots.reset();handStatus="等待新的完整手牌";
        if(ledger!=null)ledger.interrupt();
        if(!tenant.matches("[0-9]{1,32}")||!client.matches("[A-Za-z0-9_-]{1,100}")){
            status=panelStatus="请点击设置填写租户 ID 和客户端 ID";publishPanel();return;
        }
        String endpoint=BuildConfig.SERVER_URL;
        RoundSocket.url(endpoint,tenant,client);
        String legacyStore="round_session_"+tenant+"_"+endpoint;
        String store=legacyStore+"_client_"+client;
        String restored=pref.getString(store,"");
        if(restored.isEmpty()){
            String legacy=pref.getString(legacyStore,"");
            if(!legacy.isEmpty()&&client.equals(new JSONObject(legacy).optString("client")))restored=legacy;
        }
        roundSession=new RoundSession(tenant,client,restored,value->{if(!pref.edit().putString(store,value).commit())throw new IllegalStateException("无法持久保存轮次");});
        roundSocket=new RoundSocket(worker,roundSession,endpoint,this::publishPanel);
        identityConfigured=true;status=panelStatus="已保存 · 请点击启动";publishPanel();
    }
    public void reloadIdentity(){worker.post(()->{if(closing)return;try{configureIdentity();}catch(Exception error){fatal(error);}});}
    public void expandControls(){main.post(()->{if(!closing&&handOverlay!=null)handOverlay.expand();});}
    public void setSettingsOpen(boolean open){obscured=open;if(open)worker.post(()->{if(!closing){if(roundSession!=null)roundSession.gap();publishPanel();}});}
    public void showIdentitySettings(){main.post(()->{
        if(closing||handOverlay==null)return;
        if(handOverlay.hasSettings()){handOverlay.dismissSettings();return;}
        setSettingsOpen(true);
        handOverlay.showSettings(this::reloadIdentity,()->{if(!closing)setSettingsOpen(false);});
    });}
    private void resize(int w,int h){
        if(closing||projection==null||vision==null||w<1||h<1||(w==captureW&&h==captureH&&reader!=null))return;
        try{if(ledger!=null&&captureW!=0){ledger.interrupt();if(roundSession!=null)roundSession.gap();hadGap=true;}worker.removeCallbacks(captureTick);cachedFrame=null;handSnapshots.reset();if(pendingImage!=null){pendingImage.close();pendingImage=null;}if(reader!=null){reader.close();reader=null;}captureW=w;captureH=h;
            reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,3);
            reader.setOnImageAvailableListener(r->{if(closing)return;try{Image next=r.acquireLatestImage();if(next!=null){if(pendingImage!=null)pendingImage.close();pendingImage=next;}}catch(IllegalStateException ignored){}},worker);
            int density=getResources().getDisplayMetrics().densityDpi;
            if(display==null)display=projection.createVirtualDisplay("JPQ-local-capture",w,h,density,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,worker);
            else{display.resize(w,h,density);display.setSurface(reader.getSurface());}
            worker.post(captureTick);
            main.post(()->{if(handOverlay!=null){handOverlay.resize();handOverlay.setRecognitionAreas(recognitionAreas());}if(strip!=null){clampOverlay();windows.updateViewLayout(strip,params);}});
        }catch(Exception e){fatal(e);}
    }
    private void onImage(ImageReader source){
        Image image=null;Bitmap padded=null,bitmap=null;
        try{if(closing)return;image=pendingImage;pendingImage=null;long now=SystemClock.elapsedRealtime();
            if(!automatic){cachedFrame=null;lastImageAt=-1;return;}
            if(obscured){gap("设置中，暂停识别");return;}
            if(!((PowerManager)getSystemService(POWER_SERVICE)).isInteractive()){gap("屏幕已关闭，暂停识别");return;}
            if(captureH>captureW){gap("请切换到横屏游戏");return;}
            if(overlaps()){gap("悬浮条遮住识别区域，请拖到空白处");return;}
            if(image==null){if(lastImageAt>=0&&now-lastImageAt>=1500)gap("截图已中断，等待恢复");return;}
            lastImageAt=now; // Only newly captured frames can confirm visual transitions.
            Image.Plane plane=image.getPlanes()[0];int pixel=plane.getPixelStride(),row=plane.getRowStride();if(pixel!=4)throw new IOException("不支持此截图格式");
            int paddedWidth=row/pixel;ByteBuffer buffer=plane.getBuffer();padded=Bitmap.createBitmap(paddedWidth,image.getHeight(),Bitmap.Config.ARGB_8888);padded.copyPixelsFromBuffer(buffer);bitmap=Bitmap.createBitmap(padded,0,0,image.getWidth(),image.getHeight());
            long begin=SystemClock.elapsedRealtime();cachedFrame=vision.analyzeCapture(bitmap,viewport,roundSession==null||roundSession.needsHands());frameMillis=SystemClock.elapsedRealtime()-begin;evaluate(cachedFrame,now);
        }catch(Exception e){gap(e.getMessage()==null?"截图识别失败":e.getMessage());}
        finally{if(bitmap!=null&&bitmap!=padded)bitmap.recycle();if(padded!=null)padded.recycle();if(image!=null)image.close();}
    }
    private void evaluate(Vision.Frame f,long now){
        try {
            List<String> cards=f.hands.isEmpty()?List.of():f.hands.get(0).ranks();
            freshFrames++;observedStart=f.start;observedEnd=f.end;if(roundSession!=null)roundSession.frame(now,f.start,f.end,cards);if(roundSocket!=null)roundSocket.flush();
            if(!f.hands.isEmpty())handStatus="识别到 "+cards.size()+" / 13 张 · "+String.join(" ",cards);
            status=roundSession==null?"本地识别 · 请在上传设置填写租户 ID 和客户端 ID":roundSession.status;panelStatus=status;publishPanel();
            if(now-lastPersist>3000){persist();lastPersist=now;}
        }catch(Exception e){gap("轮次处理失败："+e.getMessage());}
    }

    private void gap(String reason){lastGap=reason;if(roundSession!=null)roundSession.gap();cachedFrame=null;handSnapshots.reset();handStatus="采集暂停，手牌结果无效";panelStatus=reason;if(!hadGap&&ledger!=null)ledger.interrupt();hadGap=true;status=reason;publishPanel();}
    private List<android.graphics.Rect> recognitionAreas(){
        if(pack==null)return List.of();
        double w=captureW*viewport[2],h=captureH*viewport[3],scale=Math.min(w/pack.content[2],h/pack.content[3]);double ox=captureW*viewport[0]+(w-scale*pack.content[2])/2,oy=captureH*viewport[1]+(h-scale*pack.content[3])/2;
        List<android.graphics.Rect> result=new ArrayList<>();
        try{List<JSONArray> areas=new ArrayList<>();for(JSONObject r:pack.regions.values())areas.add(r.getJSONArray("rect"));for(JSONObject d:pack.detectors.values())if(d.has("rect"))areas.add(d.getJSONArray("rect"));
            for(JSONArray a:areas){double[] r=Pack.numbers(a);result.add(new android.graphics.Rect((int)(ox+r[0]*pack.content[2]*scale),(int)(oy+r[1]*pack.content[3]*scale),(int)(ox+(r[0]+r[2])*pack.content[2]*scale),(int)(oy+(r[1]+r[3])*pack.content[3]*scale)));}
        }catch(Exception ignored){return List.of(new android.graphics.Rect(0,0,captureW,captureH));}return result;
    }
    private boolean overlaps(){
        List<android.graphics.Rect> occupied=handOverlay!=null?handOverlay.bounds():strip==null?List.of():List.of(overlayBounds);
        for(android.graphics.Rect hit:recognitionAreas())for(android.graphics.Rect b:occupied)if(android.graphics.Rect.intersects(b,hit))return true;
        return false;
    }
    private void showOverlay(){
        if(!android.provider.Settings.canDrawOverlays(this)){fatal(new IllegalStateException("请允许悬浮窗权限"));return;}
        handOverlay=new HandOverlay(this,new HandOverlay.Actions(){
            public void toggle(){CaptureService.this.toggle();}
            public void settings(){showIdentitySettings();}
            public void help(){android.widget.Toast.makeText(CaptureService.this,"启动后等待新局，连续确认 13 张自己的手牌才上传。面板只显示服务器结果。控制栏可拖动，结果面板固定左上角；点击面板隐藏，长按收起可恢复。",android.widget.Toast.LENGTH_LONG).show();}
            public void exit(){stopSelf();}
        });
        handOverlay.setRecognitionAreas(recognitionAreas());
    }
    public void toggleHandPanel(){main.post(()->{if(handOverlay!=null)handOverlay.togglePanel();});}
    private void clampOverlay(){if(strip==null)return;DisplayMetrics m=new DisplayMetrics();windows.getDefaultDisplay().getRealMetrics(m);params.width=Math.round(m.widthPixels*strip.style.width);params.height=Math.round(params.width/6.5f);if(dockBottomRight){params.x=m.widthPixels-params.width-4;params.y=m.heightPixels-params.height-4;}params.x=Math.max(0,Math.min(params.x,m.widthPixels-params.width));params.y=Math.max(0,Math.min(params.y,m.heightPixels-params.height));overlayBounds=new android.graphics.Rect(params.x,params.y,params.x+params.width,params.y+params.height);}
    public void refreshAppearance(){main.post(()->{if(strip!=null){strip.style.load(this);params.alpha=strip.style.opacity;clampOverlay();windows.updateViewLayout(strip,params);strip.invalidate();}});}
    private void updateStrip(){if(strip!=null&&ledger!=null)strip.data(pack.order,ledger.remaining(),automatic,ledger.phase==Ledger.Phase.WAITING,ledger.reviews());}
    public void toggle(){if(!automatic&&!identityConfigured){showIdentitySettings();return;}automatic=!automatic;main.post(()->{if(handOverlay!=null)handOverlay.update(automatic,null,automatic?"等待新局 / 完整 13 张手牌":"已暂停",false);});worker.post(()->{if(roundSession!=null)roundSession.gap();cachedFrame=null;handSnapshots.reset();if(!automatic){handResult.end();if(handUpload!=null)handUpload.cancel();panelStatus="已暂停";}else panelStatus="等待新局 / 完整 13 张手牌";if(ledger!=null)ledger.interrupt();hadGap=true;status=automatic?"继续识别 · 当前局可能不完整":"已暂停";persist();});updateStrip();}
    public void command(String action,long id){worker.post(()->{if(ledger==null)return;switch(action){case "confirm"->ledger.confirm(id);case "dismiss"->ledger.dismiss(id);case "undo"->ledger.undo();case "new"->{status="请等待真实结束与下一次开局，不能手动跳轮";}}persist();});}
    public JSONObject report(){JSONObject out=new JSONObject();try{out.put("format","jpq.mobile-session/1");out.put("status",status);out.put("hand_status",handStatus);HandOverlay overlay=handOverlay;out.put("panel_visible",overlay!=null&&overlay.bounds().size()>1);out.put("upload_status",roundSocket==null?"未启用上传":roundSession.status);out.put("server_round",roundSession==null?0:roundSession.round);out.put("package_id",pack==null?"":pack.json.getJSONObject("package").getString("id"));out.put("package_version",pack==null?"":pack.json.getJSONObject("package").getString("version"));JSONArray entries=new JSONArray();if(ledger!=null)synchronized(ledger){out.put("round",ledger.round);out.put("remaining",new JSONObject(ledger.remaining()));for(Ledger.Event e:ledger.history())entries.put(new JSONObject().put("id",e.id).put("round",e.round).put("seat",e.seat).put("name",e.name).put("cards",new JSONArray(e.cards)).put("time_elapsed_ms",e.time).put("status",e.status.name()).put("reason",e.reason));}out.put("events",entries);}catch(JSONException ignored){}return out;}
    private void persist(){try{File tmp=new File(getFilesDir(),"session.tmp"),dest=new File(getFilesDir(),"session.json");try(Writer w=new OutputStreamWriter(new FileOutputStream(tmp),java.nio.charset.StandardCharsets.UTF_8)){w.write(report().toString(2));}java.nio.file.Files.move(tmp.toPath(),dest.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}catch(Exception ignored){}}
    private void fatal(Exception e){status=e.getMessage();getSharedPreferences("capture",0).edit().putString("last_error",status).apply();main.post(()->{android.widget.Toast.makeText(this,status,android.widget.Toast.LENGTH_LONG).show();stopSelf();});}
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);DisplayMetrics m=new DisplayMetrics();windows.getDefaultDisplay().getRealMetrics(m);worker.post(()->resize(m.widthPixels,m.heightPixels));}
    @Override public void onDestroy(){closing=true;if(handUpload!=null)handUpload.close();current=null;main.removeCallbacks(ticker);handResult.end();if(handOverlay!=null){handOverlay.close();handOverlay=null;}if(strip!=null){windows.removeView(strip);strip=null;}worker.removeCallbacks(captureTick);worker.post(()->{if(roundSocket!=null)roundSocket.close();if(ledger!=null){ledger.interrupt();persist();}if(display!=null)display.release();if(pendingImage!=null){pendingImage.close();pendingImage=null;}if(reader!=null)reader.close();if(vision!=null)vision.close();if(projection!=null){projection.stop();projection=null;}thread.quitSafely();});stopForeground(true);super.onDestroy();}
}
