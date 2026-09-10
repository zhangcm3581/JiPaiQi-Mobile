package com.jpq.mobile;
import org.junit.Test;
import org.json.*;
import java.io.*;
import static org.junit.Assert.*;
public class HandReplyTest {
    JSONObject json(String id,int round)throws Exception{
        JSONArray cards=new JSONArray().put(new JSONObject().put("suit","clubs").put("rank","5").put("count",2)).put(new JSONObject().put("suit","hearts").put("rank","Q").put("count",1));
        for(String rank:HandSnapshots.RANKS.subList(0,10))cards.put(new JSONObject().put("suit","diamonds").put("rank",rank).put("count",1));
        return new JSONObject().put("schema","jpq.hand-result/1").put("event_id",id).put("round_id",round).put("cards",cards);
    }
    @Test public void incompleteAndOversizedResultsCannotReachPanel()throws Exception{
        for(int count:new int[]{0,6,12,14}){
            JSONArray cards=new JSONArray();for(int i=0;i<count;i++)cards.put(new JSONObject().put("rank",HandSnapshots.RANKS.get(i%13)).put("suit",i<13?"spades":"hearts").put("count",1));
            JSONObject data=json("bad",1).put("cards",cards);
            try{new HandReply(data);fail("accepted "+count);}catch(JSONException expected){}
        }
        assertEquals(13,new HandReply(json("ok",1)).totalCount);
    }
    @Test public void gridUsesSuitRowsRankColumnsAndPreservesDuplicates()throws Exception{HandReply r=new HandReply(json("a",1));assertEquals("A",HandSnapshots.RANKS.get(0));assertEquals("2",HandSnapshots.RANKS.get(1));assertEquals("K",HandSnapshots.RANKS.get(12));int five=4;assertTrue(r.occupied(4,five));assertTrue(r.occupied(5,five));assertTrue(r.occupied(2,11));assertFalse(r.occupied(0,five));}
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
