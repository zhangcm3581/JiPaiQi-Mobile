package com.jpq.mobile;
import org.json.*;
/** Immutable, bounded server grid. Never populated from local recognition. */
public final class HandReply {
    public final String eventId;public final int round;
    public final int totalCount;
    private final boolean[][] cells=new boolean[8][13];
    public boolean occupied(int row,int column){return cells[row][column];}
    public HandReply(JSONObject json)throws JSONException{
        if(!"jpq.hand-result/1".equals(json.getString("schema")))throw new JSONException("响应版本不支持");
        eventId=json.getString("event_id");Object roundValue=json.get("round_id");if(!(roundValue instanceof Number)||((Number)roundValue).doubleValue()!=((Number)roundValue).intValue()||((Number)roundValue).intValue()<1)throw new JSONException("局 ID 无效");round=((Number)roundValue).intValue();
        int total=0;
        JSONArray cards=json.getJSONArray("cards");if(cards.length()>52)throw new JSONException("响应牌种过多");
        for(int i=0;i<cards.length();i++){JSONObject card=cards.getJSONObject(i);int suit=HandSnapshots.SUITS.indexOf(card.getString("suit")),rank=HandSnapshots.RANKS.indexOf(card.getString("rank"));Object n=card.get("count");
            if(suit<0||rank<0||!(n instanceof Number)||((Number)n).doubleValue()!=((Number)n).intValue())throw new JSONException("点数、花色或数量无效");
            int count=((Number)n).intValue();if(count<1||count>2||cells[suit*2][rank])throw new JSONException("重复牌种或数量超出两副牌");
            total+=count;
            for(int copy=0;copy<count;copy++)cells[suit*2+copy][rank]=true;
        }
        if(total!=13)throw new JSONException("服务器结果必须恰好13张，实际"+total+"张");
        totalCount=total;
    }
}
