package com.jpq.mobile;

import android.content.Context;
import android.os.*;
import android.view.View;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import okhttp3.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Own-hand fixture replay over real TCP to an isolated local server. Never sends game requests. */
@RunWith(AndroidJUnit4.class)
public class ProtocolReplayDeviceTest {
 private Handler worker;
 private <T>T run(Callable<T> operation)throws Exception{FutureTask<T> task=new FutureTask<>(operation);worker.post(task);return task.get(10,TimeUnit.SECONDS);}
 private void waitFor(Callable<Boolean> condition)throws Exception{long until=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<until){if(run(condition))return;Thread.sleep(25);}fail("Timed out waiting for protocol state");}
 private JSONObject observation(String kind,int round,JSONArray cards)throws Exception{return new JSONObject().put("event",kind).put("game_key","932874:"+round).put("cards",cards);}
 private void visible(HandOverlay overlay,ProtocolSession session,boolean expected)throws Exception{
  HandReply result=run(()->session.result);boolean panel=run(()->session.panel);
  InstrumentationRegistry.getInstrumentation().runOnMainSync(()->overlay.update(true,result,"协议回放测试",panel));
  java.lang.reflect.Field field=HandOverlay.class.getDeclaredField("grid");field.setAccessible(true);View grid=(View)field.get(overlay);
  assertEquals(expected?View.VISIBLE:View.GONE,grid.getVisibility());
 }
 @Test public void eightRoundsActualSocketsAndOverlay()throws Exception{ replay(false); }
 @Test public void fiveRealRoundsPanelTiming()throws Exception{ replay(true); }
 private void replay(boolean timing)throws Exception{
  assertEquals("http://127.0.0.1:18080",BuildConfig.SERVER_URL);
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
  JSONArray replay=new JSONArray(new String(RootProtocolCollector.readAll(InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(timing?"protocol-panel-timing.json":"protocol-own-replay.json")),java.nio.charset.StandardCharsets.UTF_8));
  List<JSONArray> hands=new ArrayList<>();for(int i=0;i<replay.length();i++)if(replay.getJSONObject(i).getInt("cmd")==21)hands.add(replay.getJSONObject(i).getJSONObject("data").getJSONArray("hand_cards"));
  String tenant="9"+System.currentTimeMillis();OkHttpClient http=new OkHttpClient();
  try(Response response=http.newCall(new Request.Builder().url(BuildConfig.SERVER_URL+"/api/tenants").post(RequestBody.create(new JSONObject().put("tenant_id",tenant).put("note","isolated Android replay").toString(),MediaType.get("application/json"))).build()).execute()){assertEquals(201,response.code());}
  HandlerThread thread=new HandlerThread("protocol-replay-test");thread.start();worker=new Handler(thread.getLooper());
  ProtocolSession[] sessions=new ProtocolSession[7];RoundSocket[] sockets=new RoundSocket[7];AtomicBoolean allowed=new AtomicBoolean(true);
  HandOverlay[] overlay={null};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->overlay[0]=new HandOverlay(context,new HandOverlay.Actions(){public void toggle(){}public void settings(){}public void help(){}public void exit(){}}));
  List<Long> renderMillis=new ArrayList<>();
  try{
   run(()->{for(int i=0;i<7;i++){sessions[i]=new ProtocolSession(tenant,"peer"+i,"",s->{});sockets[i]=new RoundSocket(worker,sessions[i],BuildConfig.SERVER_URL,()->{},allowed::get);}return null;});
   // Wait for server registration snapshots, rather than a fixed startup delay.
   waitFor(()->{for(ProtocolSession s:sessions){java.lang.reflect.Field f=ProtocolSession.class.getDeclaredField("synced");f.setAccessible(true);if(!f.getBoolean(s))return false;}return true;});
   for(int round=1;round<=hands.size();round++){
    final int r=round;JSONArray own=hands.get(round-1);List<Integer> deck=new ArrayList<>();for(int suit=1;suit<=4;suit++)for(int rank=2;rank<=14;rank++){deck.add(suit*16+rank);deck.add(suit*16+rank);}
    for(int i=0;i<own.length();i++)assertTrue(deck.remove(Integer.valueOf(own.getInt(i))));Collections.shuffle(deck,new Random(round));
    boolean full=timing||round%2==1;long start=SystemClock.elapsedRealtime();
    run(()->{sessions[0].game(observation("deal",r,own));sockets[0].flush();if(full)for(int i=1;i<7;i++){sessions[i].game(observation("deal",r,new JSONArray(deck.subList((i-1)*13,i*13))));sockets[i].flush();}return null;});
    waitFor(()->sessions[0].outgoing().isEmpty());
    if(full){waitFor(()->{for(ProtocolSession s:sessions)if(!s.panel)return false;return true;});visible(overlay[0],sessions[0],true);renderMillis.add(SystemClock.elapsedRealtime()-start);
      HandReply result=run(()->sessions[0].result);int[][] expected=new int[4][13];for(int value:deck.subList(78,91)){int rank=value&15;int column=rank==14?0:rank-1;expected[(value>>4)-1][column]++;}
      for(int suit=0;suit<4;suit++)for(int col=0;col<13;col++)for(int copy=0;copy<2;copy++)assertEquals(expected[suit][col]>copy,result.occupied(suit*2+copy,col));
    }else visible(overlay[0],sessions[0],false);
    if(timing){long until=start+replay.getJSONObject(round-1).getLong("hold_ms");while(SystemClock.elapsedRealtime()<until){visible(overlay[0],sessions[0],true);Thread.sleep(100);}android.util.Log.i("JPQ_PANEL_TIMING","round="+round+" visible-through-arranging-ms="+(SystemClock.elapsedRealtime()-start));}
    int reporter=timing?0:(full?6:0);
    run(()->{sessions[reporter].game(observation("compare",r,own));sockets[reporter].flush();return null;});
    waitFor(()->{for(ProtocolSession s:sessions)if(s.panel)return false;return sessions[reporter].outgoing().isEmpty();});visible(overlay[0],sessions[0],false);
    run(()->{sessions[reporter].game(observation("end",r,own));sessions[reporter].game(observation("settled",r,own));sockets[reporter].flush();return null;});
    waitFor(()->{for(ProtocolSession s:sessions){java.lang.reflect.Field f=ProtocolSession.class.getDeclaredField("known");f.setAccessible(true);if(f.getInt(s)!=r+1)return false;}return true;});
    visible(overlay[0],sessions[0],false);
   }
   run(()->{allowed.set(false);for(RoundSocket socket:sockets)socket.flush();return null;});
   waitFor(()->{for(ProtocolSession s:sessions)if(!s.outgoing().isEmpty()||s.panel)return false;return true;});
   android.util.Log.i("JPQ_REPLAY_RESULT","rounds="+hands.size()+" timing="+timing+" results/correctness/compare/end/settled/pause; replay-to-render-ms="+renderMillis);
  }finally{run(()->{for(RoundSocket socket:sockets)if(socket!=null)socket.close();return null;});InstrumentationRegistry.getInstrumentation().runOnMainSync(()->overlay[0].close());thread.quitSafely();http.dispatcher().executorService().shutdown();http.connectionPool().evictAll();}
 }
}
