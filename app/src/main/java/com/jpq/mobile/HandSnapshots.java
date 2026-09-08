package com.jpq.mobile;
import org.json.*;
import java.util.*;
/** Exactly thirteen validated identities, confirmed once per detected round. */
public final class HandSnapshots {
    public static final List<String> RANKS=List.of("A","2","3","4","5","6","7","8","9","10","J","Q","K");
    public static final int DECK_COUNT=2, HAND_COUNT=13;
    public static Map<String,Integer> deck(){Map<String,Integer> result=new LinkedHashMap<>();for(String rank:RANKS)result.put(rank,8);return result;}
    public static final List<String> SUITS=List.of("spades","hearts","clubs","diamonds");
    private String candidate="",region="";private int streak,round;private boolean emitted;
    private final String session=UUID.randomUUID().toString();private long sequence;
    public void beginRound(int value){if(value!=round){round=value;emitted=false;reset();}}
    /** A capture gap breaks confirmation, never re-arms an already sent round. */
    public void reset(){candidate="";region="";streak=0;}
    public JSONObject observe(String key,List<String> cards,int confirm,String packageId)throws JSONException{
        if(round<=0||emitted)return null;
        if(cards.size()!=13){reset();return null;}
        TreeMap<String,Integer> counts=new TreeMap<>();
        for(String card:cards){String[] p=card.split(":",-1);if(p.length!=2||!SUITS.contains(p[0])||!RANKS.contains(p[1])){reset();return null;}counts.merge(card,1,Integer::sum);}
        // Reference game uses at most two copies of each suit/rank.
        if(counts.values().stream().anyMatch(n->n>2)){reset();return null;}
        String signature=counts.toString();streak=signature.equals(candidate)&&key.equals(region)?streak+1:1;candidate=signature;region=key;
        if(streak<Math.max(2,confirm))return null;
        JSONArray values=new JSONArray();for(var e:counts.entrySet()){String[] p=e.getKey().split(":");for(int copy=0;copy<e.getValue();copy++)values.put(new JSONObject().put("suit",p[0]).put("rank",p[1]));}
        JSONObject data=new JSONObject().put("schema","jpq.hand-snapshot/2").put("game","shisanshui").put("deck_count",DECK_COUNT).put("event_id",UUID.randomUUID().toString()).put("session_id",session).put("round_id",round).put("sequence",++sequence).put("captured_at_ms",System.currentTimeMillis()).put("package_id",packageId).put("region_id",key).put("seat_id","me").put("expected_count",13).put("observed_count",13).put("cards",values);
        emitted=true;return data;
    }
}
