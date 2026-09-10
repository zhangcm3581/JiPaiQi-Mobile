package com.jpq.mobile;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.util.function.BooleanSupplier;
import okhttp3.*;
import org.json.*;

/** Real MediaProjection with the user's video frames and an isolated local server. */
@RunWith(AndroidJUnit4.class)
public class CaptureFlowTest {
    void await(String message,BooleanSupplier condition)throws Exception{long deadline=SystemClock.elapsedRealtime()+15000;while(!condition.getAsBoolean()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);assertTrue(message+" / "+(CaptureService.current==null?"no service":CaptureService.current.status+" frames="+CaptureService.current.freshFrames+" start="+CaptureService.current.observedStart+" end="+CaptureService.current.observedEnd+" gap="+CaptureService.current.lastGap),condition.getAsBoolean());}
    void fixture(Context c,String name){c.startActivity(new Intent().setClassName("com.jpq.mobile.test","com.jpq.mobile.FixtureActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("fixture","shisanshui/"+name));}
    void replayPhase(Context target,String name,boolean start,boolean end,boolean visible)throws Exception {
        long before=CaptureService.current.freshFrames;
        target.sendBroadcast(new Intent("com.jpq.mobile.test.FRAME").setPackage("com.jpq.mobile.test").putExtra("fixture","shisanshui/"+name));
        await(name+" 实际悬浮窗显隐",()->CaptureService.current.freshFrames>=before+5&&CaptureService.current.observedStart==start&&CaptureService.current.observedEnd==end&&CaptureService.current.report().optBoolean("panel_visible")==visible);
        System.out.println("phase="+name+" panel="+visible+" frame_ms="+CaptureService.current.frameMillis);
    }
    void launchAndConsent(Context target,UiDevice device)throws Exception {
        target.startActivity(new Intent(target,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        UiObject2 consent=device.wait(Until.findObject(By.text("立即开始")),5000);
        if(consent==null)consent=device.wait(Until.findObject(By.text("Start now")),2000);
        assertNotNull("打开应用直接进行系统授权",consent);consent.click();
        await("截图服务启动",()->CaptureService.current!=null&&CaptureService.current.ledger!=null);
        assertNotNull(device.wait(Until.findObject(By.descStartsWith("悬浮控制栏")),5000));
        assertFalse("不再显示主面板",device.hasObject(By.text("十三水助手")));
        assertFalse(device.hasObject(By.text("开启悬浮控制")));
    }
    void openSettings(UiDevice device)throws Exception {
        UiObject2 menu=device.wait(Until.findObject(By.descStartsWith("悬浮控制栏")),5000);
        assertNotNull(menu);android.graphics.Rect r=menu.getVisibleBounds();
        device.click(r.left+r.width()*3/10,r.centerY());
        assertNotNull(device.wait(Until.findObject(By.text("保存")),5000));
        assertEquals(2,device.findObjects(By.clazz("android.widget.EditText")).size());
        assertFalse(device.hasObject(By.textContains("http://")));
    }
    @Test public void floatingEntryAndIdentitySaveKeepServiceAlive()throws Exception {
        var inst=InstrumentationRegistry.getInstrumentation();Context target=inst.getTargetContext();UiDevice device=UiDevice.getInstance(inst);
        var pref=target.getSharedPreferences("hand_upload",0);
        String oldEndpoint=pref.getString("endpoint",null),oldTenant=pref.getString("tenant_id",null),oldClient=pref.getString("client_id",null);boolean oldEnabled=pref.getBoolean("enabled",false);
        String tenant="6"+System.currentTimeMillis();OkHttpClient http=new OkHttpClient();
        assertEquals("设备测试必须使用内置本地地址","http://127.0.0.1:18081",BuildConfig.SERVER_URL);
        try(Response r=http.newCall(new Request.Builder().url(BuildConfig.SERVER_URL+"/api/tenants").post(RequestBody.create(new JSONObject().put("tenant_id",tenant).toString(),MediaType.get("application/json"))).build()).execute()){assertTrue(r.isSuccessful());}
        // A stale saved endpoint must never override the packaged address.
        pref.edit().putString("endpoint","http://127.0.0.1:18999").remove("tenant_id").remove("client_id").putBoolean("enabled",false).commit();
        try {
            device.pressHome();launchAndConsent(target,device);
            CaptureService original=CaptureService.current;assertFalse(original.automatic);
            UiObject2 menu=device.findObject(By.descStartsWith("悬浮控制栏"));
            android.graphics.Rect expanded=menu.getVisibleBounds();
            assertTrue("默认展开",expanded.width()>expanded.height()*2);
            device.click(expanded.right-expanded.width()/10,expanded.centerY());device.waitForIdle();
            assertTrue(device.findObject(By.descStartsWith("悬浮控制栏")).getVisibleBounds().width()<expanded.width()/2);
            target.startActivity(new Intent(target,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));device.waitForIdle();
            assertSame(original,CaptureService.current);
            await("再次打开应用恢复展开",()->{UiObject2 control=device.findObject(By.descStartsWith("悬浮控制栏"));return control!=null&&control.getVisibleBounds().width()==expanded.width();});
            openSettings(device);
            device.findObject(new UiSelector().className("android.widget.EditText").instance(0)).setText(tenant);
            device.findObject(new UiSelector().className("android.widget.EditText").instance(1)).setText("before_save");
            device.findObject(By.text("保存")).click();device.waitForIdle();
            assertEquals("before_save",pref.getString("client_id",""));assertFalse(pref.contains("endpoint"));
            await("新 ID 已连接",()->online(http,tenant,"before_save"));
            openSettings(device);
            device.findObject(new UiSelector().className("android.widget.EditText").instance(1)).setText("after_save");
            device.findObject(By.text("保存")).click();device.waitForIdle();
            await("切换 ID 关闭旧连接并重连",()->online(http,tenant,"after_save")&&!online(http,tenant,"before_save"));
            assertSame("保存 ID 保持原截图服务和悬浮窗",original,CaptureService.current);
            assertFalse(original.automatic);
            assertNotNull(device.wait(Until.findObject(By.descStartsWith("悬浮控制栏")),5000));
            openSettings(device);device.findObject(new UiSelector().className("android.widget.EditText").instance(1)).setText("discarded");
            device.findObject(By.text("取消")).click();device.waitForIdle();
            assertEquals("取消保留原 ID","after_save",pref.getString("client_id",""));assertSame(original,CaptureService.current);
            assertNotNull(device.wait(Until.findObject(By.descStartsWith("悬浮控制栏")),5000));
        }finally{
            target.stopService(new Intent(target,CaptureService.class));await("服务释放",()->CaptureService.current==null);
            pref.edit().putString("endpoint",oldEndpoint).putString("tenant_id",oldTenant).putString("client_id",oldClient).putBoolean("enabled",oldEnabled).commit();http.dispatcher().executorService().shutdown();device.pressHome();
        }
    }
    boolean online(OkHttpClient http,String tenant,String client) {
        try(Response r=http.newCall(new Request.Builder().url(BuildConfig.SERVER_URL+"/api/tenants/"+tenant+"/rounds/current").build()).execute()){
            JSONArray devices=new JSONObject(r.body().string()).getJSONArray("devices");
            for(int i=0;i<devices.length();i++){JSONObject d=devices.getJSONObject(i);if(client.equals(d.optString("client_id")))return d.optBoolean("online");}
        }catch(Exception ignored){}return false;
    }
    @Test public void realCaptureConfirmsAndUploadsOnceThenEnds()throws Exception {
        var inst=InstrumentationRegistry.getInstrumentation();Context target=inst.getTargetContext();UiDevice device=UiDevice.getInstance(inst);
        Assume.assumeTrue(Settings.canDrawOverlays(target));
        String tenant="8"+System.currentTimeMillis();OkHttpClient http=new OkHttpClient();
        try(Response r=http.newCall(new Request.Builder().url("http://127.0.0.1:18081/api/tenants").post(RequestBody.create(new JSONObject().put("tenant_id",tenant).toString(),MediaType.get("application/json"))).build()).execute()){assertTrue(r.isSuccessful());}
        var pref=target.getSharedPreferences("hand_upload",0);String oldEndpoint=pref.getString("endpoint",null),oldTenant=pref.getString("tenant_id",null),oldClient=pref.getString("client_id",null);boolean oldEnabled=pref.getBoolean("enabled",false);
        pref.edit().putString("endpoint","http://127.0.0.1:18081").putString("tenant_id",tenant).putString("client_id","capture_device_01").putBoolean("enabled",false).commit();
        try {
            try(var in=target.getAssets().open("default-pack.zip")){Pack.install(target,in);}
            launchAndConsent(target,device);
            fixture(target,"predeal");Thread.sleep(1200);inst.runOnMainSync(()->CaptureService.current.toggle());
            await("连接成功",()->CaptureService.current.status.startsWith("连接成功"));
            long beforePredeal=CaptureService.current.freshFrames;
            await("发牌前画面稳定采样",()->CaptureService.current.freshFrames>=beforePredeal+10&&!CaptureService.current.observedStart&&!CaptureService.current.observedEnd);
            for(String invalid:java.util.List.of("missing_12","extra_14")){
                int count=invalid.equals("missing_12")?12:14;
                replayPhase(target,invalid,true,false,true);
                await("真实截图的数量门槛 "+count,()->CaptureService.current.handStatus.startsWith("识别到 "+count+" /"));
                Thread.sleep(600);
                try(Response r=http.newCall(new Request.Builder().url(BuildConfig.SERVER_URL+"/api/tenants/"+tenant+"/rounds/current").build()).execute()){assertEquals("不是13张绝不能入库",0,new JSONObject(r.body().string()).getInt("received_count"));}
            }
            target.sendBroadcast(new Intent("com.jpq.mobile.test.FRAME").setPackage("com.jpq.mobile.test").putExtra("fixture","shisanshui/yellow_k"));await("完整手牌上传",()->CaptureService.current.status.contains("手牌已上报"));
            System.out.println("capture timing ms="+CaptureService.current.frameMillis+"; status="+CaptureService.current.status+"; hand="+CaptureService.current.handStatus);
            Thread.sleep(1200);try(Response r=http.newCall(new Request.Builder().url("http://127.0.0.1:18081/api/tenants/"+tenant+"/rounds/current").build()).execute()){
                JSONObject body=new JSONObject(r.body().string());assertEquals(1,body.getInt("received_count"));
                JSONArray cards=body.getJSONArray("devices").getJSONObject(0).getJSONArray("cards");java.util.List<String> received=new java.util.ArrayList<>();
                for(int i=0;i<cards.length();i++){JSONObject card=cards.getJSONObject(i);received.add(java.util.Map.of("s","spades","h","hearts","c","clubs","d","diamonds").get(card.getString("suit"))+":"+card.getString("rank"));}
                java.util.Collections.sort(received);assertEquals("服务器收到的13张逐张正确",FullVideoAuditTest.expected(6),received);
            }
            await("开局显示等待结果面板",()->CaptureService.current.report().optBoolean("panel_visible"));
            replayPhase(target,"arranging",true,false,true);
            CaptureService active=CaptureService.current;
            openSettings(device);device.findObject(By.text("保存")).click();device.waitForIdle();
            assertSame(active,CaptureService.current);assertTrue("ID 未改动不停止识别",active.automatic);
            await("关闭设置后摆牌面板恢复",()->active.report().optBoolean("panel_visible"));
            replayPhase(target,"confirm_ready",true,false,true);
            replayPhase(target,"comparing",false,false,false);
            try(Response r=http.newCall(new Request.Builder().url("http://127.0.0.1:18081/api/tenants/"+tenant+"/rounds/current").build()).execute()){JSONObject current=new JSONObject(r.body().string());assertEquals("比牌不能擅自结束服务器轮次",1,current.getInt("round_version"));assertEquals(1,current.getInt("received_count"));}
            replayPhase(target,"end_hall",false,true,false);
            await("结束确认",()->CaptureService.current.status.contains("本局结束"));
            Thread.sleep(500);try(Response r=http.newCall(new Request.Builder().url("http://127.0.0.1:18081/api/tenants/"+tenant+"/rounds/current").build()).execute()){assertEquals(2,new JSONObject(r.body().string()).getInt("round_version"));}

            // Stay on total-settlement screen, then the hall/pre-deal screen: service must remain active.
            CaptureService original=CaptureService.current;long frames=original.freshFrames;
            Thread.sleep(1000);assertSame(original,CaptureService.current);assertTrue(original.automatic);
            target.sendBroadcast(new Intent("com.jpq.mobile.test.FRAME").setPackage("com.jpq.mobile.test").putExtra("fixture","shisanshui/predeal"));
            Thread.sleep(2000);assertTrue(original.freshFrames>frames);assertTrue(original.automatic);
            try(Response r=http.newCall(new Request.Builder().url("http://127.0.0.1:18081/api/tenants/"+tenant+"/rounds/current").build()).execute()){JSONObject next=new JSONObject(r.body().string());assertEquals(2,next.getInt("round_version"));assertEquals(0,next.getInt("received_count"));}
            target.sendBroadcast(new Intent("com.jpq.mobile.test.FRAME").setPackage("com.jpq.mobile.test").putExtra("fixture","shisanshui/yellow_k"));
            await("下一桌新局上传",()->CaptureService.current.report().optInt("server_round")==2&&CaptureService.current.status.contains("手牌已上报"));
            assertSame(original,CaptureService.current);assertTrue(original.automatic);
            await("下一桌开局重新显示",()->original.report().optBoolean("panel_visible"));
            try(Response r=http.newCall(new Request.Builder().url("http://127.0.0.1:18081/api/tenants/"+tenant+"/rounds/current").build()).execute()){JSONObject next=new JSONObject(r.body().string());assertEquals(2,next.getInt("round_version"));assertEquals(1,next.getInt("received_count"));}
        } finally {
            target.stopService(new Intent(target,CaptureService.class));await("服务释放",()->CaptureService.current==null);
            pref.edit().putString("endpoint",oldEndpoint).putString("tenant_id",oldTenant).putString("client_id",oldClient).putBoolean("enabled",oldEnabled).commit();
            http.dispatcher().executorService().shutdown();device.pressHome();
        }
    }
}
