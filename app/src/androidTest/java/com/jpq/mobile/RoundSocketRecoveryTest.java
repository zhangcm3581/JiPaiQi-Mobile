package com.jpq.mobile;

import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Fault injection over real Android WebSockets, without production traffic. */
@RunWith(AndroidJUnit4.class)
public class RoundSocketRecoveryTest {
    @Test public void silentConnectionReconnectsAfterResponseDeadline()throws Exception {check("silent");}
    @Test public void registrationErrorStopsWithSpecificMessage()throws Exception {check("TENANT_NOT_FOUND");}
    @Test public void duplicatedClientStopsReconnectFight()throws Exception {check("duplicate");}
    @Test public void pausedGateStopsPendingRetriesHeartbeatAndReconnect()throws Exception {
        var allowed=new java.util.concurrent.atomic.AtomicBoolean(true);AtomicInteger messages=new AtomicInteger();
        MockWebServer server=new MockWebServer();server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener(){
            @Override public void onMessage(WebSocket ws,String text){messages.incrementAndGet();}
        }));server.start();HandlerThread thread=new HandlerThread("pause-network-test");thread.start();Handler worker=new Handler(thread.getLooper());
        RoundSession session=new RoundSession("900092","paused","",v->{});RoundSocket[] socket=new RoundSocket[1];
        var helper=new RoundSocketDeviceTest();
        try{
            helper.on(worker,()->socket[0]=new RoundSocket(worker,session,server.url("/").toString(),()->{},allowed::get));
            assertNotNull(server.takeRequest(5,TimeUnit.SECONDS));Thread.sleep(200);
            helper.on(worker,()->{
                session.receive(new JSONObject().put("protocol_version",1).put("tenant_id","900092").put("round_version",1).put("type","round.state").put("payload",new JSONObject().put("enabled",true).put("state","waiting").put("bound_round_version",JSONObject.NULL)));
                session.frame(0,false,false,java.util.List.of());session.frame(700,false,false,java.util.List.of());
                session.frame(800,true,false,FullVideoAuditTest.expected(6));session.frame(900,true,false,FullVideoAuditTest.expected(6));socket[0].flush();
            });
            helper.await("original pending hand sent",()->messages.get()==1);
            allowed.set(false);helper.on(worker,()->socket[0].flush());
            int atPause=messages.get();Thread.sleep(3400);
            assertEquals("暂停不发送旧请求或心跳",atPause,messages.get());assertEquals("暂停不重新握手",1,server.getRequestCount());
            assertTrue("暂停不再有可发送请求",session.outgoing().isEmpty());
        }finally{helper.on(worker,()->{if(socket[0]!=null)socket[0].close();});thread.quitSafely();server.shutdown();}
    }
    private void check(String mode)throws Exception {
        AtomicInteger connections=new AtomicInteger();MockWebServer server=new MockWebServer();
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){
            return new MockResponse().withWebSocketUpgrade(new WebSocketListener(){@Override public void onOpen(WebSocket ws,Response response){
                connections.incrementAndGet();
                try{
                    if(mode.equals("duplicate")){ws.close(4001,"client replaced");return;}
                    JSONObject msg=new JSONObject().put("protocol_version",1).put("tenant_id","900091").put("round_version",1);
                    if(mode.equals("silent"))msg.put("type","round.state").put("payload",new JSONObject().put("enabled",true).put("state","waiting").put("bound_round_version",JSONObject.NULL));
                    else msg.put("type","error").put("payload",new JSONObject().put("code",mode).put("message","tenant does not exist"));
                    ws.send(msg.toString()); // Intentionally ignore application pings afterward.
                }catch(JSONException e){throw new AssertionError(e);}
            }});
        }});
        server.start();HandlerThread thread=new HandlerThread("socket-fault-test");thread.start();Handler worker=new Handler(thread.getLooper());
        RoundSession session=new RoundSession("900091","fault_test","",v->{});RoundSocket[] socket=new RoundSocket[1];
        CountDownLatch created=new CountDownLatch(1);worker.post(()->{socket[0]=new RoundSocket(worker,session,server.url("/").toString(),100,600,150);created.countDown();});assertTrue(created.await(5,TimeUnit.SECONDS));
        try{
            long deadline=SystemClock.elapsedRealtime()+5000;
            String code=mode.equals("duplicate")?"CLIENT_ID_IN_USE":mode;
            while(SystemClock.elapsedRealtime()<deadline){if(mode.equals("silent")?connections.get()>=2:session.status.startsWith(code))break;Thread.sleep(40);}
            if(mode.equals("silent"))assertTrue("无响应必须重连",connections.get()>=2);
            else{assertTrue(session.status,session.status.startsWith(code));Thread.sleep(1200);assertEquals("配置错误不应重复连接",1,connections.get());assertTrue(session.status,session.status.startsWith(code));}
        }finally{
            CountDownLatch stopped=new CountDownLatch(1);worker.post(()->{socket[0].close();stopped.countDown();});assertTrue(stopped.await(5,TimeUnit.SECONDS));thread.quitSafely();server.shutdown();
        }
    }
}
