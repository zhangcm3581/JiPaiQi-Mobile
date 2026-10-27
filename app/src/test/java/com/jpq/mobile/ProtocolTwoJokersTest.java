package com.jpq.mobile;
import org.json.*;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ProtocolTwoJokersTest {
 @Test public void recordedTwoJokerHandsAndLimits()throws Exception{
  JSONArray hands=new JSONArray(new String(getClass().getResourceAsStream("/two-joker-replay.json").readAllBytes(),StandardCharsets.UTF_8)).getJSONArray(0);
  int small=0,big=0;
  for(int i=0;i<8;i++){
   JSONArray cards=ProtocolDeck.decode(hands.getJSONArray(i),ProtocolDeck.TWO);assertEquals(13,cards.length());
   for(int j=0;j<13;j++){JSONObject c=cards.getJSONObject(j);if(c.getString("suit").equals("j")){if(c.getString("rank").equals("big"))big++;else small++;}}
  }
  assertEquals(1,big);assertEquals(1,small);
  assertEquals(1,ProtocolDeck.limit("s","2",ProtocolDeck.TWO));assertEquals(2,ProtocolDeck.limit("s","4",ProtocolDeck.TWO));
  try{ProtocolDeck.decode(new JSONArray(new int[]{15,15,34,35,36,37,38,39,40,41,42,43,44}),ProtocolDeck.TWO);fail();}catch(JSONException expected){}
 }
 @Test public void twoJokerCapabilityRequired()throws Exception{
  ProtocolSessionTest helper=new ProtocolSessionTest();
  ProtocolSession s=new ProtocolSession("100001","01","",x->{},ProtocolDeck.TWO);s.connection(true);
  s.receive(helper.wire("round.state",1,new JSONObject().put("enabled",true).put("state","waiting").put("capabilities",new JSONArray().put("round.phase/1").put("game_key/1").put("deck.mode/1"))));
  assertTrue(s.status.contains("更新服务端"));assertTrue(s.outgoing().isEmpty());
 }
}
