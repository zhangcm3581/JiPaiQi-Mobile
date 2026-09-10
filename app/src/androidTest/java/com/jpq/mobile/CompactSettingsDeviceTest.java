package com.jpq.mobile;

import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.accessibility.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CompactSettingsDeviceTest {
    @Test public void settingsStayCompactWithoutActivityTransitions() throws Exception {
        var inst=InstrumentationRegistry.getInstrumentation();var target=inst.getTargetContext();
        var device=UiDevice.getInstance(inst);var pref=target.getSharedPreferences("hand_upload",0);
        String tenant=pref.getString("tenant_id",null),client=pref.getString("client_id",null);
        pref.edit().remove("tenant_id").remove("client_id").commit();
        var helper=new CaptureFlowTest();AtomicInteger activities=new AtomicInteger();
        Application app=(Application)target.getApplicationContext();
        Application.ActivityLifecycleCallbacks observer=new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a,Bundle b){activities.incrementAndGet();}
            public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){}
            public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}
            public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
        };
        boolean watching=false;
        try {
            device.pressHome();helper.launchAndConsent(target,device);device.waitForIdle();
            app.registerActivityLifecycleCallbacks(observer);watching=true;
            float density=target.getResources().getDisplayMetrics().density;
            for(int i=0;i<5;i++){
                Rect menu=device.findObject(By.descStartsWith("悬浮控制栏")).getVisibleBounds();
                long start=SystemClock.elapsedRealtime();helper.openSettings(device);
                long elapsed=SystemClock.elapsedRealtime()-start;
                Rect settings=null;
                for(AccessibilityWindowInfo window:inst.getUiAutomation().getWindows()){
                    AccessibilityNodeInfo root=window.getRoot();
                    if(root!=null&&!root.findAccessibilityNodeInfosByText("保存").isEmpty()){
                        settings=new Rect();window.getBoundsInScreen(settings);break;
                    }
                }
                assertNotNull(settings);
                String measured="menu="+Math.round(menu.width()/density)+"x"+Math.round(menu.height()/density)
                        +"dp settings="+Math.round(settings.width()/density)+"x"+Math.round(settings.height()/density)
                        +"dp new_activities="+activities.get()+" open_ms="+elapsed;
                System.out.println(measured);
                assertTrue(measured,menu.width()<=density*250&&menu.height()<=density*52
                        &&settings.height()<=density*165&&activities.get()==0);
                Rect bar=device.findObject(By.descStartsWith("悬浮控制栏")).getVisibleBounds();
                Rect form=device.findObject(By.desc("悬浮栏设置区")).getVisibleBounds();
                assertEquals("展开设置不移动控制条",menu,bar);
                assertEquals("左右边缘对齐",bar.left,form.left);assertEquals(bar.right,form.right);
                assertTrue("设置和控制条无缝相接",form.bottom==bar.top||form.top==bar.bottom);
                assertEquals("设置由相邻窗口承载，不改变控制栏窗口尺寸",form,settings);
                if(i%3==0)device.findObject(By.text("取消")).click();
                else if(i%3==1)device.pressBack();
                else device.click(bar.left+bar.width()*3/10,bar.centerY());
                assertTrue(device.wait(Until.gone(By.text("保存")),2000));
                assertEquals(menu,device.findObject(By.descStartsWith("悬浮控制栏")).getVisibleBounds());
            }
            device.waitForIdle();
            Rect bar=device.findObject(By.descStartsWith("悬浮控制栏")).getVisibleBounds();
            System.out.println("before drag "+bar);
            device.swipe(bar.centerX(),bar.centerY(),bar.centerX(),Math.round(density*64),24);device.waitForIdle();
            // UiAutomator can retain the old bounds of a moved non-focusable overlay.
            // Use the same published screen rectangle that the capture pipeline uses.
            var overlayField=CaptureService.class.getDeclaredField("handOverlay");overlayField.setAccessible(true);
            HandOverlay overlay=(HandOverlay)overlayField.get(CaptureService.current);
            Rect moved=overlay.bounds().get(0);System.out.println("after drag "+moved);
            assertTrue("控制栏确已移动到顶部",moved.top<density*100);
            device.click(moved.left+moved.width()*3/10,moved.centerY());
            assertNotNull(device.wait(Until.findObject(By.text("保存")),5000));
            bar=device.findObject(By.descStartsWith("悬浮控制栏")).getVisibleBounds();
            Rect form=device.findObject(By.desc("悬浮栏设置区")).getVisibleBounds();
            assertEquals("靠近顶部时向下展开",bar.bottom,form.top);
            var fields=device.findObjects(By.clazz("android.widget.EditText"));
            fields.get(0).setText("765432");fields.get(1).setText("unsaved_device");
            device.swipe(bar.centerX(),bar.centerY(),bar.centerX()-Math.round(60*density),bar.centerY()+Math.round(40*density),24);
            device.waitForIdle();assertJoined(device);
            target.startActivity(new Intent().setClassName("com.jpq.mobile.test","com.jpq.mobile.FixtureActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("fixture","shisanshui/predeal"));
            helper.await("横屏布局完成",()->device.getDisplayWidth()>device.getDisplayHeight());device.waitForIdle();
            assertJoined(device);
            assertTrue(device.hasObject(By.text("765432")));assertTrue(device.hasObject(By.text("unsaved_device")));
            bar=rawBounds("悬浮控制栏");
            device.click(bar.right-bar.width()/10,bar.centerY());
            assertTrue(device.wait(Until.gone(By.text("保存")),2000));
            device.waitForIdle();
            Rect collapsed=overlay.bounds().get(0);
            assertTrue("收起连同设置区一起关闭",collapsed.width()<bar.width()/3);
            device.click(collapsed.centerX(),collapsed.centerY());device.waitForIdle();
            assertEquals(bar.width(),overlay.bounds().get(0).width());
            assertFalse("重新展开不弹出已取消的设置",device.hasObject(By.text("保存")));
            assertEquals("取消不保存编辑内容","",pref.getString("tenant_id",""));
        } catch(Throwable failure) {
            device.takeScreenshot(new java.io.File(target.getFilesDir(),"integrated-settings-failure.png"));
            throw failure;
        } finally {
            if(watching)app.unregisterActivityLifecycleCallbacks(observer);
            UiObject2 cancel=device.findObject(By.text("取消"));if(cancel!=null)cancel.click();
            target.stopService(new Intent(target,CaptureService.class));helper.await("服务释放",()->CaptureService.current==null);
            pref.edit().putString("tenant_id",tenant).putString("client_id",client).commit();device.pressHome();
        }
    }
    private Rect rawBounds(String prefix){
        // UiObject2.getVisibleBounds intersects moved overlays with stale window-cache bounds.
        // Nodes themselves publish the current physical position; don't clip it a second time.
        for(AccessibilityWindowInfo window:InstrumentationRegistry.getInstrumentation().getUiAutomation().getWindows()){
            AccessibilityNodeInfo root=window.getRoot();if(root==null)continue;
            java.util.ArrayDeque<AccessibilityNodeInfo> nodes=new java.util.ArrayDeque<>();nodes.add(root);
            while(!nodes.isEmpty()){
                AccessibilityNodeInfo node=nodes.remove();CharSequence desc=node.getContentDescription();
                if(desc!=null&&desc.toString().startsWith(prefix)){Rect r=new Rect();node.getBoundsInScreen(r);return r;}
                for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null)nodes.add(child);}
            }
        }
        throw new AssertionError("未找到 "+prefix);
    }
    private void assertJoined(UiDevice device){
        Rect bar=rawBounds("悬浮控制栏"),form=rawBounds("悬浮栏设置区");
        assertEquals(bar.left,form.left);assertEquals(bar.right,form.right);
        assertTrue("bar="+bar+" form="+form,form.bottom==bar.top||form.top==bar.bottom);
        assertTrue(form.top>=0&&form.bottom<=device.getDisplayHeight());
    }
}
