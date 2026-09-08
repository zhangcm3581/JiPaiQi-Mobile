package com.jpq.mobile;
import android.content.*;
import android.graphics.*;
import android.provider.Settings;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.UiDevice;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class HandOverlayDeviceTest {
    @Test public void gridAndCollapsibleControls()throws Exception{
        var inst=InstrumentationRegistry.getInstrumentation();Context target=inst.getTargetContext();Assume.assumeTrue(Settings.canDrawOverlays(target));UiDevice device=UiDevice.getInstance(inst);
        target.startActivity(new Intent().setClassName("com.jpq.mobile.test","com.jpq.mobile.FixtureActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("fixture","overlap"));SystemClock.sleep(800);
        AtomicInteger clicked=new AtomicInteger();HandOverlay[] overlay=new HandOverlay[1];
        JSONObject json=new JSONObject("{\"schema\":\"jpq.hand-result/1\",\"event_id\":\"test\",\"round_id\":1,\"cards\":[{\"rank\":\"A\",\"suit\":\"spades\",\"count\":2},{\"rank\":\"Q\",\"suit\":\"hearts\",\"count\":1},{\"rank\":\"5\",\"suit\":\"clubs\",\"count\":2},{\"rank\":\"2\",\"suit\":\"diamonds\",\"count\":1}]}");HandReply reply=new HandReply(json);
        inst.runOnMainSync(()->{overlay[0]=new HandOverlay(target,new HandOverlay.Actions(){public void toggle(){clicked.incrementAndGet();}public void settings(){}public void help(){}public void exit(){}});overlay[0].update(true,reply,"测试响应 · 非真实服务器");});
        try{SystemClock.sleep(400);assertEquals(2,overlay[0].bounds().size());save("hand-overlay-expanded.png");Rect menu=overlay[0].bounds().get(0);device.click(menu.left+menu.width()/10,menu.centerY());SystemClock.sleep(150);assertEquals(1,clicked.get());device.click(menu.right-menu.width()/10,menu.centerY());SystemClock.sleep(200);Rect tab=overlay[0].bounds().get(0);assertEquals("收起后吸附右边",device.getDisplayWidth(),tab.right);assertEquals("收起保留高度",menu.top,tab.top);assertTrue(tab.width()<menu.width()/3);assertTrue("收起按钮至少 48dp",tab.width()>=Math.round(48*target.getResources().getDisplayMetrics().density));device.swipe(tab.centerX(),tab.centerY(),650,150,20);SystemClock.sleep(200);tab=overlay[0].bounds().get(0);int retainedY=tab.top;assertEquals("拖动收起按钮后仍吸附右边",device.getDisplayWidth(),tab.right);save("hand-overlay-collapsed.png");device.click(tab.centerX(),tab.centerY());SystemClock.sleep(200);assertEquals(menu.width(),overlay[0].bounds().get(0).width());assertEquals("展开后右侧对齐",device.getDisplayWidth(),overlay[0].bounds().get(0).right);assertEquals("原地展开 Y",retainedY,overlay[0].bounds().get(0).top);save("hand-overlay-expanded.png");Rect panel=overlay[0].bounds().get(1);device.click(panel.centerX(),panel.centerY());SystemClock.sleep(150);assertEquals(1,overlay[0].bounds().size());inst.runOnMainSync(()->overlay[0].togglePanel());assertEquals(2,overlay[0].bounds().size());
            for(int i=0;i<3;i++){Rect open=overlay[0].bounds().get(0);device.click(open.right-open.width()/10,open.centerY());SystemClock.sleep(150);Rect closed=overlay[0].bounds().get(0);assertEquals(device.getDisplayWidth(),closed.right);assertEquals(open.top,closed.top);assertTrue(closed.left>=0&&closed.bottom<=device.getDisplayHeight());device.click(closed.centerX(),closed.centerY());SystemClock.sleep(150);assertEquals(open,overlay[0].bounds().get(0));}
        }
        finally{inst.runOnMainSync(()->overlay[0].close());device.pressHome();}
    }
    void save(String name)throws Exception{var inst=InstrumentationRegistry.getInstrumentation();try(var out=new FileOutputStream(new File(inst.getTargetContext().getFilesDir(),name))){Bitmap b=inst.getUiAutomation().takeScreenshot();assertNotNull(b);b.compress(Bitmap.CompressFormat.PNG,100,out);b.recycle();}}
}
