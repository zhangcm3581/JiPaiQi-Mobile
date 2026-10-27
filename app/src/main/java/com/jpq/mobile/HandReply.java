package com.jpq.mobile;
import org.json.*;
/** Immutable, bounded server grid. Never populated from local recognition. */
public final class HandReply {
    public final String eventId;public final int round;
    public final int totalCount;
    public final String mode;
    public int smallJokers,bigJokers;
    private final boolean[][] cells=new boolean[8][13];
    public boolean occupied(int row,int column){return cells[row][column];}
    public HandReply(JSONObject json)throws JSONException{
        if(!"jpq.hand-result/1".equals(json.getString("schema")))throw new JSONException("响应版本不支持");
        eventId=json.getString("event_id");Object roundValue=json.get("round_id");if(!(roundValue instanceof Number)||((Number)roundValue).doubleValue()!=((Number)roundValue).intValue()||((Number)roundValue).intValue()<1)throw new JSONException("局 ID 无效");round=((Number)roundValue).intValue();
        mode=json.optString("mode",ProtocolDeck.STANDARD);if(!ProtocolDeck.valid(mode))throw new JSONException("未知结果模式");
        int total=0;
        JSONArray cards=json.getJSONArray("cards");if(cards.length()>54)throw new JSONException("响应牌种过多");
        for(int i=0;i<cards.length();i++){JSONObject card=cards.getJSONObject(i);int suit=HandSnapshots.SUITS.indexOf(card.getString("suit")),rank=HandSnapshots.RANKS.indexOf(card.getString("rank"));Object n=card.get("count");
            if("j".equals(card.getString("suit"))){
                String r=card.getString("rank");
                if(!(n instanceof Number)||((Number)n).doubleValue()!=((Number)n).intValue())throw new JSONException("大小王数量无效");
                int count=((Number)n).intValue();
                if(count<1||count>ProtocolDeck.limit("j",r,mode)||(r.equals("small")?smallJokers:bigJokers)>0)throw new JSONException("大小王数量超限或重复");
                if(r.equals("small"))smallJokers=count;else bigJokers=count;
                total+=count;continue;
            }
            if(suit<0||rank<0||!(n instanceof Number)||((Number)n).doubleValue()!=((Number)n).intValue())throw new JSONException("点数、花色或数量无效");
            int count=((Number)n).intValue();if(count<1||count>ProtocolDeck.limit(java.util.List.of("s","h","c","d").get(suit),card.getString("rank"),mode)||cells[suit*2][rank])throw new JSONException("重复牌种或数量超出两副牌");
            total+=count;
            for(int copy=0;copy<count;copy++)cells[suit*2+copy][rank]=true;
        }
        if(total!=13)throw new JSONException("服务器结果必须恰好13张，实际"+total+"张");
        totalCount=total;
    }
}
