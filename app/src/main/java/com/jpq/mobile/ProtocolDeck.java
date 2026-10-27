package com.jpq.mobile;
import org.json.*;
import java.util.*;

/** Versioned deck selection, independent of room metadata. */
public final class ProtocolDeck {
    public static final String STANDARD="standard", TWO="two_jokers", SIX="six_jokers";
    public static boolean valid(String mode){return STANDARD.equals(mode)||TWO.equals(mode)||SIX.equals(mode);}
    public static int limit(String suit,String rank,String mode){
        if(!valid(mode))return 0;
        if(suit.equals("j"))return !STANDARD.equals(mode)&&(rank.equals("small")||rank.equals("big"))?(TWO.equals(mode)?1:3):0;
        if(!List.of("s","h","c","d").contains(suit)||!HandSnapshots.RANKS.contains(rank))return 0;
        return suit.equals("s")&&((SIX.equals(mode)&&List.of("2","3","4","5","6","7").contains(rank))||(TWO.equals(mode)&&List.of("2","3").contains(rank)))?1:2;
    }
    public static JSONArray decode(JSONArray values,String mode)throws JSONException{
        if(!valid(mode)||values.length()!=13)throw new JSONException("需要合法模式及13张手牌");
        JSONArray result=new JSONArray();Map<String,Integer> counts=new HashMap<>();
        for(int i=0;i<values.length();i++){
            Object raw=values.get(i);if(!(raw instanceof Integer))throw new JSONException("牌值必须是整数");
            int v=(Integer)raw;String suit,rank;
            if(v==14||v==15){suit="j";rank=v==14?"small":"big";}
            else{int s=v>>4,r=v&15;if(s<1||s>4||r<2||r>14)throw new JSONException("非法牌值");suit=List.of("s","h","c","d").get(s-1);rank=r==14?"A":r==13?"K":r==12?"Q":r==11?"J":String.valueOf(r);}
            if(counts.merge(suit+":"+rank,1,Integer::sum)>limit(suit,rank,mode))throw new JSONException("手牌不符合所选模式牌库");
            result.put(new JSONObject().put("suit",suit).put("rank",rank));
        }
        return result;
    }
}
