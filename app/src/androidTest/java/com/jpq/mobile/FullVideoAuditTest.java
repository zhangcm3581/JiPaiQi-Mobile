package com.jpq.mobile;
import android.graphics.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import static org.junit.Assert.*;
/** Independent manual oracle: every rank, suit and duplicate from eight real deals. */
@RunWith(AndroidJUnit4.class)
public class FullVideoAuditTest {
    static final String[] HANDS = {"cA dA cQ d10 s8 s8 h8 c8 s7 c5 s4 c3 s2","cA hQ d10 s8 c8 h7 h7 h5 c5 c4 c3 s2 d2","sA cA s10 h9 c9 h8 s7 c7 d6 s3 s3 h2 d2","sA sQ hJ hJ c9 h7 c7 c7 s6 h5 s4 h4 d2","sK hK hQ hJ dJ s10 s10 d9 s8 c5 s4 s4 s2","hK dJ c9 d9 h8 d8 s6 d6 s3 h3 d3 s2 c2","hA sK dK dQ d8 s7 h7 h7 s6 s5 c4 c3 h2","hA s9 d9 d9 s8 d8 c7 s6 h6 s4 d4 h3 h2"};
    static List<String> expected(int round){List<String> out=new ArrayList<>();for(String id:HANDS[round-1].split(" "))out.add(Map.of('s',"spades",'h',"hearts",'c',"clubs",'d',"diamonds").get(id.charAt(0))+":"+id.substring(1));Collections.sort(out);return out;}
    static List<String> actual(Vision.Frame frame){List<String> out=frame.hands.isEmpty()?new ArrayList<>():new ArrayList<>(frame.hands.get(0).ranks());Collections.sort(out);return out;}
    @Test public void eightDealsMatchEveryCardAtBothResolutions()throws Exception {
        var fixtures=new ShisanshuiDeviceTest();Pack pack=fixtures.pack();List<String> failures=new ArrayList<>();
        try(Vision vision=new Vision(pack)){
            for(int round=1;round<=8;round++){Bitmap original=fixtures.image("golden_round_"+round);try{for(int width:List.of(840,1600)){
                Bitmap bitmap=Bitmap.createScaledBitmap(original,width,width==840?474:900,true);
                try{long before=System.nanoTime();Vision.Frame frame=vision.analyze(bitmap,new double[]{0,0,1,1});List<String> found=actual(frame);String tag="round="+round+" width="+width;
                    System.out.println(tag+" actual="+found+" ms="+(System.nanoTime()-before)/1_000_000.0);
                    if(!expected(round).equals(found))failures.add(tag+" expected="+expected(round)+" actual="+found);
                    if(!frame.start||frame.end)failures.add(tag+" wrong phase");
                }finally{if(bitmap!=original)bitmap.recycle();}
            }}finally{original.recycle();}}
        }assertTrue(String.join("\n",failures),failures.isEmpty());
    }

    @Test public void existingWhiteYellowPackGainsIdentityVerificationWithoutReinstall()throws Exception {
        var fixtures=new ShisanshuiDeviceTest();Pack existing=fixtures.pack("shisanshui/pack-1.1.zip");
        for(var detector:existing.detectors.values())assertFalse(detector.getJSONObject("params").has("identity_scoring"));
        try(Vision vision=new Vision(existing)){
            for(int round:List.of(4,6,7)){Bitmap original=fixtures.image("golden_round_"+round);
                try{for(int width:List.of(840,1600)){Bitmap image=Bitmap.createScaledBitmap(original,width,width==840?474:900,true);
                    try{assertEquals("old pack round="+round+" width="+width,expected(round),actual(vision.analyze(image,new double[]{0,0,1,1})));}
                    finally{if(image!=original)image.recycle();}
                }}finally{original.recycle();}
            }
        }
    }

