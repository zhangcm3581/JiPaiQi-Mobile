package com.jpq.mobile;
import org.junit.Test;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;
public class HandSnapshotsTest {
    List<String> hand(){return List.of("clubs:A","hearts:Q","spades:10","hearts:9","clubs:9","hearts:7","clubs:7","spades:6","clubs:5","clubs:5","clubs:4","spades:2","hearts:2");}
    @Test public void thirteenOnlyAndAtLeastTwoConfirmations()throws Exception{
        HandSnapshots h=new HandSnapshots();assertNull(h.observe("me",hand(),1,"p"));h.beginRound(1);
        assertNull(h.observe("me",hand().subList(0,12),2,"p"));var tooMany=new ArrayList<>(hand());tooMany.add("diamonds:A");assertNull(h.observe("me",tooMany,2,"p"));
        assertNull(h.observe("me",hand(),1,"p"));JSONObject packet=h.observe("me",hand(),1,"p");assertEquals(13,packet.getInt("observed_count"));assertEquals(1,packet.getInt("round_id"));
        assertEquals(13,packet.getJSONArray("cards").length());int duplicate=0;for(int i=0;i<13;i++){JSONObject card=packet.getJSONArray("cards").getJSONObject(i);assertTrue(HandSnapshots.SUITS.contains(card.getString("suit")));assertTrue(HandSnapshots.RANKS.contains(card.getString("rank")));if(card.getString("rank").equals("5")){assertEquals("clubs",card.getString("suit"));duplicate++;}}assertEquals(2,duplicate);
    }
    @Test public void invalidFrameBreaksStreak()throws Exception{HandSnapshots h=new HandSnapshots();h.beginRound(1);assertNull(h.observe("me",hand(),2,"p"));assertNull(h.observe("me",List.of(),2,"p"));assertNull(h.observe("me",hand(),2,"p"));assertNotNull(h.observe("me",hand(),2,"p"));}
    @Test public void oncePerRoundDespitePauseOrderAndHandChanges()throws Exception{
        HandSnapshots h=new HandSnapshots();h.beginRound(1);h.observe("me",hand(),2,"p");JSONObject first=h.observe("me",hand(),2,"p");h.reset();var reversed=new ArrayList<>(hand());Collections.reverse(reversed);assertNull(h.observe("me",reversed,2,"p"));assertNull(h.observe("me",hand(),2,"p"));
        h.beginRound(2);assertNull(h.observe("me",hand(),2,"p"));JSONObject second=h.observe("me",hand(),2,"p");assertEquals(2,second.getInt("round_id"));assertNotEquals(first.getString("event_id"),second.getString("event_id"));
    }
    @Test public void unknownSuitAndThirdCopyRejected()throws Exception{
        HandSnapshots h=new HandSnapshots();h.beginRound(1);var invalid=new ArrayList<>(hand());invalid.set(0,"unknown:A");for(int i=0;i<3;i++)assertNull(h.observe("me",invalid,2,"p"));invalid.set(0,"clubs:5");for(int i=0;i<3;i++)assertNull(h.observe("me",invalid,2,"p"));
    }
    @Test public void rejectsUnprotectedEndpoint(){for(String url:List.of("http://example.com","https://user:password@example.com","https://example.com/#token","")){try(HandUpload u=new HandUpload(url)){fail();}catch(IllegalArgumentException expected){}}}
}
