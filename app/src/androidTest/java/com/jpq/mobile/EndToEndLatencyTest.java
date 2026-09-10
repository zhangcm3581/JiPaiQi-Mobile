package com.jpq.mobile;

import android.content.*;
import android.os.*;
import android.view.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.UiDevice;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import okhttp3.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

/** Real screen capture -> recognition -> isolated server -> actual overlay frame commit.
 * Six peers use known valid hands; this is not seven simultaneous screen recognizers or public WAN.
 * Reflection observes the unchanged production renderer; all probes live in the test APK.
 */
@androidx.test.filters.LargeTest
@androidx.test.filters.SdkSuppress(minSdkVersion=29)
@RunWith(AndroidJUnit4.class)
public class EndToEndLatencyTest extends CaptureFlowTest {
    static Object field(Object owner,String name)throws Exception {var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    static long ns(){return SystemClock.elapsedRealtimeNanos();}
    static double ms(long end,long start){return (end-start)/1e6;}
    void frame(Context target,String name){target.sendBroadcast(new Intent("com.jpq.mobile.test.FRAME").setPackage("com.jpq.mobile.test").putExtra("fixture","shisanshui/"+name).putExtra("latency_probe",true));}
    @Test public void measureScreenToPaintAcrossRounds()throws Exception {
        var inst=InstrumentationRegistry.getInstrumentation();Context target=inst.getTargetContext();UiDevice device=UiDevice.getInstance(inst);
        assertEquals("isolated endpoint only","http://127.0.0.1:18081",BuildConfig.SERVER_URL);
        assertTrue(android.provider.Settings.canDrawOverlays(target));
        String tenant="5"+System.currentTimeMillis();OkHttpClient http=new OkHttpClient();
        try(Response r=http.newCall(new Request.Builder().url(BuildConfig.SERVER_URL+"/api/tenants").post(RequestBody.create(new JSONObject().put("tenant_id",tenant).toString(),MediaType.get("application/json"))).build()).execute()){assertTrue(r.isSuccessful());}
        var pref=target.getSharedPreferences("hand_upload",0);Map<String,?> previous=new HashMap<>(pref.getAll());
        pref.edit().clear().putString("tenant_id",tenant).putString("client_id","capture_latency").commit();
        HandlerThread thread=new HandlerThread("latency-peers");thread.start();Handler h=new Handler(thread.getLooper());
        var helper=new RoundSocketDeviceTest();List<RoundSession> peers=new ArrayList<>();List<RoundSocket> sockets=new ArrayList<>();JSONArray records=new JSONArray();
        AtomicLong sourcePresentedNs=new AtomicLong();java.util.concurrent.atomic.AtomicReference<String> sourceFixture=new java.util.concurrent.atomic.AtomicReference<>("");
        BroadcastReceiver presented=new BroadcastReceiver(){public void onReceive(Context c,Intent i){sourcePresentedNs.set(i.getLongExtra("presented_ns",0));sourceFixture.set(i.getStringExtra("fixture"));}};
        if(Build.VERSION.SDK_INT>=33)target.registerReceiver(presented,new IntentFilter("com.jpq.mobile.test.PRESENTED"),Context.RECEIVER_EXPORTED);else target.registerReceiver(presented,new IntentFilter("com.jpq.mobile.test.PRESENTED"));
        View[] gridRef=new View[1];ViewTreeObserver.OnPreDrawListener[] listenerRef=new ViewTreeObserver.OnPreDrawListener[1];
        try {
            launchAndConsent(target,device);fixture(target,"predeal");Thread.sleep(1200);inst.runOnMainSync(()->CaptureService.current.toggle());
            await("capture connected",()->CaptureService.current.status.startsWith("连接成功"));
            CaptureService service=CaptureService.current;RoundSession session=(RoundSession)field(service,"roundSession");HandOverlay overlay=(HandOverlay)field(service,"handOverlay");View grid=(View)field(overlay,"grid");gridRef[0]=grid;
            helper.on(h,()->{for(int i=0;i<6;i++){RoundSession p=new RoundSession(tenant,"peer_"+i,"",v->{});peers.add(p);sockets.add(new RoundSocket(h,p,BuildConfig.SERVER_URL));}});
            helper.await("six peers connected",()->peers.stream().allMatch(p->p.status.startsWith("连接成功")));
            AtomicLong desiredRound=new AtomicLong(),emptyPaint=new AtomicLong(),resultPaint=new AtomicLong();
            listenerRef[0]=()->{try{HandReply reply=(HandReply)field(overlay,"reply");long round=desiredRound.get();boolean visible=grid.isShown();grid.getViewTreeObserver().registerFrameCommitCallback(()->{if(desiredRound.get()!=round||!visible)return;emptyPaint.compareAndSet(0,ns());if(reply!=null&&reply.round==round&&reply.totalCount==13)resultPaint.compareAndSet(0,ns());});}catch(Exception e){throw new RuntimeException(e);}return true;};
            inst.runOnMainSync(()->{assertTrue("hardware frame commit required",grid.isHardwareAccelerated());grid.getViewTreeObserver().addOnPreDrawListener(listenerRef[0]);});
            // 24 normal rounds across all eight recorded hands, then three deliberately delayed peers.
            for(int index=0;index<27;index++){
                int round=index+1,golden=index%8+1;boolean delayed=index>=24;
                List<String> own=FullVideoAuditTest.expected(golden),deck=new ArrayList<>();
                for(String suit:HandSnapshots.SUITS)for(String rank:HandSnapshots.RANKS)for(int copy=0;copy<2;copy++)deck.add(suit+":"+rank);
                for(String card:own)assertTrue(deck.remove(card));
                List<String> remaining=new ArrayList<>(deck.subList(78,91));
                frame(target,"predeal");long before=service.freshFrames;
                await("pre-deal settles",()->service.freshFrames>=before+6&&!service.observedStart&&!service.observedEnd);
                helper.on(h,()->{long t=SystemClock.elapsedRealtime();for(RoundSession p:peers){p.frame(t-600,false,false,List.of());p.frame(t,false,false,List.of());}});
                Thread.sleep(30+(index*47)%190); // vary sampling phase, do not align every deal to capture tick
                desiredRound.set(round);emptyPaint.set(0);resultPaint.set(0);
                long requestNs=ns();String fixture="golden_round_"+golden;frame(target,fixture);
                helper.on(h,()->{long t=SystemClock.elapsedRealtime();for(int i=0;i<(delayed?5:6);i++){RoundSession p=peers.get(i);List<String> hand=deck.subList(i*13,i*13+13);p.frame(t,true,false,hand);p.frame(t+200,true,false,hand);p.frame(t+400,true,false,hand);sockets.get(i).flush();}});
                long recognizedNs=0,receivedNs=0,lastPeerSentNs=0;boolean lateSent=!delayed;
                long deadline=SystemClock.elapsedRealtime()+12000;
                while(resultPaint.get()==0&&SystemClock.elapsedRealtime()<deadline){
                    if(recognizedNs==0&&service.observedStart&&service.handStatus.startsWith("识别到 13 /"))recognizedNs=ns();
                    if(receivedNs==0&&session.result!=null&&session.result.round==round)receivedNs=ns();
                    if(!lateSent&&ms(ns(),requestNs)>=2500){lastPeerSentNs=ns();helper.on(h,()->{long t=SystemClock.elapsedRealtime();RoundSession p=peers.get(5);List<String> hand=deck.subList(65,78);p.frame(t-600,false,false,List.of());p.frame(t,false,false,List.of());p.frame(t+200,true,false,hand);p.frame(t+400,true,false,hand);p.frame(t+600,true,false,hand);sockets.get(5).flush();});lateSent=true;}
                    Thread.sleep(5);
                }
                assertTrue("result drawn round "+round+" / "+session.status,resultPaint.get()>0);
                assertEquals("freshly rendered fixture","shisanshui/"+fixture,sourceFixture.get());
                long sourceNs=sourcePresentedNs.get();assertTrue(sourceNs>=requestNs);
                assertNotNull(session.result);assertTrue(service.report().optBoolean("panel_visible"));
                for(int row=0;row<8;row++)for(int col=0;col<13;col++){String token=HandSnapshots.SUITS.get(row/2)+":"+HandSnapshots.RANKS.get(col);assertEquals("result identity "+token,Collections.frequency(remaining,token)>row%2,session.result.occupied(row,col));}
                // Screenshot readback happens after the measured frame, outside the timed interval.
                Thread.sleep(80);var screen=inst.getUiAutomation().takeScreenshot();assertNotNull(screen);try{android.graphics.Rect rect=overlay.bounds().get(1);int inkCount=0;float cw=rect.width()/14f,rh=rect.height()*.9f/8;
                    for(int row=0;row<8;row++)for(int col=0;col<13;col++){boolean ink=false;for(int y=rect.top+(int)(row*rh)+2;y<rect.top+(int)((row+1)*rh)-2;y++)for(int x=rect.left+(int)((col+1)*cw)+2;x<rect.left+(int)((col+2)*cw)-2;x++){int color=screen.getPixel(x,y),r=android.graphics.Color.red(color),g=android.graphics.Color.green(color),b=android.graphics.Color.blue(color);if((r<140&&g<140&&b<140)||(r>130&&g<100&&b<130))ink=true;}assertEquals("pixel row="+row+" col="+col,session.result.occupied(row,col),ink);if(ink)inkCount++;}assertEquals(13,inkCount);
                }finally{screen.recycle();}
                JSONObject record=new JSONObject().put("round",round).put("golden_hand",golden).put("scenario",delayed?"last_peer_delayed_2500ms":"six_peers_ready_during_capture").put("screen_to_result_ms",ms(resultPaint.get(),sourceNs)).put("request_to_result_ms",ms(resultPaint.get(),requestNs)).put("screen_to_empty_panel_ms",ms(emptyPaint.get(),sourceNs)).put("first_13_observed_to_result_ms",recognizedNs==0?JSONObject.NULL:ms(resultPaint.get(),recognizedNs)).put("received_to_paint_ms",receivedNs==0?JSONObject.NULL:ms(resultPaint.get(),receivedNs)).put("last_peer_send_to_paint_ms",delayed?ms(resultPaint.get(),lastPeerSentNs):JSONObject.NULL).put("correct_13_painted",true);
                records.put(record);System.out.println("LATENCY "+record);
                try(var out=new java.io.FileWriter(new java.io.File(target.getFilesDir(),"latency-results.json"))){out.write(new JSONObject().put("endpoint",BuildConfig.SERVER_URL).put("records",records).toString(2));}
                frame(target,"end_hall");await("end clears",()->session.result==null&&!service.report().optBoolean("panel_visible")&&session.status.contains("本局结束"));
                helper.on(h,()->{long t=SystemClock.elapsedRealtime();for(int i=0;i<6;i++){RoundSession p=peers.get(i);p.frame(t,false,true,List.of());p.frame(t+200,false,true,List.of());sockets.get(i).flush();}});
                helper.await("peers reset",()->peers.stream().allMatch(p->p.result==null));
            }
            assertEquals(27,records.length());
        }finally{
            target.unregisterReceiver(presented);
            if(gridRef[0]!=null&&listenerRef[0]!=null)inst.runOnMainSync(()->gridRef[0].getViewTreeObserver().removeOnPreDrawListener(listenerRef[0]));
            helper.on(h,()->{for(RoundSocket socket:sockets)socket.close();});thread.quitSafely();
            target.stopService(new Intent(target,CaptureService.class));await("service stopped",()->CaptureService.current==null);
            var edit=pref.edit().clear();for(var e:previous.entrySet()){Object v=e.getValue();if(v instanceof String)edit.putString(e.getKey(),(String)v);else if(v instanceof Boolean)edit.putBoolean(e.getKey(),(Boolean)v);else if(v instanceof Integer)edit.putInt(e.getKey(),(Integer)v);else if(v instanceof Long)edit.putLong(e.getKey(),(Long)v);else if(v instanceof Float)edit.putFloat(e.getKey(),(Float)v);}edit.commit();http.dispatcher().executorService().shutdown();device.pressHome();
        }
    }
}
