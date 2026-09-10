package com.jpq.mobile;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import android.graphics.*;
import java.util.*;
import java.io.*;
import java.util.zip.*;

@RunWith(AndroidJUnit4.class)
public class ShisanshuiDeviceTest {
    Pack pack()throws Exception {return pack("shisanshui/pack.zip");}
    Pack pack(String archive)throws Exception {
        var c=InstrumentationRegistry.getInstrumentation().getContext();File dir=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"shisanshui-test");Pack.delete(dir);dir.mkdirs();
        try(ZipInputStream z=new ZipInputStream(c.getAssets().open(archive))){ZipEntry e;byte[] b=new byte[8192];while((e=z.getNextEntry())!=null){try(var o=new FileOutputStream(new File(dir,e.getName()))){int n;while((n=z.read(b))!=-1)o.write(b,0,n);}}}return new Pack(dir);
    }
    Bitmap image(String name)throws Exception{try(var in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("shisanshui/"+name+".png")){return BitmapFactory.decodeStream(in);}}
    @Test public void sameHandAtSourceAndEmulatorSizeAndEndRegions()throws Exception {
        Pack p=pack();assertEquals(200,p.timing.getInt("sample_interval_ms"));
        try(Vision v=new Vision(p)){
            for(String name:List.of("yellow_k","initial")){
                Bitmap b=image(name);try{for(int width:List.of(840,1600)){
                    Bitmap scaled=Bitmap.createScaledBitmap(b,width,width==840?474:900,true);
                    try{Vision.Frame f=v.analyze(scaled,new double[]{0,0,1,1});assertEquals(name+width,13,f.hands.get(0).hits.size());assertTrue(f.start);assertFalse(f.end);
                        if(name.equals("yellow_k")){Vision.Hit k=f.hands.get(0).hits.stream().filter(h->h.rank.equals("hearts:K")).findFirst().get();assertTrue(k.score>.9);}
                    }finally{if(scaled!=b)scaled.recycle();}
                }}finally{b.recycle();}
            }
            for(String name:List.of("arranging","confirm_ready","comparing")){Bitmap b=image(name);try{Vision.Frame f=v.analyze(b,new double[]{0,0,1,1});assertEquals(name,!name.equals("comparing"),f.start);assertFalse(name,f.end);if(name.equals("confirm_ready"))assertTrue(f.hands.isEmpty()||f.hands.get(0).hits.isEmpty());}finally{b.recycle();}}
            for(String name:List.of("end_right","end_hall")){Bitmap b=image(name);try{Vision.Frame f=v.analyze(b,new double[]{0,0,1,1},false);assertTrue(name,f.end);assertTrue(f.hands.isEmpty());}finally{b.recycle();}}
        }
    }
    @Test public void installBundledPackAndCancelCaptureConsent()throws Exception {
        var inst=InstrumentationRegistry.getInstrumentation();var target=inst.getTargetContext();
        try(var in=target.getAssets().open("default-pack.zip")){Pack installed=Pack.install(target,in);assertEquals("十三水·白黄底手牌",installed.name);}
        target.startActivity(new android.content.Intent(target,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        var device=androidx.test.uiautomator.UiDevice.getInstance(inst);
        var consent=device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("立即开始")),5000);
        if(consent==null)consent=device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("Start now")),2000);
        assertNotNull("没有主面板，直接请求系统授权",consent);
        assertFalse(device.hasObject(androidx.test.uiautomator.By.text("开启悬浮控制")));
        var cancel=device.findObject(androidx.test.uiautomator.By.text("取消"));
        if(cancel==null)cancel=device.findObject(androidx.test.uiautomator.By.text("Cancel"));
        assertNotNull(cancel);cancel.click();device.waitForIdle();
        assertTrue(device.wait(androidx.test.uiautomator.Until.gone(androidx.test.uiautomator.By.text("立即开始")),3000));
        assertNull("取消授权不留下截图服务",CaptureService.current);
        assertFalse(device.hasObject(androidx.test.uiautomator.By.text("十三水助手")));
    }

}
