package com.jpq.mobile;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.os.*;
import org.json.*;

/** Protocol-only foreground service: no screenshot or media-projection permission. */
public final class ProtocolService extends Service {
    static volatile ProtocolService current;
    private final Handler main=new Handler(Looper.getMainLooper());
    private HandlerThread thread;private Handler worker;
    private HandOverlay overlay;
    private ProtocolSession session;private RoundSocket socket;private RootProtocolCollector collector;
    private volatile boolean running,closed;
    private int generation;
    private String info="协议测试版 · 请先打开游戏";
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onCreate(){super.onCreate();current=this;thread=new HandlerThread("protocol-session");thread.start();worker=new Handler(thread.getLooper());}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null||"stop".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("protocol","协议采集",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,"protocol").setSmallIcon(R.drawable.ic_cards).setContentTitle("记牌器协议测试版")
            .setContentText("需要Root · 仅启动时采集和连接服务器").setContentIntent(open).setOngoing(true).build();
        if(Build.VERSION.SDK_INT>=34)startForeground(2,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(2,notification);
        if(overlay==null){overlay=new HandOverlay(this,new HandOverlay.Actions(){
            public void toggle(){ProtocolService.this.toggle();}
            public void settings(){settingsPanel();}
            public void help(){android.widget.Toast.makeText(ProtocolService.this,info,android.widget.Toast.LENGTH_LONG).show();}
            public void exit(){stopSelf();}
        });overlay.update(false,null,info,false);}
        return START_NOT_STICKY;
    }
    void expand(){if(overlay!=null)overlay.expand();}
    private void render(){
        final HandReply reply=session==null?null:session.result;final boolean visible=running&&session!=null&&session.panel;
        if(session!=null)info=session.status;
        main.post(()->{if(!closed&&overlay!=null)overlay.update(running,reply,info,visible);});
    }
    private void settingsPanel(){if(overlay.hasSettings()){overlay.dismissSettings();return;}overlay.showSettings(()->{if(running)toggle();},()->{});}
    void toggle(){
        if(running){running=false;worker.post(this::stopWork);overlay.update(false,null,"已暂停 · 未连接",false);return;}
        SharedPreferences pref=getSharedPreferences("hand_upload",0);String tenant=pref.getString("tenant_id",""),client=pref.getString("client_id","");
        try{RoundSocket.url(BuildConfig.SERVER_URL,tenant,client);}catch(Exception e){settingsPanel();return;}
        final String mode=pref.getString("game_mode",ProtocolDeck.STANDARD);
        running=true;worker.post(()->{
            stopWork();if(!running||closed)return;
            final int run=++generation;
            try {
                String key="protocol_session_"+tenant+"_"+client+"_"+BuildConfig.SERVER_URL+"_"+mode;
                session=new ProtocolSession(tenant,client,pref.getString(key,""),value->{if(!pref.edit().putString(key,value).commit())throw new IllegalStateException("保存失败");},mode);
                session.trace=this::audit;
                socket=new RoundSocket(worker,session,BuildConfig.SERVER_URL,this::render,()->running&&!closed);
                collector=new RootProtocolCollector(this,event->worker.post(()->{
                    if(!running||closed||run!=generation)return;
                    try {
                        audit("game",event.toString());
                        String kind=event.optString("event");
                        if(kind.equals("waiting-game"))session.status="等待问鼎启动";
                        else if(kind.equals("attaching-game"))session.status="问鼎已启动，正在连接采集";
                        else if(kind.equals("ready"))session.status="采集已连接 · 请进入新局";
                        else if(kind.equals("observer-error")||kind.equals("invalid-hand"))session.status="发牌事件解析失败，等待有效手牌";
                        else{session.game(event);if(socket!=null)socket.flush();}
                        android.util.Log.i("JPQ_PROTOCOL_TEST",kind+" "+event.optString("game_key")+" panel="+session.panel);
                        render();
                    }catch(Exception error){session.status="协议事件无效："+error.getMessage();render();}
                }),text->worker.post(()->{if(run==generation&&running){info=text;running=false;stopWork();main.post(()->android.widget.Toast.makeText(this,text,android.widget.Toast.LENGTH_LONG).show());}}));
                collector.start();render();
            }catch(Exception e){info=e.getMessage();running=false;stopWork();}
        });
    }
    private synchronized void audit(String direction,String data){
        // Bounded on-device test evidence; contains this account's hand and server replies only.
        try{
            java.io.File file=new java.io.File(getFilesDir(),"protocol-events.jsonl");
            if(file.length()>4*1024*1024){java.io.File previous=new java.io.File(getFilesDir(),"protocol-events.previous.jsonl");if(previous.exists())previous.delete();file.renameTo(previous);}
            JSONObject record=new JSONObject().put("time_ms",System.currentTimeMillis()).put("elapsed_ms",SystemClock.elapsedRealtime()).put("direction",direction).put("data",new JSONObject(data));
            try(java.io.FileWriter writer=new java.io.FileWriter(file,true)){writer.write(record.toString()+"\n");}
        }catch(Exception error){android.util.Log.w("JPQ_PROTOCOL_TEST","无法写入测试记录",error);}
    }
    private void stopWork(){generation++;if(socket!=null){socket.close();socket=null;}if(collector!=null){collector.close();collector=null;}session=null;render();}
    @Override public void onConfigurationChanged(Configuration configuration){super.onConfigurationChanged(configuration);if(overlay!=null)overlay.resize();}
    @Override public void onDestroy(){closed=true;running=false;current=null;if(overlay!=null)overlay.close();worker.post(()->{stopWork();thread.quitSafely();});super.onDestroy();}
}
