package com.jpq.mobile;
import org.junit.Test;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;
public class ServerEndResetTest {
 final RoundSessionTest f=new RoundSessionTest();
 List<String> fresh(){List<String> h=f.hand();h.set(0,"hearts:A");return h;}
 RoundSession accepted()throws Exception {RoundSession s=f.session();f.start(s,0);JSONObject r=f.request(s);f.ack(s,r);s.receive(f.result(r));return s;}
 @Test public void advanceImmediatelyHidesAndRearmsWithoutLocalBoundary()throws Exception {
  RoundSession s=accepted();f.state(s,2);assertFalse(s.panel);assertNull(s.result);assertTrue(s.needsHands());assertTrue(s.outgoing().isEmpty());
  s.frame(1200,true,false,f.hand());s.frame(1400,true,false,f.hand());assertFalse(s.panel);assertTrue(s.outgoing().isEmpty());
  s.frame(1600,true,false,fresh());assertTrue(s.outgoing().isEmpty());s.frame(1800,true,false,fresh());assertTrue(s.panel);assertEquals(2,f.request(s).getInt("round_version"));
 }
 @Test public void oldPendingRequestAndLateReplyCannotBlockOrReopen()throws Exception {
  RoundSession s=f.session();f.start(s,0);JSONObject old=f.request(s);f.state(s,2);assertTrue(s.outgoing().isEmpty());f.ack(s,old);f.error(s,old,"SYNC_REQUIRED");s.receive(f.result(old));assertNull(s.result);assertFalse(s.panel);
  s.frame(1200,true,false,fresh());s.frame(1400,true,false,fresh());assertEquals(2,f.request(s).getInt("round_version"));
 }
 @Test public void gapRequiresNewConsecutiveEvidenceButNotBlankScreen()throws Exception {
  RoundSession s=accepted();f.state(s,2);s.frame(1200,true,false,fresh());s.gap();s.frame(1400,true,false,fresh());assertTrue(s.outgoing().isEmpty());s.frame(1600,true,false,fresh());assertEquals(1,s.outgoing().size());
 }
 @Test public void staleAndDuplicateStateCannotResetNewRound()throws Exception {
  RoundSession s=accepted();f.state(s,2);s.frame(1200,true,false,fresh());s.receive(f.wire("round.state",2,new JSONObject().put("enabled",true).put("state","waiting")));s.frame(1400,true,false,fresh());JSONObject r=f.request(s);f.ack(s,r);s.receive(f.result(r));
  s.receive(f.wire("round.state",1,new JSONObject().put("enabled",true).put("state","waiting")));assertNotNull(s.result);assertEquals(2,s.result.round);
 }
 @Test public void incompleteHandsNeverSubmitAfterServerReset()throws Exception {
  for(int n:new int[]{0,12,14}){RoundSession s=accepted();f.state(s,2);List<String> h=new ArrayList<>(fresh());if(n==14)h.add("hearts:K");else h=new ArrayList<>(h.subList(0,n));for(int i=0;i<8;i++)s.frame(1200+i*200,true,false,h);assertTrue(s.outgoing().isEmpty());}
 }
 @Test public void restartPreservesOldHandGuard()throws Exception {
  String[] saved={""};RoundSession s=new RoundSession("100001","test_device","",v->saved[0]=v);f.state(s,1);f.start(s,0);f.ack(s,f.request(s));
  RoundSession restored=new RoundSession("100001","test_device",saved[0],v->{});f.state(restored,2,1);restored.frame(1200,true,false,f.hand());restored.frame(1400,true,false,f.hand());assertTrue(restored.outgoing().isEmpty());restored.frame(1600,true,false,fresh());restored.frame(1800,true,false,fresh());assertEquals(2,f.request(restored).getInt("round_version"));
 }
}
