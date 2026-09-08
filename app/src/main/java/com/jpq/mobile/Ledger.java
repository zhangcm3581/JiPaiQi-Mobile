package com.jpq.mobile;

import java.util.*;

/** No Android dependency: temporal gate, unique play candidates, atomic deck accounting. */
public final class Ledger {
    public enum Phase { WAITING, PLAYING, ENDED }
    public enum Status { PENDING, COUNTED, REVIEW, DISMISSED }
    public static final class Event {
        public final long id;public final int round;public final String region,seat,name;public final List<String> cards;public final long time;
        public Status status=Status.PENDING;public String reason="等待排除结算亮牌";
        Event(long id,int round,Observation o,long now){this.id=id;this.round=round;region=o.key;seat=o.seat;name=o.name;cards=List.copyOf(o.cards);time=now;}
        public String label(){return String.join(" ",cards);}
    }
    public static final class Observation {
        public final String key,seat,name;public final List<String> cards;public final boolean uncertain;
        public Observation(String key,String seat,String name,List<String> cards,boolean uncertain){this.key=key;this.seat=seat;this.name=name;this.cards=List.copyOf(cards);this.uncertain=uncertain;}
    }
    private static final class Region {
        String candidate="",stable="",emitted="";int count;long emptySince=-1;
    }
    private final Map<String,Integer> deck,hands;
    private final Map<String,Integer> used=new LinkedHashMap<>(),seatUsed=new HashMap<>();
    private final Map<String,Region> regions=new HashMap<>();
    public final List<Event> events=new ArrayList<>();
    public Phase phase=Phase.WAITING;public int round=0;
    public final long holdMs,clearMs,emptyMs;private final int confirm,restartConfirm;
    private long clearSince=-1,nextId=1,lastTime=-1;private boolean armed=false;private int startCount=0;
    public Ledger(Map<String,Integer> deck,Map<String,Integer> hands,long holdMs,long clearMs,long emptyMs,int confirm,int restartConfirm){
        this.deck=new LinkedHashMap<>(deck);this.hands=new LinkedHashMap<>(hands);this.holdMs=holdMs;this.clearMs=clearMs;this.emptyMs=emptyMs;this.confirm=confirm;this.restartConfirm=restartConfirm;
    }
    public synchronized void frame(long time,boolean start,boolean end,List<Observation> observations){
        if(lastTime>=0&&time<lastTime)throw new IllegalArgumentException("时间戳倒退");lastTime=time;
        if(end){pendingToReview("临近结算：最后一手或剩余牌展示，需核对");phase=Phase.ENDED;startCount=0;regions.clear();}
        if(phase==Phase.ENDED){
            if(!start){if(clearSince<0)clearSince=time;if(time-clearSince>=clearMs)armed=true;}else clearSince=-1;
            startCount=!end&&start&&armed?startCount+1:0;
            if(startCount>=restartConfirm)newRound();
        }else if(phase==Phase.WAITING){startCount=start&&!end?startCount+1:0;if(startCount>=restartConfirm)newRound();}
        if(phase!=Phase.PLAYING)return;
        // End checked first: candidates expiring on this frame must not slip into the ledger.
        for(Event e:events)if(e.status==Status.PENDING&&time-e.time>=holdMs)commit(e);
        for(Observation o:observations){
            Region r=regions.computeIfAbsent(o.key,k->new Region());String value=String.join(" ",o.cards);
            if(o.uncertain){r.count=0;r.candidate="";continue;}
            if(value.isEmpty()){
                r.count=0;r.candidate="";if(r.emptySince<0)r.emptySince=time;
                if(time-r.emptySince>=emptyMs){r.stable="";r.emitted="";}
                continue;
            }
            r.emptySince=-1;if(value.equals(r.candidate))r.count++;else{r.candidate=value;r.count=1;}
            if(r.count>=confirm)r.stable=value;
            if(!r.stable.equals(value)||r.emitted.equals(value))continue;
            r.emitted=value;Event event=new Event(nextId++,round,o,time);
            // Identical reappearance is ambiguous without turn/hand-count evidence. Never double debit automatically.
            for(Event old:events)if(old.round==round&&old.region.equals(o.key)&&sameCards(old.cards,o.cards)&&old.status!=Status.DISMISSED){event.status=Status.REVIEW;event.reason="同牌组再次出现，可能是重复显示，请核对";break;}
            events.add(event);
        }
    }
    private static boolean sameCards(List<String>a,List<String>b){return counts(a).equals(counts(b));}
    private static Map<String,Integer> counts(List<String> values){Map<String,Integer> m=new HashMap<>();for(String s:values)m.merge(s,1,Integer::sum);return m;}
    private void commit(Event e){
        if(e.round!=round){e.status=Status.REVIEW;e.reason="上一局记录，不自动计入当前局";return;}
        String error=capacityError(e);
        if(error!=null){e.status=Status.REVIEW;e.reason=error;return;}
        for(String s:e.cards)used.merge(s,1,Integer::sum);seatUsed.merge(e.seat,e.cards.size(),Integer::sum);e.status=Status.COUNTED;e.reason="已计入";
    }
    private String capacityError(Event e){
        if(!hands.containsKey(e.seat)||seatUsed.getOrDefault(e.seat,0)+e.cards.size()>hands.get(e.seat))return "超过该玩家初始手牌数，请核对";
        for(Map.Entry<String,Integer>x:counts(e.cards).entrySet())if(!deck.containsKey(x.getKey())||used.getOrDefault(x.getKey(),0)+x.getValue()>deck.get(x.getKey()))return "超过实际牌库数量，请核对";
        return null;
    }
    public synchronized boolean confirm(long id){for(Event e:events)if(e.id==id&&e.status==Status.REVIEW&&e.round==round){commit(e);return e.status==Status.COUNTED;}return false;}
    public synchronized void dismiss(long id){for(Event e:events)if(e.id==id&&e.status==Status.REVIEW){e.status=Status.DISMISSED;e.reason="已忽略";}}
    public synchronized void undo(){for(int i=events.size()-1;i>=0;i--){Event e=events.get(i);if(e.round==round&&e.status==Status.COUNTED){for(String s:e.cards)used.merge(s,-1,Integer::sum);seatUsed.merge(e.seat,-e.cards.size(),Integer::sum);e.status=Status.REVIEW;e.reason="已撤销，可重新核对";return;}}}
    public synchronized void interrupt(){pendingToReview("识别中断，观察期不足");regions.clear();}
    public synchronized void newRound(){pendingToReview("新局开始，旧候选待核对");round++;phase=Phase.PLAYING;used.clear();seatUsed.clear();regions.clear();armed=false;clearSince=-1;startCount=0;}
    private void pendingToReview(String reason){for(Event e:events)if(e.status==Status.PENDING){e.status=Status.REVIEW;e.reason=reason;}}
    public synchronized LinkedHashMap<String,Integer> remaining(){LinkedHashMap<String,Integer> r=new LinkedHashMap<>();for(String s:deck.keySet())r.put(s,deck.get(s)-used.getOrDefault(s,0));return r;}
    public synchronized int reviews(){return (int)events.stream().filter(e->e.status==Status.REVIEW&&e.round==round).count();}
    public synchronized List<Event> history(){return new ArrayList<>(events);}
}
