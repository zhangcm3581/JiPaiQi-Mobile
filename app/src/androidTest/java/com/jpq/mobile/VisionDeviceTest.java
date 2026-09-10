package com.jpq.mobile;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import android.graphics.*;
import java.util.*;
import java.io.*;

@RunWith(AndroidJUnit4.class)
public class VisionDeviceTest {
    @Test public void testImportedPackageAndActualVideoFrames()throws Exception{
        Pack pack=legacyPack();assertEquals(3,pack.hands.size());
        try(Vision vision=new Vision(pack)){
            verify(vision,"overlap",Map.of("opponent_1","3 3","opponent_2","","me","Q Q"));
            verify(vision,"three_aces",Map.of("opponent_1","","opponent_2","A A A","me","Q Q"));
            verify(vision,"two_rows",Map.of("opponent_1","","opponent_2","Q J 10 9 8 7 6 5 4 3","me",""));
            verify(vision,"simultaneous",Map.of("opponent_1","","opponent_2","Q Q Q 5 5","me","J J J 5 3"));
            Bitmap image=load("settlement");try{assertTrue("结算应命中",vision.analyze(image,new double[]{0,0,1,1}).end);}finally{image.recycle();}
            Bitmap original=load("two_rows"),scaled=Bitmap.createScaledBitmap(original,1280,722,true);try{Vision.Frame f=vision.analyze(scaled,new double[]{0,0,1,1});assertEquals("Q J 10 9 8 7 6 5 4 3",String.join(" ",f.cards.stream().filter(r->r.seat.equals("opponent_2")).findFirst().get().ranks()));}finally{original.recycle();scaled.recycle();}
        }
    }
    @Test public void settlementAtScreenSize()throws Exception{
        Pack p=legacyPack();try(Vision v=new Vision(p)){Bitmap b=load("settlement"),scaled=Bitmap.createScaledBitmap(b,1600,900,true);try{assertTrue("1600x900 settlement",v.analyze(scaled,new double[]{0,0,1,1}).end);}finally{b.recycle();scaled.recycle();}}
    }
    private Pack legacyPack()throws Exception {
        var inst=InstrumentationRegistry.getInstrumentation();File dir=new File(inst.getTargetContext().getCacheDir(),"legacy-vision");Pack.delete(dir);dir.mkdirs();
        try(var zip=new java.util.zip.ZipInputStream(inst.getContext().getAssets().open("legacy-pack.zip"))){java.util.zip.ZipEntry e;byte[] b=new byte[8192];while((e=zip.getNextEntry())!=null){try(var out=new FileOutputStream(new File(dir,e.getName()))){int n;while((n=zip.read(b))!=-1)out.write(b,0,n);}}}return new Pack(dir);
    }
    private Bitmap load(String name)throws Exception{try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name+".png")){return BitmapFactory.decodeStream(in);}}
    private void verify(Vision v,String name,Map<String,String> expected)throws Exception{Bitmap b=load(name);try{Vision.Frame f=v.analyze(b,new double[]{0,0,1,1});for(Vision.Reading r:f.cards)assertEquals(name+" "+r.seat,expected.get(r.seat),String.join(" ",r.ranks()));}finally{b.recycle();}}
}
