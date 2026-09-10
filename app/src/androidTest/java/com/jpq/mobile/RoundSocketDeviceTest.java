package com.jpq.mobile;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import okhttp3.*;

@RunWith(AndroidJUnit4.class)
public class RoundSocketDeviceTest {
    interface Job {void run()throws Exception;}
    void on(Handler h,Job job)throws Exception{CompletableFuture<Void> f=new CompletableFuture<>();h.post(()->{try{job.run();f.complete(null);}catch(Throwable e){f.completeExceptionally(e);}});f.get(10,TimeUnit.SECONDS);}
    void await(String label,java.util.function.BooleanSupplier ready)throws Exception{long until=SystemClock.elapsedRealtime()+10000;while(!ready.getAsBoolean()&&SystemClock.elapsedRealtime()<until)Thread.sleep(50);assertTrue(label,ready.getAsBoolean());}
    @Test public void sevenAndroidClientsUseActualServerProtocol()throws Exception {
        String tenant="9"+System.currentTimeMillis();OkHttpClient http=new OkHttpClient();
        Request request=new Request.Builder().url("http://127.0.0.1:18081/api/tenants").post(RequestBody.create(new JSONObject().put("tenant_id",tenant).put("note","Android isolated integration").toString(),MediaType.get("application/json"))).build();
        try(Response res=http.newCall(request).execute()){assertTrue("tenant creation "+res.code(),res.isSuccessful());}
        HandlerThread t=new HandlerThread("ws-test");t.start();Handler h=new Handler(t.getLooper());List<RoundSession> sessions=new ArrayList<>();List<RoundSocket> sockets=new ArrayList<>();List<String> deck=new ArrayList<>();
        for(String suit:HandSnapshots.SUITS)for(String rank:HandSnapshots.RANKS)for(int i=0;i<2;i++)deck.add(suit+":"+rank);
        List<String> expected=List.of("spades:A","spades:A","spades:10","spades:K","hearts:Q","hearts:7","hearts:7","clubs:5","clubs:5","clubs:J","diamonds:2","diamonds:9","diamonds:9");
        for(String card:expected)assertTrue(deck.remove(card));
        try{
            on(h,()->{for(int i=0;i<7;i++){RoundSession s=new RoundSession(tenant,"integration_"+i,"",v->{});sessions.add(s);sockets.add(new RoundSocket(h,s,"http://127.0.0.1:18081"));}});
            long deadline=SystemClock.elapsedRealtime()+10000;
            while(SystemClock.elapsedRealtime()<deadline){if(sessions.stream().allMatch(s->s.status.startsWith("连接成功")))break;Thread.sleep(50);}
            assertTrue(sessions.toString(),sessions.stream().allMatch(s->s.status.startsWith("连接成功")));
            on(h,()->{for(int i=0;i<6;i++){RoundSession s=sessions.get(i);List<String> hand=deck.subList(i*13,i*13+13);s.frame(0,false,false,List.of());s.frame(600,false,false,List.of());s.frame(800,true,false,hand);s.frame(1000,true,false,hand);s.frame(1200,true,false,hand);sockets.get(i).flush();}});
            await("前六台上报",()->sessions.subList(0,6).stream().allMatch(s->s.status.contains("手牌已上报")));
            on(h,()->{RoundSession s=sessions.get(0);s.frame(1400,false,false,List.of());s.frame(2000,false,false,List.of());assertFalse(s.panel);
                RoundSession last=sessions.get(6);List<String> hand=deck.subList(78,91);last.frame(0,false,false,List.of());last.frame(600,false,false,List.of());last.frame(800,true,false,hand);last.frame(1000,true,false,hand);last.frame(1200,true,false,hand);sockets.get(6).flush();});
            await("七台收齐，返回13张",()->sessions.stream().allMatch(s->s.result!=null));
            assertFalse("比牌阶段迟到结果不得重新打开面板",sessions.get(0).panel);
            for(RoundSession s:sessions.subList(1,7))assertTrue("其他设备仍在摆牌",s.panel);
            for(RoundSession s:sessions){assertNotNull(s.status,s.result);int count=0;for(int row=0;row<8;row++)for(int col=0;col<13;col++)if(s.result.occupied(row,col))count++;assertEquals(13,count);}
            verifyVisibleGrid(sessions.get(0).result,expected);
            on(h,()->{RoundSession s=sessions.get(0);s.frame(2200,true,false,List.of());s.frame(2400,true,false,List.of());assertTrue("回到摆牌仍显示，无需13张留在底部",s.panel);assertNotNull(s.result);assertTrue(s.outgoing().isEmpty());
                s.frame(2600,false,true,List.of());s.frame(2800,false,true,List.of());sockets.get(0).flush();});
            await("一台结束后，其余设备清空旧结果",()->sessions.subList(1,7).stream().allMatch(s->s.result==null));
            on(h,()->{assertFalse(sessions.get(0).panel);for(int i=1;i<7;i++){RoundSession s=sessions.get(i);assertTrue("服务器换轮不能隐藏本机摆牌面板",s.panel);s.frame(1400,false,true,List.of());s.frame(1600,false,true,List.of());sockets.get(i).flush();}});
            // Other devices ending the already closed old round must recover without ID reset.
            Thread.sleep(600);on(h,()->{for(RoundSession s:sessions)assertTrue(s.status,s.outgoing().isEmpty());
                for(int i=0;i<7;i++){RoundSession s=sessions.get(i);List<String> hand=deck.subList(i*13,i*13+13);s.frame(3000,false,false,List.of());s.frame(3600,false,false,List.of());s.frame(3800,true,false,hand);s.frame(4000,true,false,hand);s.frame(4200,true,false,hand);sockets.get(i).flush();}});
            await("七台连续第二局返回结果",()->sessions.stream().allMatch(s->s.round==2&&s.result!=null));
            on(h,()->{for(RoundSession s:sessions){assertTrue(s.panel);int count=0;for(int row=0;row<8;row++)for(int col=0;col<13;col++)if(s.result.occupied(row,col))count++;assertEquals(13,count);}});
            System.out.println("7 real Android WebSockets x 2 rounds; delayed result/early end/local visibility verified");
        }finally{on(h,()->{for(RoundSocket socket:sockets)socket.close();});t.quitSafely();http.dispatcher().executorService().shutdown();}
    }
    void verifyVisibleGrid(HandReply reply,List<String> expected)throws Exception {
        var inst=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();var target=inst.getTargetContext();
        assertTrue("需要悬浮窗权限验证真实面板",android.provider.Settings.canDrawOverlays(target));
        target.startActivity(new android.content.Intent().setClassName("com.jpq.mobile.test","com.jpq.mobile.FixtureActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("fixture","shisanshui/confirm_ready"));
        Thread.sleep(800);
        HandOverlay[] overlay=new HandOverlay[1];
        inst.runOnMainSync(()->{overlay[0]=new HandOverlay(target,new HandOverlay.Actions(){public void toggle(){}public void settings(){}public void help(){}public void exit(){}});overlay[0].update(true,reply,"服务器结果",true);});
        try {
            Thread.sleep(400);android.graphics.Rect panel=overlay[0].bounds().get(1);android.graphics.Bitmap screen=inst.getUiAutomation().takeScreenshot();assertNotNull(screen);
            try {
                int painted=0;
                for(int row=0;row<8;row++)for(int col=0;col<13;col++){
                    String token=HandSnapshots.SUITS.get(row/2)+":"+HandSnapshots.RANKS.get(col);
                    boolean wanted=java.util.Collections.frequency(expected,token)>(row%2);
                    assertEquals("server cell "+token+" copy "+row%2,wanted,reply.occupied(row,col));
                    float cw=panel.width()/14f,rh=panel.height()*.9f/8;
                    int x0=panel.left+(int)((col+1)*cw)+2,x1=panel.left+(int)((col+2)*cw)-2;
                    int y0=panel.top+(int)(row*rh)+2,y1=panel.top+(int)((row+1)*rh)-2;
                    boolean ink=false;
                    for(int y=y0;y<y1;y++)for(int x=x0;x<x1;x++){
                        int color=screen.getPixel(x,y),r=android.graphics.Color.red(color),g=android.graphics.Color.green(color),b=android.graphics.Color.blue(color);
                        if((r<140&&g<140&&b<140)||(r>130&&g<100&&b<130))ink=true;
                    }
                    assertEquals("painted cell "+token+" copy "+row%2,wanted,ink);if(ink)painted++;
                }
                assertEquals("实际截图必须恰好绘制13个牌格",13,painted);
                try(var out=new java.io.FileOutputStream(new java.io.File(target.getFilesDir(),"verified-server-13-cards.png"))){screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
            }finally{screen.recycle();}
        }finally{inst.runOnMainSync(()->overlay[0].close());}
    }

}
