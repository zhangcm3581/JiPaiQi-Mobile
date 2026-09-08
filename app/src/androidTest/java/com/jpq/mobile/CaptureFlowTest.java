package com.jpq.mobile;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.graphics.Bitmap;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.*;
import java.util.function.BooleanSupplier;

@RunWith(AndroidJUnit4.class)
public class CaptureFlowTest {
    void await(String message,BooleanSupplier condition)throws Exception{long deadline=SystemClock.elapsedRealtime()+15000;while(!condition.getAsBoolean()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);assertTrue(message,condition.getAsBoolean());}
    void fixture(Context c,String name){c.startActivity(new Intent().setClassName("com.jpq.mobile.test","com.jpq.mobile.FixtureActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("fixture",name));}
    @Test public void realCaptureOverlayAndEndLatch()throws Exception{
        var instrumentation=InstrumentationRegistry.getInstrumentation();Context target=instrumentation.getTargetContext();UiDevice device=UiDevice.getInstance(instrumentation);
        Assume.assumeTrue("先通过系统界面允许悬浮窗",Settings.canDrawOverlays(target));
        target.startActivity(new Intent(target,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        UiObject2 start=device.wait(Until.findObject(By.text("开启悬浮控制")),10000);assertNotNull(start);Thread.sleep(700);start.click();
        UiObject2 consent=device.wait(Until.findObject(By.text("立即开始")),10000);assertNotNull("系统屏幕采集授权弹窗",consent);consent.click();
        try{
            await("前台采集服务启动",()->CaptureService.current!=null&&CaptureService.current.ledger!=null);
            assertTrue("悬浮控制栏创建完成",device.wait(Until.hasObject(By.descContains("悬浮控制栏")),5000));
            instrumentation.runOnMainSync(()->{CaptureService.current.toggle();CaptureService.current.toggleHandPanel();});
            fixture(target,"overlap");
            await("实际截图确认开局",()->CaptureService.current.ledger.phase==Ledger.Phase.PLAYING);
            assertEquals("十三水两副牌牌库",104,CaptureService.current.ledger.remaining().values().stream().mapToInt(Integer::intValue).sum());
            assertEquals("v2 不计入旧出牌观察",0,CaptureService.current.ledger.events.size());
            assertTrue(CaptureService.current.frameMillis>0);
            long before=CaptureService.current.ledger.events.size();Thread.sleep(1600);assertEquals("持续显示不重复入账",before,CaptureService.current.ledger.events.size());
            File out=new File(target.getFilesDir(),"overlay-test.png");try(FileOutputStream stream=new FileOutputStream(out)){instrumentation.getUiAutomation().takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,stream);}
            fixture(target,"settlement");await("结算锁定",()->CaptureService.current.ledger.phase==Ledger.Phase.ENDED);
            int events=CaptureService.current.ledger.events.size();Thread.sleep(1000);assertEquals("结束后不生成出牌",events,CaptureService.current.ledger.events.size());
            instrumentation.runOnMainSync(()->CaptureService.current.toggle());assertFalse(CaptureService.current.automatic);
            instrumentation.runOnMainSync(()->CaptureService.current.toggle());assertTrue(CaptureService.current.automatic);
        }catch(Throwable error){try(FileOutputStream stream=new FileOutputStream(new File(target.getFilesDir(),"flow-failure.png"))){instrumentation.getUiAutomation().takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,stream);}throw error;}finally{target.stopService(new Intent(target,CaptureService.class));await("停止后释放服务",()->CaptureService.current==null);device.pressHome();}
    }
}
