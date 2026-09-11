package com.jpq.mobile;
import android.graphics.Bitmap;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class ThresholdSpeedTest {
 @Test public void compareThresholdsOnKnownHands()throws Exception {
  var fixtures=new ShisanshuiDeviceTest();List<String> failures=new ArrayList<>();
  for(double threshold:List.of(.85,.80)){
   Pack p=fixtures.pack();for(var d:p.detectors.values()){var r=p.resources.get(d.getString("resource_id"));if("hand".equals(r.getJSONObject("semantic").optString("role")))d.getJSONObject("params").put("threshold",threshold);}
   try(Vision v=new Vision(p)){
    for(int round=1;round<=8;round++){Bitmap original=fixtures.image("golden_round_"+round);try{for(int width:List.of(840,1600)){Bitmap image=Bitmap.createScaledBitmap(original,width,width==840?474:900,true);try{long t=System.nanoTime();var found=FullVideoAuditTest.actual(v.analyzeCapture(image,new double[]{0,0,1,1},true));double ms=(System.nanoTime()-t)/1e6;System.out.println("THRESHOLD "+threshold+" round="+round+" width="+width+" ms="+ms+" correct="+found.equals(FullVideoAuditTest.expected(round)));if(!found.equals(FullVideoAuditTest.expected(round)))failures.add("threshold="+threshold+" round="+round+" width="+width+" got="+found);}finally{if(image!=original)image.recycle();}}}finally{original.recycle();}}
    for(String name:List.of("missing_12","extra_14")){Bitmap image=fixtures.image(name);try{int count=FullVideoAuditTest.actual(v.analyzeCapture(image,new double[]{0,0,1,1},true)).size();if(count!=(name.equals("missing_12")?12:14))failures.add(threshold+" "+name+" count="+count);}finally{image.recycle();}}
   }
  }
  assertTrue(String.join("\n",failures),failures.isEmpty());
 }
}
