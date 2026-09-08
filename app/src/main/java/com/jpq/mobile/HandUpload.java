package com.jpq.mobile;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.json.JSONObject;
/** Opt-in HTTPS request/response, bounded payloads, retries keep the same event id. */
public final class HandUpload implements AutoCloseable {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private String pending;private boolean running;private volatile boolean closed;private volatile long generation;private final String endpoint;
    private final Consumer<HandReply> onReply;private volatile HttpURLConnection connection;
    public volatile String status="未发送";
    public HandUpload(String endpoint){this(endpoint,r->{});}
    public HandUpload(String endpoint,Consumer<HandReply> callback){URI uri=URI.create(endpoint);if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("请填写有效 HTTPS 接口地址");this.endpoint=endpoint;onReply=callback;}
    public synchronized void submit(JSONObject data){if(closed)return;pending=data.toString();status="等待服务器结果";if(!running){running=true;io.execute(this::drain);}}
    public synchronized void cancel(){generation++;pending=null;HttpURLConnection c=connection;if(c!=null)c.disconnect();}
    static String readBody(InputStream input)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[2048];int n;while((n=input.read(b))!=-1){if(out.size()+n>65536)throw new IOException("响应过大");out.write(b,0,n);}return out.toString(StandardCharsets.UTF_8.name());}
    private void drain(){String body;long token;synchronized(this){if(closed){running=false;return;}body=pending;pending=null;token=generation;if(body==null){running=false;return;}}
        for(int attempt=0;attempt<3&&!closed&&generation==token;attempt++){
            HttpURLConnection c=null;try{JSONObject request=new JSONObject(body);c=(HttpURLConnection)new URL(endpoint).openConnection();connection=c;c.setInstanceFollowRedirects(false);c.setConnectTimeout(4000);c.setReadTimeout(4000);c.setRequestMethod("POST");c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setRequestProperty("Idempotency-Key",request.getString("event_id"));c.setDoOutput(true);byte[] bytes=body.getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);try(var out=c.getOutputStream()){out.write(bytes);}int code=c.getResponseCode();
                if(code>=200&&code<300){try(var in=c.getInputStream()){
                    HandReply reply=new HandReply(new JSONObject(readBody(in)));
                    if(!reply.eventId.equals(request.getString("event_id"))||reply.round!=request.getInt("round_id"))throw new IOException("响应与本次请求不符");
                    if(!closed&&generation==token){onReply.accept(reply);status="已收到服务器结果";}
                }catch(Exception e){status="服务器响应无效，请检查接口格式";}break;}
                status="上传失败 HTTP "+code;if(code<500&&code!=429)break;
            }catch(Exception e){if(generation==token)status="上传失败，请检查接口或网络";}finally{connection=null;if(c!=null)c.disconnect();}
            synchronized(this){if(pending!=null||closed||generation!=token||attempt==2)break;}try{Thread.sleep(1000L*(attempt+1));}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
        }
        synchronized(this){if(closed||pending==null){running=false;return;}io.execute(this::drain);}
    }
    public synchronized void close(){closed=true;cancel();io.shutdownNow();}
}
