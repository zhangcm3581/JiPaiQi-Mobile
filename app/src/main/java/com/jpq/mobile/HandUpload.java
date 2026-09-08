package com.jpq.mobile;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.JSONObject;
/** Opt-in HTTPS transport; one latest pending snapshot, bounded retries, no disk queue. */
public final class HandUpload implements AutoCloseable {
    private final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor();
    private String pending;private boolean running;private volatile boolean closed;private final String endpoint;
    public volatile String status="未发送";
    public HandUpload(String endpoint){URI uri=URI.create(endpoint);if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("请填写有效 HTTPS 接口地址");this.endpoint=endpoint;}
    public synchronized void submit(JSONObject data){if(closed)return;pending=data.toString();if(!running){running=true;io.execute(this::drain);}}
    private void drain(){String body;synchronized(this){if(closed){running=false;return;}body=pending;pending=null;if(body==null){running=false;return;}}
        for(int attempt=0;attempt<3&&!closed;attempt++){
            HttpURLConnection c=null;try{c=(HttpURLConnection)new URL(endpoint).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(4000);c.setReadTimeout(4000);c.setRequestMethod("POST");c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setRequestProperty("Idempotency-Key",new JSONObject(body).getString("event_id"));c.setDoOutput(true);byte[] bytes=body.getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);try(var out=c.getOutputStream()){out.write(bytes);}int code=c.getResponseCode();if(code>=200&&code<300){status="已上传手牌";break;}status="上传失败 HTTP "+code;if(code<500&&code!=429)break;
            }catch(Exception e){status="上传失败，请检查接口或网络";}finally{if(c!=null)c.disconnect();}
            synchronized(this){if(pending!=null||closed||attempt==2)break;}try{Thread.sleep(1000L*(attempt+1));}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
        }
        synchronized(this){if(closed){running=false;return;}if(pending==null){running=false;return;}io.execute(this::drain);}
    }
    public synchronized void close(){closed=true;pending=null;io.shutdownNow();}
}
