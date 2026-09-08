package com.jpq.mobile;
import org.junit.Test;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;
public class HandSnapshotsTest {
    @Test public void preservesDuplicateIdentityAndRequiresConfirmation() throws Exception {
        HandSnapshots h=new HandSnapshots();var cards=List.of("clubs:5","hearts:Q","clubs:5");
        assertNull(h.observe("me",cards,2,"pack"));JSONObject data=h.observe("me",cards,2,"pack");
        assertEquals(3,data.getInt("observed_count"));assertFalse(data.getBoolean("complete"));
        assertEquals(2,data.getJSONArray("cards").getJSONObject(0).getInt("count"));
        assertEquals("clubs",data.getJSONArray("cards").getJSONObject(0).getString("suit"));
        assertNull(h.observe("me",List.of("hearts:Q","clubs:5","clubs:5"),2,"pack"));
    }
    @Test public void emptyFrameDoesNotInventEmptyHandAndBreaksConfirmation() throws Exception {
        HandSnapshots h=new HandSnapshots();var cards=List.of("spades:A");
        assertNull(h.observe("me",cards,2,"pack"));assertNull(h.observe("me",List.of(),2,"pack"));
        assertNull(h.observe("me",cards,2,"pack"));assertNotNull(h.observe("me",cards,2,"pack"));
        assertNull(h.observe("me",List.of(),2,"pack"));assertNull(h.observe("me",cards,2,"pack"));assertNull(h.observe("me",cards,2,"pack"));
        h.reset();assertNull(h.observe("me",cards,2,"pack"));assertEquals(2,h.observe("me",cards,2,"pack").getInt("sequence"));
    }
    @Test public void changedHandGetsDifferentEventId() throws Exception {
        HandSnapshots h=new HandSnapshots();JSONObject first=h.observe("me",List.of("clubs:5","clubs:5"),1,"pack");
        JSONObject second=h.observe("me",List.of("clubs:5"),1,"pack");
        assertNotEquals(first.getString("event_id"),second.getString("event_id"));assertEquals(first.getString("session_id"),second.getString("session_id"));
    }
    @Test public void rejectsUnprotectedOrCredentialBearingEndpoint() {
        for(String url:List.of("http://example.com","https://user:password@example.com","https://example.com/#token","")){
            try(HandUpload u=new HandUpload(url)){fail("accepted "+url);}catch(IllegalArgumentException expected){}
        }
    }
}
