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
        JSONObject json=new JSONObject("{\"schema\":\"jpq.hand-result/1\",\"event_id\":\"test\",\"round_id\":1,\"cards\":[{\"rank\":\"A\",\"suit\":\"spades\",\"count\":2},{\"rank\":\"Q\",\"suit\":\"hearts\",\"count\":1},{\"rank\":\"5\",\"suit\":\"clubs\",\"count\":2},{\"rank\":\"2\",\"suit\":\"diamonds\",\"count\":1}]}");JSONArray values=json.getJSONArray("cards");
        for(String rank:java.util.List.of("3","4","6","7","8","9","10"))values.put(new JSONObject().put("rank",rank).put("suit","diamonds").put("count",1));
        HandReply reply=new HandReply(json);assertEquals(13,reply.totalCount);
        inst.runOnMainSync(()->{overlay[0]=new HandOverlay(target,new HandOverlay.Actions(){public void toggle(){clicked.incrementAndGet();}public void settings(){}public void help(){}public void exit(){}});overlay[0].update(true,reply,"测试响应 · 非真实服务器");});
        try{SystemClock.sleep(400);assertEquals(2,overlay[0].bounds().size());save("hand-overlay-expanded.png");Rect menu=overlay[0].bounds().get(0);device.click(menu.left+menu.width()/10,menu.centerY());SystemClock.sleep(150);assertEquals(1,clicked.get());device.click(menu.right-menu.width()/10,menu.centerY());SystemClock.sleep(200);Rect tab=overlay[0].bounds().get(0);assertEquals("收起后吸附右边",device.getDisplayWidth(),tab.right);assertEquals("收起保留高度",menu.top,tab.top);assertTrue(tab.width()<menu.width()/3);assertTrue("收起按钮至少 48dp",tab.width()>=Math.round(48*target.getResources().getDisplayMetrics().density));device.swipe(tab.centerX(),tab.centerY(),650,150,20);SystemClock.sleep(200);tab=overlay[0].bounds().get(0);int retainedY=tab.top;assertEquals("拖动收起按钮后仍吸附右边",device.getDisplayWidth(),tab.right);save("hand-overlay-collapsed.png");device.click(tab.centerX(),tab.centerY());SystemClock.sleep(200);assertEquals(menu.width(),overlay[0].bounds().get(0).width());assertEquals("展开后右侧对齐",device.getDisplayWidth(),overlay[0].bounds().get(0).right);assertEquals("原地展开 Y",retainedY,overlay[0].bounds().get(0).top);save("hand-overlay-expanded.png");Rect panel=overlay[0].bounds().get(1);device.click(panel.centerX(),panel.centerY());SystemClock.sleep(150);assertEquals(1,overlay[0].bounds().size());inst.runOnMainSync(()->overlay[0].togglePanel());assertEquals(2,overlay[0].bounds().size());
            for(int i=0;i<3;i++){Rect open=overlay[0].bounds().get(0);device.click(open.right-open.width()/10,open.centerY());SystemClock.sleep(150);Rect closed=overlay[0].bounds().get(0);assertEquals(device.getDisplayWidth(),closed.right);assertEquals(open.top,closed.top);assertTrue(closed.left>=0&&closed.bottom<=device.getDisplayHeight());device.click(closed.centerX(),closed.centerY());SystemClock.sleep(150);assertEquals(open,overlay[0].bounds().get(0));}
        }
        finally{inst.runOnMainSync(()->overlay[0].close());device.pressHome();}
    }
    @Test public void panelFollowsStartPauseAndCannotBeOpenedWhilePaused()throws Exception{
        var inst=InstrumentationRegistry.getInstrumentation();Context target=inst.getTargetContext();Assume.assumeTrue(Settings.canDrawOverlays(target));UiDevice device=UiDevice.getInstance(inst);
        target.startActivity(new Intent().setClassName("com.jpq.mobile.test","com.jpq.mobile.FixtureActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("fixture","shisanshui/yellow_k"));SystemClock.sleep(800);
        HandOverlay[] overlay=new HandOverlay[1];java.util.concurrent.atomic.AtomicBoolean active=new java.util.concurrent.atomic.AtomicBoolean();
        inst.runOnMainSync(()->overlay[0]=new HandOverlay(target,new HandOverlay.Actions(){public void toggle(){active.set(!active.get());overlay[0].update(active.get(),null,active.get()?"等待新局":"已暂停");}public void settings(){}public void help(){}public void exit(){}}));
        try{
            device.waitForIdle();SystemClock.sleep(400);
            assertEquals("初始仅显示控制栏",1,overlay[0].bounds().size());
            inst.runOnMainSync(()->{overlay[0].update(false,null,"已暂停");overlay[0].togglePanel();});assertEquals("暂停时不能手动打开面板",1,overlay[0].bounds().size());
            for(int i=0;i<3;i++){Rect menu=overlay[0].bounds().get(0);device.click(menu.left+menu.width()/10,menu.centerY());SystemClock.sleep(150);assertEquals("启动显示面板",2,overlay[0].bounds().size());device.click(menu.left+menu.width()/10,menu.centerY());SystemClock.sleep(150);assertEquals("暂停隐藏面板",1,overlay[0].bounds().size());}
            inst.runOnMainSync(()->{overlay[0].update(true,null,"等待新局");overlay[0].togglePanel();overlay[0].update(true,null,"等待新局");});assertEquals("运行时手动隐藏不被刷新撤销",1,overlay[0].bounds().size());
            inst.runOnMainSync(()->{overlay[0].update(false,null,"已暂停");overlay[0].update(true,null,"等待新局");});assertEquals("重新启动恢复显示",2,overlay[0].bounds().size());
        }finally{inst.runOnMainSync(()->overlay[0].close());}
    }

    @Test public void roundPhaseControlsVisibilityWithoutLateResultPopup()throws Exception {
        var inst=InstrumentationRegistry.getInstrumentation();Context target=inst.getTargetContext();Assume.assumeTrue(Settings.canDrawOverlays(target));
        HandOverlay[] holder=new HandOverlay[1];
        inst.runOnMainSync(()->holder[0]=new HandOverlay(target,new HandOverlay.Actions(){public void toggle(){}public void settings(){}public void help(){}public void exit(){}}));
        try{inst.runOnMainSync(()->{
            HandOverlay o=holder[0];o.update(true,null,"等待",false);assertEquals(1,o.bounds().size());
            o.update(true,null,"摆牌",true);assertEquals(2,o.bounds().size());
            o.update(true,null,"比牌",false);o.update(true,null,"迟到结果",false);o.togglePanel();assertEquals(1,o.bounds().size());
            o.update(true,null,"同局重新摆牌",true);assertEquals(2,o.bounds().size());
        });}finally{inst.runOnMainSync(()->holder[0].close());}
    }
    @Test public void panelIsFixedUpperLeftAndStaysStableAcrossTransitions()throws Exception {
        var inst=InstrumentationRegistry.getInstrumentation();Context target=inst.getTargetContext();UiDevice device=UiDevice.getInstance(inst);Assume.assumeTrue(Settings.canDrawOverlays(target));
        target.startActivity(new Intent().setClassName("com.jpq.mobile.test","com.jpq.mobile.FixtureActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("fixture","shisanshui/yellow_k"));SystemClock.sleep(800);
        HandOverlay[] holder=new HandOverlay[1];
        inst.runOnMainSync(()->holder[0]=new HandOverlay(target,new HandOverlay.Actions(){public void toggle(){}public void settings(){}public void help(){}public void exit(){}}));
        try{
            Rect lower=new Rect(0,device.getDisplayHeight()/2,device.getDisplayWidth(),device.getDisplayHeight());
            inst.runOnMainSync(()->{holder[0].setRecognitionAreas(java.util.List.of(lower));holder[0].update(true,null,"摆牌",true);});
            Rect before=holder[0].bounds().get(1);
            assertEquals(2,before.left);assertEquals(2,before.top);
            device.swipe(before.centerX(),before.centerY(),before.centerX()+200,before.centerY()+40,25);SystemClock.sleep(200);
            assertEquals("结果面板不能拖动，滑动也不误触隐藏",before,holder[0].bounds().get(1));
            device.swipe(before.centerX(),before.centerY(),before.centerX(),lower.centerY(),25);SystemClock.sleep(200);
            assertEquals(2,holder[0].bounds().size());assertFalse("面板不能拖进识别区",Rect.intersects(lower,holder[0].bounds().get(1)));
            inst.runOnMainSync(()->{
                HandOverlay o=holder[0];
                o.togglePanel();for(int i=0;i<20;i++)o.update(true,null,"服务器刷新",true);assertEquals("手动隐藏不被刷新撤销",1,o.bounds().size());
                for(int i=0;i<30;i++){
                    o.update(true,null,"比牌",false);o.update(true,null,"迟到结果",false);assertEquals(1,o.bounds().size());
                    o.update(true,null,"重新摆牌",true);assertEquals(2,o.bounds().size());assertFalse(Rect.intersects(lower,o.bounds().get(1)));
                }
                Rect oldPosition=new Rect(o.bounds().get(1));o.setRecognitionAreas(java.util.List.of(oldPosition));assertEquals("固定位置冲突时隐藏，不迁移",1,o.bounds().size());
                o.setRecognitionAreas(java.util.List.of());o.update(true,null,"比牌",false);o.update(true,null,"开局",true);o.resize();assertEquals(oldPosition,o.bounds().get(1));
                o.setRecognitionAreas(java.util.List.of(new Rect(0,0,device.getDisplayWidth(),device.getDisplayHeight())));
                for(int i=0;i<20;i++){o.update(true,null,"无安全空白区",true);assertEquals(1,o.bounds().size());}
                o.close();o.close();assertTrue(o.bounds().isEmpty());
            });
        }finally{inst.runOnMainSync(()->holder[0].close());device.pressHome();}
    }
    void save(String name)throws Exception{var inst=InstrumentationRegistry.getInstrumentation();try(var out=new FileOutputStream(new File(inst.getTargetContext().getFilesDir(),name))){Bitmap b=inst.getUiAutomation().takeScreenshot();assertNotNull(b);b.compress(Bitmap.CompressFormat.PNG,100,out);b.recycle();}}
}
