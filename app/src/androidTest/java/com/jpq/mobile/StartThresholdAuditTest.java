package com.jpq.mobile;
import android.graphics.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.*;
import java.util.*;
import java.io.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class StartThresholdAuditTest {
 @Test public void compareOnlyStartThreshold()throws Exception {
  var fixtures=new ShisanshuiDeviceTest();JSONArray rows=new JSONArray();
  List<String> names=List.of("golden_round_1","golden_round_2","golden_round_3","golden_round_4","golden_round_5","golden_round_6","golden_round_7","golden_round_8","initial","yellow_k","arranging","confirm_ready","missing_12","extra_14","predeal","comparing","end_hall","end_right");
  Set<String> negative=Set.of("predeal","comparing","end_hall","end_right");
  for(double threshold:new double[]{.90,.85,.80}) {
   Pack pack=fixtures.pack();JSONArray states=pack.json.getJSONArray("states");
   for(int i=0;i<states.length();i++){JSONObject state=states.getJSONObject(i);if(!state.getString("purpose").equals("new_round_candidate"))continue;JSONArray ids=state.getJSONArray("detector_ids");for(int j=0;j<ids.length();j++)pack.detectors.get(ids.getString(j)).getJSONObject("params").put("threshold",threshold);}
   int misses=0,falseStarts=0,total=0;
   try(Vision vision=new Vision(pack)) {
    for(String name:names)for(int width:new int[]{840,1600})for(boolean jpeg:new boolean[]{false,true}) {
     Bitmap original=fixtures.image(name),scaled=Bitmap.createScaledBitmap(original,width,width==840?474:900,true),image=scaled;
     try {
      if(jpeg){ByteArrayOutputStream bytes=new ByteArrayOutputStream();scaled.compress(Bitmap.CompressFormat.JPEG,60,bytes);byte[] raw=bytes.toByteArray();image=BitmapFactory.decodeByteArray(raw,0,raw.length);}
      Vision.Frame frame=vision.analyze(image,new double[]{0,0,1,1},false);boolean expected=!negative.contains(name),actual=frame.start&&!frame.end;
      if(expected&&!actual)misses++;if(!expected&&actual)falseStarts++;total++;
      rows.put(new JSONObject().put("threshold",threshold).put("fixture",name).put("width",width).put("jpeg60",jpeg).put("expected_start",expected).put("raw_start",frame.start).put("end",frame.end).put("effective_start",actual));
     }finally{if(image!=scaled)image.recycle();if(scaled!=original)scaled.recycle();original.recycle();}
    }
   }
   System.out.println("THRESHOLD="+threshold+" samples="+total+" missed="+misses+" false_start="+falseStarts);
  }
  java.nio.file.Files.write(new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(),"start-threshold-audit.json").toPath(),rows.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  assertEquals(216,rows.length());
 }
}
