package com.jpq.mobile;
import org.json.*;
import java.util.*;
/** Stable observations, not proof of a complete hand or a new play. */
public final class HandSnapshots {
    private final Map<String,String> candidate=new HashMap<>(),sent=new HashMap<>();
    private final Map<String,Integer> streak=new HashMap<>();
    private final String session=UUID.randomUUID().toString();private long sequence;
    public void reset(){candidate.clear();sent.clear();streak.clear();}
    public JSONObject observe(String region,List<String> cards,int confirm,String packageId)throws JSONException{
        if(cards.isEmpty()){candidate.remove(region);streak.remove(region);return null;}
        TreeMap<String,Integer> counts=new TreeMap<>();for(String card:cards)counts.merge(card,1,Integer::sum);
        String signature=counts.toString();int n=signature.equals(candidate.get(region))?streak.getOrDefault(region,0)+1:1;
        candidate.put(region,signature);streak.put(region,n);
        if(n<confirm||signature.equals(sent.get(region)))return null;
        JSONArray values=new JSONArray();for(var entry:counts.entrySet()){String[] parts=entry.getKey().split(":",2);if(parts.length!=2)throw new JSONException("缺少花色");values.put(new JSONObject().put("suit",parts[0]).put("rank",parts[1]).put("count",entry.getValue()));}
        sent.put(region,signature);
        return new JSONObject().put("schema","jpq.hand-snapshot/1").put("event_id",UUID.randomUUID().toString()).put("session_id",session).put("sequence",++sequence).put("captured_at_ms",System.currentTimeMillis()).put("package_id",packageId).put("region_id",region).put("seat_id","me").put("complete",false).put("observed_count",cards.size()).put("cards",values);
    }
}
