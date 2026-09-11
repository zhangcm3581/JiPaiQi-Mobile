package com.jpq.mobile;

import android.os.Handler;
import okhttp3.*;
import org.json.*;
import java.net.URI;
import java.util.concurrent.TimeUnit;

/** All session mutations run on capture worker. Retries resend exact persisted envelopes. */
public final class RoundSocket implements AutoCloseable {
    private final Runnable onUpdate;private final Handler worker;private final RoundSession session;private final String url;
    private final OkHttpClient http=new OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(0,TimeUnit.MILLISECONDS).build();
    private final java.util.Map<String,Long> sent=new java.util.HashMap<>();
    private WebSocket socket;private boolean closed,open;private long lastPing,lastRetry,lastInbound;
    private final long heartbeatMs,responseTimeoutMs,reconnectMs;
    private final Runnable tick=new Runnable(){public void run(){
        if(closed)return;
        try{long now=android.os.SystemClock.elapsedRealtime();if(open&&socket!=null){
            if(now-lastInbound>=responseTimeoutMs){WebSocket expired=socket;expired.cancel();lost(expired);}
            else{if(now-lastPing>=heartbeatMs){socket.send(session.message("ping",null,new JSONObject()).toString());lastPing=now;}if(now-lastRetry>=3000){flush();lastRetry=now;}}
        }}catch(Exception e){session.status="网络协议错误："+e.getMessage();}
        worker.postDelayed(this,Math.min(1000,heartbeatMs));
    }};
    public static String url(String endpoint,String tenant,String client){
        URI u=URI.create(endpoint.trim());String scheme=u.getScheme();if(!("http".equals(scheme)||"https".equals(scheme)||"ws".equals(scheme)||"wss".equals(scheme))||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null)throw new IllegalArgumentException("请填写服务器地址，例如 http://139.199.12.90:8080");
        if(!tenant.matches("[0-9]{1,32}"))throw new IllegalArgumentException("请填写租户 ID：1 至 32 位数字");
        if(!client.matches("[A-Za-z0-9_-]{1,100}"))throw new IllegalArgumentException("请填写客户端 ID：1 至 100 位字母、数字、下划线或连字符");
        String path=u.getPath();if(path!=null&&!path.isEmpty()&&!path.equals("/")&&!path.equals("/ws/client"))throw new IllegalArgumentException("服务器地址不能含其他接口路径");
        return (scheme.equals("https")||scheme.equals("wss")?"wss":"ws")+"://"+u.getRawAuthority()+"/ws/client?tenant_id="+tenant+"&client_id="+client;
    }
    public RoundSocket(Handler worker,RoundSession session,String endpoint){this(worker,session,endpoint,15000,45000,2000);}
    public RoundSocket(Handler worker,RoundSession session,String endpoint,Runnable onUpdate){this(worker,session,endpoint,15000,45000,2000,onUpdate);}
    RoundSocket(Handler worker,RoundSession session,String endpoint,long heartbeatMs,long responseTimeoutMs,long reconnectMs){this(worker,session,endpoint,heartbeatMs,responseTimeoutMs,reconnectMs,()->{});}
    private RoundSocket(Handler worker,RoundSession session,String endpoint,long heartbeatMs,long responseTimeoutMs,long reconnectMs,Runnable onUpdate){this.onUpdate=onUpdate;this.worker=worker;this.session=session;this.heartbeatMs=heartbeatMs;this.responseTimeoutMs=responseTimeoutMs;this.reconnectMs=reconnectMs;url=url(endpoint,session.tenant,session.client);connect();worker.post(tick);}
    private void connect(){if(closed)return;socket=http.newWebSocket(new Request.Builder().url(url).build(),new WebSocketListener(){
        @Override public void onOpen(WebSocket ws,Response r){worker.post(()->{if(closed||socket!=ws){ws.cancel();return;}open=true;sent.clear();session.connection(true);lastInbound=lastPing=android.os.SystemClock.elapsedRealtime();});}
        @Override public void onMessage(WebSocket ws,String text){worker.post(()->{if(closed||socket!=ws)return;if(text.length()>262144){ws.cancel();return;}try{lastInbound=android.os.SystemClock.elapsedRealtime();JSONObject message=new JSONObject(text);session.receive(message);if(message.optString("type").equals("error")&&java.util.Set.of("TENANT_NOT_FOUND","TENANT_DISABLED","CLIENT_LIMIT").contains(message.getJSONObject("payload").optString("code"))){close();return;}flush();onUpdate.run();}catch(Exception e){session.status="服务器消息无效："+e.getMessage();}});}
        @Override public void onFailure(WebSocket ws,Throwable error,Response response){lost(ws);}
        @Override public void onClosing(WebSocket ws,int code,String reason){worker.post(()->{if(closed||socket!=ws)return;if(code==4001){session.registrationFailed("CLIENT_ID_IN_USE：客户端 ID 已被其他设备使用，请修改为不同 ID");close();}else{ws.close(code,reason);lost(ws);}});}
        @Override public void onClosed(WebSocket ws,int code,String reason){lost(ws);}
    });}
    private void lost(WebSocket ws){worker.post(()->{if(closed||socket!=ws)return;socket=null;open=false;session.connection(false);worker.postDelayed(this::connect,reconnectMs);});}
    public void flush(){if(!closed&&open&&socket!=null)for(String text:session.outgoing()){long now=android.os.SystemClock.elapsedRealtime();if(now-sent.getOrDefault(text,-10000L)>=3000&&socket.send(text))sent.put(text,now);}
        java.util.Set<String> active=new java.util.HashSet<>(session.outgoing());sent.keySet().retainAll(active);}
    public void close(){if(closed)return;closed=true;open=false;session.connection(false);worker.removeCallbacks(tick);if(socket!=null)socket.cancel();http.dispatcher().executorService().shutdown();http.connectionPool().evictAll();}
}