    @Test public void bundledUpgradePreservesCustomPacks()throws Exception {
        var inst=InstrumentationRegistry.getInstrumentation();var target=inst.getTargetContext();
        try(var in=inst.getContext().getAssets().open("shisanshui/pack-1.1.zip")){Pack.install(target,in);}
        Pack upgraded=Pack.current(target);assertEquals("1.2.0",upgraded.json.getJSONObject("package").getString("version"));assertEquals(57,upgraded.resources.size());
        try(var in=inst.getContext().getAssets().open("shisanshui/pack-1.1.zip")){Pack.install(target,in);}
        java.io.File manifest=new java.io.File(target.getFilesDir(),"pack/manifest.json");var edited=new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(manifest.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        edited.getJSONArray("regions").getJSONObject(0).put("name","保留用户自定义区域");String saved=edited.toString();java.nio.file.Files.write(manifest.toPath(),saved.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("1.1.0",Pack.current(target).json.getJSONObject("package").getString("version"));assertEquals(saved,new String(java.nio.file.Files.readAllBytes(manifest.toPath()),java.nio.charset.StandardCharsets.UTF_8));
    }

    @androidx.test.filters.LargeTest
    @Test public void completeVideoThroughNativeRecognitionAndRealWebSocket()throws Exception {
        var inst=InstrumentationRegistry.getInstrumentation();var context=inst.getTargetContext();
        java.io.File folder=new java.io.File(context.getFilesDir(),"audit/video-frames");
        assertTrue("Full-video corpus must be extracted into app-private files/audit/video-frames",new java.io.File(folder,"frames.csv").isFile());
        List<String> rows=java.nio.file.Files.readAllLines(new java.io.File(folder,"frames.csv").toPath());
        assertEquals(2202,rows.size());
        for(int width:List.of(840,1600))replayVideo(folder,rows,width);
    }
    void replayVideo(java.io.File folder,List<String> rows,int width)throws Exception {
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Never contact production during video replay","http://127.0.0.1:18081",BuildConfig.SERVER_URL);
        String tenant="4"+System.currentTimeMillis();okhttp3.OkHttpClient http=new okhttp3.OkHttpClient();
        try(var response=http.newCall(new okhttp3.Request.Builder().url(BuildConfig.SERVER_URL+"/api/tenants").post(okhttp3.RequestBody.create(new org.json.JSONObject().put("tenant_id",tenant).toString(),okhttp3.MediaType.get("application/json"))).build()).execute()){assertEquals(201,response.code());}
        android.os.HandlerThread thread=new android.os.HandlerThread("full-video-audit");thread.start();android.os.Handler worker=new android.os.Handler(thread.getLooper());
        RoundSocketDeviceTest helper=new RoundSocketDeviceTest();RoundSession[] session={null};RoundSocket[] socket={null};
        org.json.JSONArray observations=new org.json.JSONArray(),serverRounds=new org.json.JSONArray();List<Double> timings=new ArrayList<>();
        try(Vision vision=new Vision(new ShisanshuiDeviceTest().pack())){
            helper.on(worker,()->{session[0]=new RoundSession(tenant,"video_"+width,"",v->{});socket[0]=new RoundSocket(worker,session[0],BuildConfig.SERVER_URL);});
            helper.await("video connection",()->session[0].status.startsWith("连接成功"));
            for(int i=0;i<rows.size();i++){
                String[] row=rows.get(i).split(",");long time=Long.parseLong(row[1]);Bitmap original=BitmapFactory.decodeFile(new java.io.File(folder,row[2]).toString());assertNotNull(row[2],original);
                Bitmap image=width==840?original:Bitmap.createScaledBitmap(original,1600,900,true);
                try{boolean[] needs={true};helper.on(worker,()->needs[0]=session[0].needsHands());long begin=System.nanoTime();Vision.Frame frame=vision.analyzeCapture(image,new double[]{0,0,1,1},needs[0]);double elapsed=(System.nanoTime()-begin)/1_000_000.0;timings.add(elapsed);
                    List<String> cards=actual(frame);helper.on(worker,()->{session[0].frame(time,frame.start,frame.end,cards);socket[0].flush();});
                    org.json.JSONObject observation=new org.json.JSONObject().put("frame",Integer.parseInt(row[0])).put("time_ms",time).put("start",frame.start).put("end",frame.end).put("count",cards.size()).put("cards",new org.json.JSONArray(cards)).put("panel",session[0].panel).put("round",session[0].round).put("status",session[0].status).put("vision_ms",elapsed);
                    observations.put(observation);
                    if(i%200==0||elapsed>500)System.out.println("video width="+width+" sample="+i+" ms="+elapsed+" status="+session[0].status);
                }finally{if(image!=original)image.recycle();original.recycle();}
            }
            Thread.sleep(500);List<String> failures=new ArrayList<>();
            for(int round=1;round<=8;round++){
                try(var response=http.newCall(new okhttp3.Request.Builder().url(BuildConfig.SERVER_URL+"/api/tenants/"+tenant+"/rounds/"+round).build()).execute()){
                    org.json.JSONObject detail=new org.json.JSONObject(response.body().string());serverRounds.put(detail);
                    if(detail.optInt("received_count")!=1){failures.add("round "+round+" received="+detail.optInt("received_count")+" "+detail);continue;}
                    org.json.JSONArray cards=detail.getJSONArray("devices").getJSONObject(0).getJSONArray("cards");List<String> found=new ArrayList<>();for(int c=0;c<cards.length();c++){var card=cards.getJSONObject(c);found.add(Map.of("s","spades","h","hearts","c","clubs","d","diamonds").get(card.getString("suit"))+":"+card.getString("rank"));}Collections.sort(found);
                    if(!expected(round).equals(found))failures.add("round "+round+" expected="+expected(round)+" received="+found);
                }
            }
            double[][] arranging={{6.8,24.8},{59,72.8},{106.4,116.8},{150,164.8},{202.2,207.4},{256,265.8},{323,336.6},{379,390.6}};
            for(int i=0;i<observations.length();i++){var sample=observations.getJSONObject(i);double seconds=sample.getLong("time_ms")/1000.0;
                for(double[] interval:arranging)if(seconds>=interval[0]&&seconds<=interval[1]&&!sample.getBoolean("panel"))failures.add("panel hidden during arranging at "+seconds+" seconds");
            }
            org.json.JSONObject report=new org.json.JSONObject().put("width",width).put("tenant",tenant).put("samples",observations).put("server_rounds",serverRounds).put("failures",new org.json.JSONArray(failures));
            java.nio.file.Files.write(new java.io.File(context.getFilesDir(),"audit-video-"+width+".json").toPath(),report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Collections.sort(timings);System.out.println("full video "+width+" p50="+timings.get(timings.size()/2)+" p95="+timings.get((int)(timings.size()*.95))+" max="+timings.get(timings.size()-1));
            assertTrue(String.join("\n",failures),failures.isEmpty());
        }finally{helper.on(worker,()->{if(socket[0]!=null)socket[0].close();});thread.quitSafely();http.dispatcher().executorService().shutdown();http.connectionPool().evictAll();}
    }
}
