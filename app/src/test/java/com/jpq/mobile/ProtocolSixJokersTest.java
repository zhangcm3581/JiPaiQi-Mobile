package com.jpq.mobile;
import org.json.*;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ProtocolSixJokersTest extends ProtocolSessionTest {
    ProtocolSession six()throws Exception{return new ProtocolSession("100001","01","",x->{},ProtocolDeck.SIX);}
    void sixReady(ProtocolSession s)throws Exception{
        s.connection(true);s.receive(wire("round.state",1,new JSONObject().put("enabled",true).put("state","waiting").put("capabilities",new JSONArray().put("round.phase/1").put("game_key/1").put("deck.mode/1"))));
    }
    @Test public void capturedHandsDecodeAndSubmitInSixMode()throws Exception{
        JSONArray rounds=new JSONArray(new String(getClass().getResourceAsStream("/six-joker-replay.json").readAllBytes(),StandardCharsets.UTF_8));
        assertEquals(3,rounds.length());
        for(int r=0;r<rounds.length();r++)for(int i=0;i<8;i++){
            ProtocolSession s=six();sixReady(s);
            s.game(new JSONObject().put("event","deal").put("game_key","replay:"+(r+1)).put("cards",rounds.getJSONArray(r).getJSONArray(i)));
            JSONObject p=new JSONObject(s.outgoing().get(0)).getJSONObject("payload");
            assertEquals("six_jokers",p.getString("mode"));assertEquals(13,p.getJSONArray("cards").length());
        }
    }
    @Test public void oldServerCannotReceiveSixJokerHands()throws Exception{
        ProtocolSession s=six();ready(s);assertTrue(s.status.contains("六王"));assertTrue(s.outgoing().isEmpty());
    }
    @Test public void jokerAndReducedSpadeLimits()throws Exception{
        assertEquals(3,ProtocolDeck.limit("j","big",ProtocolDeck.SIX));assertEquals(0,ProtocolDeck.limit("j","big",ProtocolDeck.STANDARD));
        assertEquals(1,ProtocolDeck.limit("s","2",ProtocolDeck.SIX));assertEquals(2,ProtocolDeck.limit("h","2",ProtocolDeck.SIX));
        JSONArray bad=new JSONArray(new int[]{14,14,14,14,34,35,36,37,38,39,40,41,42});
        try{ProtocolDeck.decode(bad,ProtocolDeck.SIX);fail();}catch(JSONException expected){}
        bad=new JSONArray(new int[]{18,18,34,35,36,37,38,39,40,41,42,43,44});
        try{ProtocolDeck.decode(bad,ProtocolDeck.SIX);fail();}catch(JSONException expected){}
    }
    @Test public void sixJokerResultDisplaysAndCompareHides()throws Exception{
        ProtocolSession s=six();sixReady(s);s.game(event("deal",1));
        JSONObject p=result(s).getJSONObject("payload").put("mode",ProtocolDeck.SIX);
        JSONArray cards=new JSONArray();
        cards.put(new JSONObject().put("suit","j").put("rank","big").put("count",3));
        cards.put(new JSONObject().put("suit","j").put("rank","small").put("count",3));
        for(int i=2;i<=8;i++)cards.put(new JSONObject().put("suit","h").put("rank",""+i).put("count",1));
        p.put("cards",cards);s.receive(wire("round.result",1,p));
        assertTrue(s.panel);assertEquals(3,s.result.bigJokers);assertEquals(3,s.result.smallJokers);
        s.receive(wire("round.phase",1,new JSONObject().put("phase","comparing")));assertFalse(s.panel);assertNull(s.result);
    }
}
