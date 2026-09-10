package com.jpq.mobile;
import org.junit.Test;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;

public class RoundSessionEdgeTest {
    final RoundSessionTest f=new RoundSessionTest();
    RoundSession joined()throws Exception{RoundSession s=f.session();s.frame(0,false,false,List.of());s.frame(600,false,false,List.of());s.frame(800,true,false,List.of());s.frame(1000,true,false,List.of());f.ack(s,f.request(s));assertTrue(s.outgoing().isEmpty());return s;}
    @Test public void everyIncompleteAndOversizeHandCannotUpload()throws Exception {
        for(int n=0;n<=104;n++){if(n==13)continue;RoundSession s=joined();List<String> input=new ArrayList<>();for(int i=0;i<n;i++)input.add("spades:"+HandSnapshots.RANKS.get(i%13));
            for(int i=0;i<10;i++)s.frame(1200+i*200,true,false,input);assertTrue("size="+n,s.outgoing().isEmpty());
            s.frame(3400,true,false,f.hand());assertTrue("first valid sample",s.outgoing().isEmpty());s.frame(3600,true,false,f.hand());assertEquals("hand.submit",f.request(s).getString("type"));
        }
    }
    @Test public void seededHandsKeepEverySuitRankAndDuplicateExactly()throws Exception {
        Random random=new Random(20260910);List<String> deck=new ArrayList<>();for(String suit:HandSnapshots.SUITS)for(String rank:HandSnapshots.RANKS){deck.add(suit+":"+rank);deck.add(suit+":"+rank);}
        for(int i=0;i<1000;i++){Collections.shuffle(deck,random);List<String> original=new ArrayList<>(deck.subList(0,13));RoundSession s=joined();s.frame(1200,true,false,original);assertTrue(s.outgoing().isEmpty());List<String> reordered=new ArrayList<>(original);Collections.shuffle(reordered,random);s.frame(1400,true,false,reordered);
            JSONObject request=f.request(s);assertEquals(RoundSession.identities(original).toString(),request.getJSONObject("payload").getJSONArray("cards").toString());
            String wire=request.toString();for(int j=0;j<3;j++){s.connection(false);assertTrue(s.outgoing().isEmpty());f.state(s,1);assertEquals("retry must preserve identity and version",wire,f.request(s).toString());}
            f.ack(s,request);for(int j=0;j<4;j++)s.frame(1600+j*200,true,false,f.hand());assertTrue("once per round",s.outgoing().isEmpty());
        }
    }
    @Test public void alternatingPlausibleHandsNeverConfirm()throws Exception {
        RoundSession s=joined();List<String> a=f.hand(),b=new ArrayList<>(a);b.set(0,"hearts:A");
        for(int i=0;i<100;i++)s.frame(1200+i*200,true,false,i%2==0?a:b);assertTrue(s.outgoing().isEmpty());
        s.frame(21200,true,false,b);assertEquals(RoundSession.identities(b).toString(),f.request(s).getJSONObject("payload").getJSONArray("cards").toString());
    }
    @Test public void endCandidateInterruptsUnconfirmedHand()throws Exception {
        RoundSession s=joined();s.frame(1200,true,false,f.hand());s.frame(1400,false,true,List.of());s.frame(1600,true,false,f.hand());
        assertTrue("two valid frames separated by an end candidate are not consecutive",s.outgoing().isEmpty());
        s.frame(1800,true,false,f.hand());assertEquals("hand.submit",f.request(s).getString("type"));
    }
    @Test public void missingFramesAndSettingsInterruptConfirmation()throws Exception {
        for(boolean explicit:new boolean[]{true,false}){RoundSession s=joined();s.frame(1200,true,false,f.hand());if(explicit)s.gap();s.frame(3000,true,false,f.hand());assertTrue(s.outgoing().isEmpty());s.frame(3200,true,false,f.hand());assertEquals("hand.submit",f.request(s).getString("type"));}
    }
    @Test public void noInvalidIdentityOrThirdCopyCanUpload()throws Exception {
        for(String bad:List.of("spades:1","joker:A","hearts:14","spades:A:extra","spades:a","spades:","","clubs:10 ")){RoundSession s=joined();List<String> cards=f.hand();cards.set(0,bad);s.frame(1200,true,false,cards);s.frame(1400,true,false,cards);assertTrue(bad,s.outgoing().isEmpty());}
        RoundSession s=joined();List<String> cards=f.hand();cards.set(1,"spades:A");cards.set(2,"spades:A");for(int i=0;i<5;i++)s.frame(1200+i*200,true,false,cards);assertTrue(s.outgoing().isEmpty());
    }
    JSONObject result(int n)throws Exception{JSONArray cards=new JSONArray();for(int i=0;i<n;i++)cards.put(new JSONObject().put("suit","s").put("rank",HandSnapshots.RANKS.get(i%13)).put("count",1));return new JSONObject().put("remaining_count",n).put("cards",cards);}
    @Test public void resultResetsAndLateMessagesNeverLeakAcrossRounds()throws Exception {
        RoundSession s=joined();s.frame(1200,true,false,f.hand());s.frame(1400,true,false,f.hand());f.ack(s,f.request(s));s.receive(f.wire("round.result",1,result(13)));assertNotNull(s.result);assertEquals(13,s.result.totalCount);
        s.frame(1600,false,false,List.of());s.frame(2200,false,false,List.of());assertFalse(s.panel);s.receive(f.wire("round.result",1,result(13)));assertFalse(s.panel);
        s.frame(2400,false,true,List.of());s.frame(2600,false,true,List.of());assertNull(s.result);assertFalse(s.panel);f.ack(s,f.request(s));f.state(s,2);f.start(s,2800);assertTrue(s.panel);assertNull(s.result);f.ack(s,f.request(s));
        s.receive(f.wire("round.result",1,result(13)));assertNull("stale result",s.result);s.receive(f.wire("round.result",3,result(13)));assertNull("future result",s.result);s.receive(f.wire("round.result",2,result(13)).put("tenant_id","100002"));assertNull("wrong tenant",s.result);
    }
    @Test public void malformedResultNeverFillsPanel()throws Exception {
        for(int count:new int[]{0,1,12,14}){RoundSession s=joined();try{s.receive(f.wire("round.result",1,result(count)));fail("invalid result "+count);}catch(JSONException expected){}assertNull(s.result);}
    }
}
