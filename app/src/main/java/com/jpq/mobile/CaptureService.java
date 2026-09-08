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
    public volatile boolean automatic=true;
    public volatile long frameMillis;
    public volatile String handStatus="未配置手牌模板";private final HandSnapshots handSnapshots=new HandSnapshots();private HandUpload handUpload;
    private Image pendingImage;private Vision vision;private MediaProjection projection;private VirtualDisplay display;private ImageReader reader;
    private HandlerThread thread;private Handler worker;private final Handler main=new Handler(Looper.getMainLooper());
    private boolean dockBottomRight=true;
    private WindowManager windows;private CounterStrip strip;private WindowManager.LayoutParams params;
    private volatile boolean closing=false,obscured=false;private volatile android.graphics.Rect overlayBounds=new android.graphics.Rect();
    private long lastPersist=0;private Vision.Frame cachedFrame;private int captureW,captureH;private double[] viewport={0,0,1,1};private boolean hadGap=false;
    private final Runnable captureTick=new Runnable(){public void run(){
        if(closing||reader==null)return;
        long begin=SystemClock.elapsedRealtime();onImage(reader);
        if(!closing)worker.postDelayed(this,Math.max(16,pack.timing.optLong("sample_interval_ms",200)-(SystemClock.elapsedRealtime()-begin)));
    }};
    private final Runnable ticker=new Runnable(){public void run(){if(!closing){updateStrip();if(strip!=null)strip.pulse((SystemClock.elapsedRealtime()/500)%2==0);main.postDelayed(this,250);}}};
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onCreate(){super.onCreate();current=this;thread=new HandlerThread("card-recognition");thread.start();worker=new Handler(thread.getLooper());windows=(WindowManager)getSystemService(WINDOW_SERVICE);}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null||"stop".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(projection!=null)return START_NOT_STICKY;
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);nm.createNotificationChannel(new NotificationChannel("capture","记牌识别",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,CaptureService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification=new Notification.Builder(this,"capture").setSmallIcon(com.jpq.mobile.R.drawable.ic_cards).setContentTitle("记牌器正在识别").setContentText("仅在本机处理 · 点此查看记录").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"停止",stop).build()).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);else startForeground(1,notification);
        try{
            Intent data=intent.getParcelableExtra("projection");if(data==null)throw new IllegalArgumentException("需要重新授权屏幕录制");
            projection=((MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE)).getMediaProjection(intent.getIntExtra("result",Activity.RESULT_CANCELED),data);
            if(projection==null)throw new IllegalStateException("屏幕授权失效");
            projection.registerCallback(new MediaProjection.Callback(){@Override public void onStop(){stopSelf();}@Override public void onCapturedContentResize(int w,int h){if(worker!=null)worker.post(()->resize(w,h));}},main);
            worker.post(()->{try{
                pack=Pack.current(this);vision=new Vision(pack);var uploadPrefs=getSharedPreferences("hand_upload",0);if(uploadPrefs.getBoolean("enabled",false))handUpload=new HandUpload(uploadPrefs.getString("endpoint",""));
                ledger=new Ledger(pack.deck,pack.hands,pack.guard.getLong("settlement_hold_ms"),pack.guard.getLong("restart_clear_ms"),pack.timing.getLong("empty_hold_ms"),pack.timing.getInt("confirm_samples"),pack.guard.getInt("restart_confirm_samples"));
                android.content.SharedPreferences p=getSharedPreferences("capture",0);viewport=new double[]{p.getFloat("x",0),p.getFloat("y",0),p.getFloat("w",1),p.getFloat("h",1)};
                DisplayMetrics metrics=new DisplayMetrics();windows.getDefaultDisplay().getRealMetrics(metrics);resize(metrics.widthPixels,metrics.heightPixels);
                status="等待开局 · 请在发牌前开启";main.post(()->{if(!closing){showOverlay();ticker.run();}});
            }catch(Exception e){fatal(e);}});
        }catch(Exception e){fatal(e);}
        return START_NOT_STICKY;
    }
    private void resize(int w,int h){
        if(closing||projection==null||vision==null||w<1||h<1||(w==captureW&&h==captureH&&reader!=null))return;
        try{if(ledger!=null&&captureW!=0){ledger.interrupt();hadGap=true;}worker.removeCallbacks(captureTick);cachedFrame=null;handSnapshots.reset();if(pendingImage!=null){pendingImage.close();pendingImage=null;}if(reader!=null){reader.close();reader=null;}captureW=w;captureH=h;
            reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,3);
            reader.setOnImageAvailableListener(r->{if(closing)return;try{if(pendingImage!=null){pendingImage.close();pendingImage=null;}pendingImage=r.acquireLatestImage();}catch(IllegalStateException ignored){}},worker);
            int density=getResources().getDisplayMetrics().densityDpi;
            if(display==null)display=projection.createVirtualDisplay("JPQ-local-capture",w,h,density,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,worker);
            else{display.resize(w,h,density);display.setSurface(reader.getSurface());}
            worker.post(captureTick);
            main.post(()->{if(strip!=null){clampOverlay();windows.updateViewLayout(strip,params);}});
        }catch(Exception e){fatal(e);}
    }
    private void onImage(ImageReader source){
        Image image=null;Bitmap padded=null,bitmap=null;
        try{if(closing)return;image=pendingImage;pendingImage=null;long now=SystemClock.elapsedRealtime();
            if(!automatic){cachedFrame=null;return;}
            if(!((PowerManager)getSystemService(POWER_SERVICE)).isInteractive()){gap("屏幕已关闭，暂停识别");return;}
            if(captureH>captureW){gap("请切换到横屏游戏");return;}
            if(overlaps()){gap("悬浮条遮住识别区域，请拖到空白处");return;}
            if(image==null){if(cachedFrame!=null)evaluate(cachedFrame,now);return;}
            Image.Plane plane=image.getPlanes()[0];int pixel=plane.getPixelStride(),row=plane.getRowStride();if(pixel!=4)throw new IOException("不支持此截图格式");
            int paddedWidth=row/pixel;ByteBuffer buffer=plane.getBuffer();padded=Bitmap.createBitmap(paddedWidth,image.getHeight(),Bitmap.Config.ARGB_8888);padded.copyPixelsFromBuffer(buffer);bitmap=Bitmap.createBitmap(padded,0,0,image.getWidth(),image.getHeight());
            long begin=SystemClock.elapsedRealtime();cachedFrame=vision.analyze(bitmap,viewport);frameMillis=SystemClock.elapsedRealtime()-begin;evaluate(cachedFrame,now);
        }catch(Exception e){gap(e.getMessage()==null?"截图识别失败":e.getMessage());}
        finally{if(bitmap!=null&&bitmap!=padded)bitmap.recycle();if(padded!=null)padded.recycle();if(image!=null)image.close();}
    }
    private void evaluate(Vision.Frame f,long now){
        List<Ledger.Observation> observations=new ArrayList<>();
        for(Vision.Reading r:f.cards)observations.add(new Ledger.Observation(r.key,r.seat,r.name,r.ranks(),r.ambiguous||!r.unresolved.isEmpty()));
        int before=ledger.round;ledger.frame(now,f.start,f.end,observations);
        if(ledger.round!=before){hadGap=false;handSnapshots.reset();}
        if(f.end||ledger.phase==Ledger.Phase.ENDED){handSnapshots.reset();handStatus="本局结束，停止手牌上传";}
        else for(Vision.Reading hand:f.hands){
            try{JSONObject snapshot=handSnapshots.observe(hand.key,hand.ranks(),pack.timing.getInt("confirm_samples"),pack.json.getJSONObject("package").getString("id"));
                handStatus=hand.ranks().isEmpty()?"未识别到手牌":String.join(" ",hand.ranks());
                if(snapshot!=null&&handUpload!=null)handUpload.submit(snapshot);
            }catch(JSONException e){handStatus="手牌格式错误";}
        }
        status=switch(ledger.phase){case WAITING->"等待开局 · 请在发牌前开启";case PLAYING->"第 "+ledger.round+" 局 · "+(hadGap?"曾中断，余量可能不完整":"整副牌未出余量");case ENDED->"本局结束 · 已锁定";};
        if(ledger.reviews()>0)status+=" · "+ledger.reviews()+" 项待核对";
        if(observations.stream().anyMatch(o->o.uncertain))status+=" · 牌行有疑问";
        if(now-lastPersist>3000||f.end){persist();lastPersist=now;}
    }
    private void gap(String reason){cachedFrame=null;handSnapshots.reset();handStatus="采集暂停，手牌结果无效";if(!hadGap&&ledger!=null)ledger.interrupt();hadGap=true;status=reason;}
    private boolean overlaps(){
        if(pack==null||strip==null)return false;android.graphics.Rect b=overlayBounds;
        double w=captureW*viewport[2],h=captureH*viewport[3],scale=Math.min(w/pack.content[2],h/pack.content[3]);double ox=captureW*viewport[0]+(w-scale*pack.content[2])/2,oy=captureH*viewport[1]+(h-scale*pack.content[3])/2;
        try{List<JSONArray> areas=new ArrayList<>();for(JSONObject r:pack.regions.values())areas.add(r.getJSONArray("rect"));for(JSONObject d:pack.detectors.values())if(d.has("rect"))areas.add(d.getJSONArray("rect"));
            for(JSONArray a:areas){double[] r=Pack.numbers(a);android.graphics.Rect hit=new android.graphics.Rect((int)(ox+r[0]*pack.content[2]*scale),(int)(oy+r[1]*pack.content[3]*scale),(int)(ox+(r[0]+r[2])*pack.content[2]*scale),(int)(oy+(r[1]+r[3])*pack.content[3]*scale));if(android.graphics.Rect.intersects(b,hit))return true;}
        }catch(Exception ignored){return true;}return false;
    }
    private void showOverlay(){
        if(!android.provider.Settings.canDrawOverlays(this)){fatal(new IllegalStateException("请允许悬浮窗权限"));return;}
        Appearance appearance=new Appearance();appearance.load(this);strip=new CounterStrip(this,appearance);
        params=new WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.LEFT;params.alpha=appearance.opacity;
        android.content.SharedPreferences pref=getSharedPreferences("overlay",0);dockBottomRight=pref.getBoolean("dock_bottom_right",true);params.x=pref.getInt("x",0);params.y=pref.getInt("y",0);clampOverlay();
        strip.setOnClickListener(v->toggle());
        strip.setOnTouchListener(new View.OnTouchListener(){float downX,downY;int startX,startY;long time;boolean moved;public boolean onTouch(View v,android.view.MotionEvent e){
            if(e.getAction()==MotionEvent.ACTION_DOWN){downX=e.getRawX();downY=e.getRawY();startX=params.x;startY=params.y;time=SystemClock.elapsedRealtime();moved=false;return true;}
            if(e.getAction()==MotionEvent.ACTION_MOVE){float dx=e.getRawX()-downX,dy=e.getRawY()-downY;if(Math.abs(dx)+Math.abs(dy)>10)moved=true;if(moved){dockBottomRight=false;params.x=startX+(int)dx;params.y=startY+(int)dy;clampOverlay();windows.updateViewLayout(strip,params);}return true;}
            if(e.getAction()==MotionEvent.ACTION_UP){if(moved){getSharedPreferences("overlay",0).edit().putBoolean("dock_bottom_right",dockBottomRight).putInt("x",params.x).putInt("y",params.y).apply();}else if(SystemClock.elapsedRealtime()-time>500||e.getX()>strip.getWidth()/(pack.order.size()+1f)){startActivity(new Intent(CaptureService.this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}else strip.performClick();return true;}return true;}});
        windows.addView(strip,params);updateStrip();
    }
    private void clampOverlay(){if(strip==null)return;DisplayMetrics m=new DisplayMetrics();windows.getDefaultDisplay().getRealMetrics(m);params.width=Math.round(m.widthPixels*strip.style.width);params.height=Math.round(params.width/6.5f);if(dockBottomRight){params.x=m.widthPixels-params.width-4;params.y=m.heightPixels-params.height-4;}params.x=Math.max(0,Math.min(params.x,m.widthPixels-params.width));params.y=Math.max(0,Math.min(params.y,m.heightPixels-params.height));overlayBounds=new android.graphics.Rect(params.x,params.y,params.x+params.width,params.y+params.height);}
    public void refreshAppearance(){main.post(()->{if(strip!=null){strip.style.load(this);params.alpha=strip.style.opacity;clampOverlay();windows.updateViewLayout(strip,params);strip.invalidate();}});}
    private void updateStrip(){if(strip!=null&&ledger!=null)strip.data(pack.order,ledger.remaining(),automatic,ledger.phase==Ledger.Phase.WAITING,ledger.reviews());}
    public void toggle(){automatic=!automatic;worker.post(()->{cachedFrame=null;handSnapshots.reset();if(ledger!=null)ledger.interrupt();hadGap=true;status=automatic?"继续识别 · 当前局可能不完整":"已暂停";persist();});updateStrip();}
    public void command(String action,long id){worker.post(()->{if(ledger==null)return;switch(action){case "confirm"->ledger.confirm(id);case "dismiss"->ledger.dismiss(id);case "undo"->ledger.undo();case "new"->{ledger.newRound();hadGap=false;}}persist();});}
    public JSONObject report(){JSONObject out=new JSONObject();try{out.put("format","jpq.mobile-session/1");out.put("status",status);out.put("hand_status",handStatus);out.put("upload_status",handUpload==null?"未启用上传":handUpload.status);out.put("package_id",pack==null?"":pack.json.getJSONObject("package").getString("id"));out.put("package_version",pack==null?"":pack.json.getJSONObject("package").getString("version"));JSONArray entries=new JSONArray();if(ledger!=null)synchronized(ledger){out.put("round",ledger.round);out.put("remaining",new JSONObject(ledger.remaining()));for(Ledger.Event e:ledger.history())entries.put(new JSONObject().put("id",e.id).put("round",e.round).put("seat",e.seat).put("name",e.name).put("cards",new JSONArray(e.cards)).put("time_elapsed_ms",e.time).put("status",e.status.name()).put("reason",e.reason));}out.put("events",entries);}catch(JSONException ignored){}return out;}
    private void persist(){try{File tmp=new File(getFilesDir(),"session.tmp"),dest=new File(getFilesDir(),"session.json");try(Writer w=new OutputStreamWriter(new FileOutputStream(tmp),java.nio.charset.StandardCharsets.UTF_8)){w.write(report().toString(2));}java.nio.file.Files.move(tmp.toPath(),dest.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}catch(Exception ignored){}}
    private void fatal(Exception e){status=e.getMessage();getSharedPreferences("capture",0).edit().putString("last_error",status).apply();main.post(()->{android.widget.Toast.makeText(this,status,android.widget.Toast.LENGTH_LONG).show();stopSelf();});}
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);DisplayMetrics m=new DisplayMetrics();windows.getDefaultDisplay().getRealMetrics(m);worker.post(()->resize(m.widthPixels,m.heightPixels));}
    @Override public void onDestroy(){closing=true;if(handUpload!=null)handUpload.close();current=null;main.removeCallbacks(ticker);if(strip!=null){windows.removeView(strip);strip=null;}worker.removeCallbacks(captureTick);worker.post(()->{if(ledger!=null){ledger.interrupt();persist();}if(display!=null)display.release();if(pendingImage!=null){pendingImage.close();pendingImage=null;}if(reader!=null)reader.close();if(vision!=null)vision.close();if(projection!=null){projection.stop();projection=null;}thread.quitSafely();});stopForeground(true);super.onDestroy();}
}
