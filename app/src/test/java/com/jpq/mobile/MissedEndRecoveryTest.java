package com.jpq.mobile;
import org.junit.Test;
import org.json.JSONObject;
import java.util.*;
import static org.junit.Assert.*;

public class MissedEndRecoveryTest {
 final RoundSessionTest f=new RoundSessionTest();
 RoundSession accepted()throws Exception {RoundSession s=f.session();f.start(s,0);f.ack(s,f.request(s));return s;}
 List<String> fresh(int n){List<String> h=f.hand();h.set(0,"hearts:"+HandSnapshots.RANKS.get(n));return h;}
 @Test public void missedEndRecoversForTwoFollowingGames()throws Exception {
  RoundSession s=accepted();
  for(int v=2;v<=3;v++){f.state(s,v);f.start(s,v*2000,fresh(v));assertEquals("one fresh submission for round "+v,1,s.outgoing().size());JSONObject r=f.request(s);assertEquals(v,r.getInt("round_version"));assertEquals("hand.submit",r.getString("type"));assertEquals(13,r.getJSONObject("payload").getJSONArray("cards").length());f.ack(s,r);}
 }
 @Test public void sameOldCardsAndContinuousStartNeverRelabel()throws Exception {
  RoundSession s=accepted();f.state(s,2);for(int t=1200;t<=2000;t+=200)s.frame(t,true,false,f.hand());assertTrue(s.outgoing().isEmpty());f.start(s,2200,f.hand());assertEquals("a confirmed local transition permits an identical next deal",2,f.request(s).getInt("round_version"));
 }
 @Test public void captureGapOrDisconnectCannotCountAsBlankEvidence()throws Exception {
  for(boolean disconnect:new boolean[]{false,true}){RoundSession s=accepted();f.state(s,2);s.frame(1200,false,false,List.of());if(disconnect){s.connection(false);f.state(s,2);}else s.gap();s.frame(1800,true,false,f.hand());s.frame(2000,true,false,f.hand());assertTrue(s.outgoing().isEmpty());}
 }
 @Test public void versionChangeBetweenFreshFramesCannotRelabel()throws Exception {
  RoundSession s=accepted();f.state(s,2);s.frame(1200,false,false,List.of());s.frame(1800,false,false,List.of());s.frame(2000,true,false,fresh(2));f.state(s,3);s.frame(2200,true,false,fresh(2));s.frame(2400,true,false,fresh(2));assertTrue(s.outgoing().isEmpty());
 }
 @Test public void recoveryNeedsTwoEqualCompleteHands()throws Exception {
  RoundSession s=accepted();f.state(s,2);f.start(s,1200,List.of());assertTrue(s.needsHands());s.frame(2400,true,false,fresh(2));s.frame(2600,true,false,fresh(3));assertTrue(s.outgoing().isEmpty());s.frame(2800,true,false,fresh(3).subList(0,12));s.frame(3000,true,false,fresh(3));assertTrue(s.outgoing().isEmpty());s.frame(3200,true,false,fresh(3));assertEquals(1,s.outgoing().size());
 }
}
