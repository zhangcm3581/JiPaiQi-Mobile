package com.jpq.mobile;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProtocolSessionTest {
    private static final int[] HAND={36,71,27,45,34,68,71,36,26,50,42,38,66};
    ProtocolSession make()throws Exception{return new ProtocolSession("100001","01","",x->{});}
    JSONObject wire(String type,int version,JSONObject data)throws Exception{return new JSONObject().put("protocol_version",1).put("tenant_id","100001").put("type",type).put("round_version",version).put("payload",data);}
    void state(ProtocolSession s,int version,String phase)throws Exception{s.receive(wire("round.state",version,new JSONObject().put("enabled",true).put("state","collecting").put("phase",phase).put("capabilities",new JSONArray().put("round.phase/1").put("game_key/1"))));}
    void ready(ProtocolSession s)throws Exception{s.connection(true);state(s,1,"playing");}
    JSONObject event(String type,int round)throws Exception{return new JSONObject().put("event",type).put("game_key","room:"+round).put("cards",new JSONArray(HAND));}
    JSONObject result(ProtocolSession s)throws Exception{
        JSONObject request=new JSONObject(s.outgoing().get(0));
        JSONObject p=new JSONObject().put("remaining_count",13).put("client_id","01").put("start_event_id",request.getJSONObject("payload").getString("start_event_id"));
        JSONArray cards=new JSONArray();for(int i=2;i<=14;i++)cards.put(new JSONObject().put("suit","s").put("rank",i==14?"A":i==13?"K":i==12?"Q":i==11?"J":""+i).put("count",1));return wire("round.result",1,p.put("cards",cards));
    }
    @Test public void roomIsNotSentToServer()throws Exception{
        ProtocolSession s=make();ready(s);
        JSONObject deal=event("deal",1).put("game_key","hand_20_21_22:1");
        s.game(deal);
        JSONObject request=new JSONObject(s.outgoing().get(0));
        assertFalse(request.getJSONObject("payload").has("game_key"));
        assertEquals(13,request.getJSONObject("payload").getJSONArray("cards").length());
        s.game(deal);assertEquals(1,s.outgoing().size());
    }
    @Test public void immediateExactlyOnceAndStableRetries()throws Exception{
        ProtocolSession s=make();ready(s);s.game(event("deal",1));assertEquals(1,s.outgoing().size());String request=s.outgoing().get(0);
        assertEquals("hand.submit",new JSONObject(request).getString("type"));s.game(event("deal",1));assertEquals(request,s.outgoing().get(0));
        s.connection(false);assertTrue(s.outgoing().isEmpty());s.connection(true);state(s,1,"playing");assertEquals(request,s.outgoing().get(0));
    }
    @Test public void thirteenRequiredAndAceFourteen()throws Exception{
        JSONArray cards=ProtocolSession.decode(new JSONArray(new int[]{78,28,45,45,67,27,21,57,71,70,58,44,74}));assertEquals(13,cards.length());
        try{ProtocolSession.decode(new JSONArray(new int[]{20}));fail();}catch(JSONException expected){}
        try{ProtocolSession.decode(new JSONArray(new int[]{20,20,20,45,67,27,21,57,71,70,58,44,74}));fail();}catch(JSONException expected){}
    }
    @Test public void compareHidesAndRejectsLateResults()throws Exception{
        ProtocolSession s=make();ready(s);s.game(event("deal",1));JSONObject r=result(s);s.receive(r);assertTrue(s.panel);
        s.receive(wire("round.phase",1,new JSONObject().put("phase","comparing")));assertFalse(s.panel);assertNull(s.result);s.receive(r);assertNull(s.result);
    }
    @Test public void compareWithoutResultAndReconnectRemainHidden()throws Exception{
        ProtocolSession s=make();ready(s);s.game(event("deal",1));JSONObject r=result(s);s.connection(false);s.connection(true);state(s,1,"comparing");s.receive(r);assertFalse(s.panel);assertNull(s.result);
    }
    @Test public void remoteEndAllowsNextRealDealButRejectsOldDeal()throws Exception{
        ProtocolSession s=make();ready(s);s.game(event("deal",1));JSONObject old=result(s);s.receive(wire("round.phase",1,new JSONObject().put("phase","ended")));state(s,2,"playing");
        s.game(event("deal",1));assertTrue(s.outgoing().isEmpty());s.game(event("deal",2));assertEquals(2,new JSONObject(s.outgoing().get(0)).getInt("round_version"));s.receive(old);assertNull(s.result);
    }
    @Test public void nextDealBeforeEndAckWaitsForNewVersion()throws Exception{
        ProtocolSession s=make();ready(s);s.game(event("deal",1));s.game(event("end",1));s.game(event("deal",2));
        assertEquals(1,s.outgoing().size());assertEquals("round.end",new JSONObject(s.outgoing().get(0)).getString("type"));state(s,2,"playing");
        assertTrue(s.outgoing().stream().anyMatch(x->x.contains("hand.submit")));
    }
    @Test public void bigSettlementDoesNotCloseNextRound()throws Exception{
        ProtocolSession s=make();ready(s);s.game(event("deal",1));s.game(event("end",1));int n=s.outgoing().size();s.game(event("settled",1));assertEquals(n,s.outgoing().size());
        state(s,2,"playing");s.game(event("deal",2));s.game(event("settled",1));assertEquals(1,s.outgoing().size());
    }
    @Test public void restartDoesNotReplayOldGame()throws Exception{
        String[] saved={""};ProtocolSession s=new ProtocolSession("100001","01","",x->saved[0]=x);ready(s);s.game(event("deal",1));
        ProtocolSession restarted=new ProtocolSession("100001","01",saved[0],x->{});ready(restarted);restarted.game(event("deal",1));assertTrue(restarted.outgoing().isEmpty());restarted.game(event("deal",2));assertEquals(1,restarted.outgoing().size());
    }
    @Test public void oldErrorsCannotDiscardNextHand()throws Exception{
        ProtocolSession s=make();ready(s);s.game(event("deal",1));String old=new JSONObject(s.outgoing().get(0)).getString("request_id");
        state(s,2,"playing");s.game(event("deal",2));String next=s.outgoing().get(0);
        s.receive(wire("error",1,new JSONObject().put("code","ROUND_CLOSED")).put("reply_to",old));assertEquals(next,s.outgoing().get(0));
    }
    @Test public void registrationErrorSurvivesSocketClose()throws Exception{
        ProtocolSession s=make();s.connection(true);s.receive(wire("error",0,new JSONObject().put("code","TENANT_DISABLED").put("message","disabled")));s.connection(false);
        assertTrue(s.status.contains("TENANT_DISABLED"));assertTrue(s.outgoing().isEmpty());
    }
    @Test public void incompatibleServerCannotReceiveHands()throws Exception{
        ProtocolSession s=make();s.connection(true);s.receive(wire("round.state",1,new JSONObject().put("enabled",true)));s.game(event("deal",1));assertTrue(s.outgoing().isEmpty());
    }
    @Test public void anotherTenantAndOldPhaseAreIgnored()throws Exception{
        ProtocolSession s=make();ready(s);s.game(event("deal",1));s.receive(result(s));assertTrue(s.panel);
        s.receive(wire("round.phase",1,new JSONObject().put("phase","ended")).put("tenant_id","100002"));assertTrue(s.panel);
        s.receive(wire("round.phase",0,new JSONObject().put("phase","ended")));assertTrue(s.panel);
    }
    @Test public void firstUnsyncedHandCannotBeAssignedToLaterVersion()throws Exception{
        ProtocolSession s=make();s.game(event("deal",1));s.connection(true);state(s,2,"playing");
        assertTrue(s.outgoing().isEmpty());s.game(event("deal",1));assertTrue(s.outgoing().isEmpty());
        s.game(event("deal",2));assertEquals(2,new JSONObject(s.outgoing().get(0)).getInt("round_version"));
    }
}
