package com.jpq.mobile;
import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class RoundSessionTest {
    List<String> hand(){List<String> h=new ArrayList<>();for(String rank:HandSnapshots.RANKS)h.add("spades:"+rank);return h;}
    JSONObject wire(String type,int version,JSONObject payload)throws Exception{return new JSONObject().put("protocol_version",1).put("type",type).put("tenant_id","100001").put("round_version",version).put("payload",payload);}
    void state(RoundSession s,int n)throws Exception{s.connection(true);s.receive(wire("round.state",n,new JSONObject().put("enabled",true).put("state","waiting")));}
    RoundSession session()throws Exception{RoundSession s=new RoundSession("100001","test_device","",v->{});state(s,1);return s;}
    void start(RoundSession s,long t)throws Exception{s.frame(t,false,false,List.of());s.frame(t+600,false,false,List.of());s.frame(t+800,true,false,hand());s.frame(t+1000,true,false,hand());}
    JSONObject request(RoundSession s)throws Exception{return new JSONObject(s.outgoing().get(0));}
    void ack(RoundSession s,JSONObject req)throws Exception{JSONObject p=new JSONObject().put("action",req.getString("type"));if(req.getString("type").equals("round.join"))p.put("bound_round_version",req.getInt("round_version"));s.receive(wire("ack",req.getInt("round_version"),p).put("reply_to",req.getString("request_id")));}
    @Test public void joinAckRequiredAndRetriesAreIdentical()throws Exception{
        RoundSession s=session();start(s,0);String first=s.outgoing().get(0);assertEquals("round.join",request(s).getString("type"));
        s.frame(1200,true,false,hand());assertEquals(List.of(first),s.outgoing());s.connection(false);assertTrue(s.outgoing().isEmpty());state(s,1);assertEquals(first,s.outgoing().get(0));
        ack(s,request(s));assertEquals("hand.submit",request(s).getString("type"));assertEquals(13,request(s).getJSONObject("payload").getJSONArray("cards").length());
        ack(s,request(s));s.frame(1400,true,false,hand());assertTrue(s.outgoing().isEmpty());assertFalse(s.needsHands());
    }
    @Test public void halfRoundStartupShowsWaitingButNeverJoins()throws Exception{RoundSession s=session();for(int i=0;i<20;i++)s.frame(i*200,true,false,hand());assertTrue(s.outgoing().isEmpty());assertTrue(s.panel);assertNull(s.result);}
    @Test public void panelDisappearanceAndRearrangementDoNotEndRound()throws Exception{
        RoundSession s=session();start(s,0);s.frame(1200,true,false,hand());ack(s,request(s));ack(s,request(s));assertTrue(s.panel);
        s.frame(1400,false,false,List.of());s.frame(1800,false,false,List.of());assertTrue(s.panel);s.frame(2000,false,false,List.of());assertFalse(s.panel);
        s.frame(2200,true,false,hand());s.frame(2400,true,false,hand());assertTrue(s.panel);assertTrue(s.outgoing().isEmpty());
    }
    @Test public void realEndThenNextStartGetsExactlyNextVersion()throws Exception{
        RoundSession s=session();start(s,0);s.frame(1200,true,false,hand());ack(s,request(s));ack(s,request(s));s.frame(1400,false,true,List.of());assertTrue(s.outgoing().isEmpty());s.frame(1600,false,true,List.of());assertEquals("round.end",request(s).getString("type"));ack(s,request(s));state(s,2);
        start(s,1800);assertEquals(2,request(s).getInt("round_version"));assertEquals("end_then_start",request(s).getJSONObject("payload").getString("sync_basis"));assertEquals(1,request(s).getJSONObject("payload").getInt("previous_round_version"));
    }
    @Test public void serverVersionChangeCannotRelabelHand()throws Exception{RoundSession s=session();start(s,0);state(s,2);s.frame(1200,true,false,hand());assertEquals(1,request(s).getInt("round_version"));}
    @Test public void invalidCaptureBreaksConfirmation()throws Exception{RoundSession s=session();s.frame(0,false,false,List.of());s.gap();s.frame(800,true,false,hand());s.frame(1000,true,false,hand());assertTrue(s.outgoing().isEmpty());}
    @Test public void processLossStopsBusinessInsteadOfRejoining()throws Exception{String[] saved={""};RoundSession s=new RoundSession("100001","test_device","",v->saved[0]=v);state(s,1);start(s,0);RoundSession recovered=new RoundSession("100001","test_device",saved[0],v->{});state(recovered,1);start(recovered,2000);assertTrue(recovered.outgoing().isEmpty());assertTrue(recovered.panel);assertNull(recovered.result);}
    @Test public void lateResultDoesNotShowHiddenPanel()throws Exception{RoundSession s=session();start(s,0);s.frame(1200,true,false,hand());ack(s,request(s));ack(s,request(s));s.frame(1400,false,false,List.of());s.frame(2000,false,false,List.of());JSONArray cards=new JSONArray();for(String rank:HandSnapshots.RANKS)cards.put(new JSONObject().put("suit","s").put("rank",rank).put("count",1));s.receive(wire("round.result",1,new JSONObject().put("cards",cards).put("remaining_count",13)));assertNotNull(s.result);assertFalse(s.panel);s.receive(wire("round.result",2,new JSONObject().put("cards",cards).put("remaining_count",13)));assertEquals(1,s.result.round);}
    @Test public void duplicateCardsRetainedButThirdCopyRejected()throws Exception{List<String> h=hand();h.set(1,"spades:A");assertNotNull(RoundSession.identities(h));h.set(2,"spades:A");assertNull(RoundSession.identities(h));}
    @Test public void storageFailurePreventsAnyBusinessSend()throws Exception {
        RoundSession s=new RoundSession("100001","test_device","",v->{throw new IllegalStateException("disk full");});state(s,1);
        try{start(s,0);fail("must stop on disk failure");}catch(IllegalStateException expected){}
        assertTrue(s.outgoing().isEmpty());assertFalse(s.panel);
    }
    @Test public void administratorClearedBindingRearmsOnlyAfterFreshEvidence()throws Exception {
        String[] saved={""};RoundSession s=new RoundSession("100001","test_device","",v->saved[0]=v);state(s,1);start(s,0);
        RoundSession restored=new RoundSession("100001","test_device",saved[0],v->{});state(restored,1);
        restored.receive(wire("round.state",1,new JSONObject().put("enabled",true).put("state","waiting").put("bound_round_version",JSONObject.NULL)));
        restored.frame(0,true,false,hand());restored.frame(200,true,false,hand());assertTrue(restored.outgoing().isEmpty());
        start(restored,400);assertEquals("round.join",request(restored).getString("type"));assertEquals("test_device",request(restored).getString("client_id"));
    }

    @Test public void serverAdvanceClearsCardsButKeepsLocalArrangingPanel()throws Exception {
        RoundSession s=session();start(s,0);assertTrue(s.panel);
        state(s,2);assertTrue("本机仍摆牌时保留等待面板",s.panel);assertNull(s.result);
        s.frame(1200,true,false,hand());s.frame(1400,true,false,hand());assertTrue(s.panel);assertNull(s.result);
    }
    @Test public void closedServerRoundKeepsOnlyEmptyLocalPanel()throws Exception {
        RoundSession s=session();start(s,0);assertTrue(s.panel);
        s.receive(wire("round.state",1,new JSONObject().put("enabled",true).put("state","closed")));
        assertTrue(s.panel);assertNull(s.result);s.frame(1200,true,false,hand());s.frame(1400,true,false,hand());assertTrue(s.panel);assertNull(s.result);
    }
    @Test public void disabledTenantShowsNoCardsEvenAfterDisconnect()throws Exception {
        RoundSession s=session();start(s,0);
        s.receive(wire("round.state",1,new JSONObject().put("enabled",false).put("state","waiting")));
        s.connection(false);s.frame(1200,true,false,hand());s.frame(1400,true,false,hand());assertTrue(s.panel);assertNull(s.result);
    }

    @Test public void shortDetectionDropoutsDoNotBlinkOrCreateNewRounds()throws Exception {
        RoundSession s=session();start(s,0);s.frame(1200,true,false,hand());ack(s,request(s));ack(s,request(s));
        for(long t=1400;t<10000;t+=800){s.frame(t,false,false,List.of());s.frame(t+200,false,false,List.of());assertTrue(s.panel);s.frame(t+400,true,false,List.of());s.frame(t+600,true,false,List.of());assertTrue(s.panel);assertTrue(s.outgoing().isEmpty());}
    }

    @Test public void scatteredAbsenceCannotArmANewRound()throws Exception {
        RoundSession s=session();s.frame(0,false,false,List.of());s.frame(200,true,false,hand());s.frame(400,false,false,List.of());s.frame(600,true,false,hand());s.frame(800,false,false,List.of());s.frame(1000,true,false,hand());s.frame(1200,true,false,hand());assertTrue(s.outgoing().isEmpty());
    }
    @Test public void startWithoutAnyServerVersionShowsWaitingButDoesNotGuessBinding()throws Exception {
        RoundSession s=new RoundSession("100001","test_device","",v->{});start(s,0);s.frame(1200,true,false,hand());
        assertTrue("开局不等待网络才显示",s.panel);assertTrue(s.outgoing().isEmpty());state(s,1);assertTrue(s.panel);assertTrue("未知服务端版本的旧开局不可补绑",s.outgoing().isEmpty());
        start(s,1400);s.frame(2600,true,false,hand());ack(s,request(s));assertEquals("hand.submit",request(s).getString("type"));
    }
    @Test public void delayedEndAckAndStateDoNotBlockNextRound()throws Exception {
        RoundSession s=session();start(s,0);s.frame(1200,true,false,hand());ack(s,request(s));ack(s,request(s));
        s.frame(1400,false,true,List.of());s.frame(1600,false,true,List.of());JSONObject end=request(s);
        start(s,1800);s.frame(3000,true,false,hand());assertTrue(s.panel);assertNull(s.result);
        state(s,2);ack(s,end);assertEquals("round.join",request(s).getString("type"));assertEquals(2,request(s).getInt("round_version"));ack(s,request(s));assertEquals("hand.submit",request(s).getString("type"));
    }
    @Test public void rejectedLateHandCanContinueAfterRealEnd()throws Exception {
        RoundSession s=session();start(s,0);s.frame(1200,true,false,hand());ack(s,request(s));JSONObject submit=request(s);state(s,2);
        s.receive(wire("error",0,new JSONObject().put("code","ROUND_CLOSED").put("message","已关闭")).put("reply_to",submit.getString("request_id")));
        s.frame(1400,false,true,List.of());s.frame(1600,false,true,List.of());JSONObject end=request(s);
        s.receive(wire("error",0,new JSONObject().put("code","ROUND_CLOSED")).put("reply_to",end.getString("request_id")));
        start(s,1800);assertEquals("round.join",request(s).getString("type"));assertEquals(2,request(s).getInt("round_version"));
    }

    @Test public void firstOfflineStartCannotAdoptANewerVersion()throws Exception {
        RoundSession s=session();s.connection(false);start(s,0);s.frame(1200,true,false,hand());state(s,2);
        assertTrue("旧手牌不能借用重连后的版本",s.outgoing().isEmpty());assertTrue(s.panel);assertNull(s.result);
    }
    @Test public void lostJoinAckCanRecoverAfterLocalEndAndNextStart()throws Exception {
        RoundSession s=session();start(s,0);JSONObject join=request(s);s.frame(1200,true,false,hand());
        s.frame(1400,false,true,List.of());s.frame(1600,false,true,List.of());
        start(s,1800);s.frame(3000,true,false,hand());state(s,2);
        assertEquals("必须仍能重试原始join",join.toString(),request(s).toString());ack(s,join);
        JSONObject end=request(s);assertEquals("round.end",end.getString("type"));
        s.receive(wire("error",0,new JSONObject().put("code","ROUND_CLOSED")).put("reply_to",end.getString("request_id")));
        JSONObject next=request(s);assertEquals("round.join",next.getString("type"));assertEquals(2,next.getInt("round_version"));ack(s,next);assertEquals("hand.submit",request(s).getString("type"));
    }

    @Test public void firstOfflineStartKeepsItsVersionWhenReconnectIsStillSameRound()throws Exception {
        RoundSession s=session();s.connection(false);start(s,0);s.frame(1200,true,false,hand());state(s,1);JSONObject join=request(s);assertEquals(1,join.getInt("round_version"));ack(s,join);assertEquals("hand.submit",request(s).getString("type"));
    }

}
