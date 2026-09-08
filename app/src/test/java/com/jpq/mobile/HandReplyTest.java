package com.jpq.mobile;
import org.junit.Test;
import org.json.*;
import java.io.*;
import static org.junit.Assert.*;
public class HandReplyTest {
    JSONObject json(String id,int round)throws Exception{return new JSONObject().put("schema","jpq.hand-result/1").put("event_id",id).put("round_id",round).put("cards",new JSONArray("[{\"suit\":\"clubs\",\"rank\":\"5\",\"count\":2},{\"suit\":\"hearts\",\"rank\":\"Q\",\"count\":1}]"));}
    @Test public void gridUsesSuitRowsRankColumnsAndPreservesDuplicates()throws Exception{HandReply r=new HandReply(json("a",1));int five=HandSnapshots.RANKS.indexOf("5");assertTrue(r.occupied(4,five));assertTrue(r.occupied(5,five));assertTrue(r.occupied(2,HandSnapshots.RANKS.indexOf("Q")));assertFalse(r.occupied(0,five));}
    @Test public void oldRoundWrongRequestAndEndedResponsesNeverDisplayed()throws Exception{
        HandResultState state=new HandResultState();state.begin(1);state.expect("a");assertTrue(state.accept(new HandReply(json("a",1))));state.begin(2);assertNull(state.reply);state.expect("b");assertFalse(state.accept(new HandReply(json("a",1))));assertFalse(state.accept(new HandReply(json("a",2))));assertTrue(state.accept(new HandReply(json("b",2))));state.end();assertNull(state.reply);assertFalse(state.accept(new HandReply(json("b",2))));
    }
    @Test public void malformedRepliesRejectedAtomically()throws Exception{
        for(String change:new String[]{"suit","rank","count","round","duplicate"}){JSONObject data=json("a",1);JSONObject card=data.getJSONArray("cards").getJSONObject(0);switch(change){case "suit":card.put("suit","unknown");break;case "rank":card.put("rank","joker");break;case "count":card.put("count",1.5);break;case "round":data.put("round_id",1.5);break;default:data.getJSONArray("cards").put(card);}
            try{new HandReply(data);fail(change);}catch(JSONException expected){}
        }
    }
    @Test public void responseBodyHasSizeLimit()throws Exception{assertEquals("{}",HandUpload.readBody(new ByteArrayInputStream("{}".getBytes())));try{HandUpload.readBody(new ByteArrayInputStream(new byte[65537]));fail();}catch(IOException expected){}}
}
