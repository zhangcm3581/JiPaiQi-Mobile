package com.jpq.mobile;

import org.json.*;
import java.util.*;
import java.util.function.Consumer;

/** Single-worker state machine. Visual evidence never inherits a server version change. */
public final class RoundSession {
    public final String tenant,client;
    private final Consumer<String> save;
    private final LinkedHashMap<String,JSONObject> pending=new LinkedHashMap<>();
    private int known,target,bound,starts,ends,handStreak,waitingHandStreak,waitingTarget,closedThrough;
    private boolean online,enabled,receivedState,closedState,armed,joining,ended,blocked,submitted,started,waitingStart;
    private long clearSince=-1,absentSince=-1,lastFrame=-1;
    private String candidate="",startEvent="",waitingCandidate="";
    private JSONArray stableCards,waitingCards;
    public volatile boolean panel;
    public volatile HandReply result;
    public volatile String status="等待服务器与发牌前画面";
    public volatile int round;

    public RoundSession(String tenant,String client,String restored,Consumer<String> save)throws JSONException {
        if(!tenant.matches("[0-9]{1,32}")||!client.matches("[A-Za-z0-9_-]{1,100}"))throw new IllegalArgumentException("租户或设备ID无效");
        this.tenant=tenant;this.client=client;this.save=save;
        if(restored!=null&&!restored.isEmpty()) {
            JSONObject p=new JSONObject(restored);
            if(!tenant.equals(p.getString("tenant"))||!client.equals(p.getString("client")))throw new JSONException("恢复记录身份不一致");
            target=p.optInt("target");bound=p.optInt("bound");round=target;started=p.optBoolean("started");ended=p.optBoolean("ended");joining=p.optBoolean("joining");submitted=p.optBoolean("submitted");startEvent=p.optString("start_event");
            JSONArray requests=p.optJSONArray("pending");if(requests!=null)for(int i=0;i<requests.length();i++){JSONObject m=requests.getJSONObject(i);pending.put(m.getString("request_id"),m);}
            // Process loss destroys continuity. Keep original requests for audit, never infer a new round.
            blocked=started||!pending.isEmpty();if(blocked)status="采集中断：轮次待同步，请在局间重新登记";
        }
    }
    private void persist(){try{save.accept(new JSONObject().put("tenant",tenant).put("client",client).put("target",target).put("bound",bound).put("started",started).put("ended",ended).put("joining",joining).put("submitted",submitted).put("start_event",startEvent).put("pending",new JSONArray(pending.values())).toString());}catch(Exception e){blocked=true;panel=false;status="无法保存轮次，停止上传";throw new IllegalStateException(e);}}
    public JSONObject message(String type,Integer version,JSONObject payload)throws JSONException{return new JSONObject().put("protocol_version",1).put("type",type).put("request_id",UUID.randomUUID().toString()).put("tenant_id",tenant).put("client_id",client).put("round_version",version==null?JSONObject.NULL:version).put("payload",payload);}
    private void enqueue(String type,int version,JSONObject payload)throws JSONException{JSONObject m=message(type,version,payload);pending.put(m.getString("request_id"),m);persist();}
    public List<String> outgoing(){if(!online||!receivedState||!enabled||blocked)return List.of();List<String> out=new ArrayList<>();for(JSONObject m:pending.values())out.add(m.toString());return out;}
    public void connection(boolean value){online=value;receivedState=false;if(!value&&!blocked)status="连接中断，保留本局请求";}
    public boolean needsHands(){return !blocked&&(!started||ended||(!submitted&&stableCards==null));}
    private void clearWaiting(){waitingTarget=0;waitingStart=false;waitingCards=null;waitingCandidate="";waitingHandStreak=0;}
    public void gap(){armed=false;clearSince=absentSince=lastFrame=-1;starts=ends=handStreak=0;candidate="";clearWaiting();panel=false;}
    private void fail(String text){blocked=true;result=null;status=text;persist();}
    public void registrationFailed(String text){fail(text);}
    public void frame(long now,boolean start,boolean end,List<String> cards)throws JSONException {
        if(lastFrame>=0&&now-lastFrame>1500)gap();lastFrame=now;
        // Visibility belongs to the local game phase, even when network synchronization waits.
        if(end){
            // Even an unconfirmed end candidate interrupts consecutive hand evidence.
            handStreak=waitingHandStreak=0;candidate=waitingCandidate="";
            ends++;starts=0;clearSince=absentSince=-1;armed=false;
            if(ends>=2){
                panel=false;result=null;clearWaiting();
                if(!blocked&&started&&!ended){ended=true;stableCards=null;status="本局结束，等待下一局";if(bound==target)enqueue("round.end",target,new JSONObject());persist();}
            }
            return;
        }
        ends=0;
        if(!start){
            handStreak=waitingHandStreak=0;candidate=waitingCandidate="";starts=0;
            if(absentSince<0)absentSince=now;if(now-absentSince>=600)panel=false;
            if((!started||ended)&&!waitingStart){if(clearSince<0)clearSince=now;if(now-clearSince>=600)armed=true;}
            return;
        }
        clearSince=-1;absentSince=-1;starts++;
        if(starts>=2)panel=true;
        if(blocked)return;
        if((!started||ended)&&!waitingStart&&armed&&starts>=2){armed=false;waitingStart=true;waitingTarget=started?target+1:known;waitingCards=null;waitingCandidate="";waitingHandStreak=0;}
        if(waitingStart){
            if(waitingCards==null){JSONArray valid=identities(cards);if(valid==null){waitingHandStreak=0;waitingCandidate="";}else{String sig=valid.toString();waitingHandStreak=sig.equals(waitingCandidate)?waitingHandStreak+1:1;waitingCandidate=sig;if(waitingHandStreak>=2)waitingCards=valid;}}
            tryBegin();return;
        }
        if(!started||ended||!enabled||closedState||known!=target||closedThrough>=target)return;
        if(submitted||stableCards!=null)return;
        JSONArray valid=identities(cards);
        if(valid==null){handStreak=0;candidate="";return;}
        String sig=valid.toString();handStreak=sig.equals(candidate)?handStreak+1:1;candidate=sig;
        if(handStreak>=2){stableCards=valid;if(!joining&&bound==target)submit();}
    }
    private void tryBegin()throws JSONException {
        if(!waitingStart||blocked)return;
        if(!online||!receivedState||!enabled||closedState||known<1){status="已识别开局，等待服务器同步";return;}
        int next=waitingTarget;
        if(next<1){status="等待服务器版本及下一次可靠开局";return;}
        if(known>next){fail("轮次不同步，旧手牌不能用于新版本");return;}
        // A join may be accepted remotely while its ack is still being retried locally.
        // Resolve that original binding before testing the previous-version chain.
        if(joining){status="已识别下一局，等待上一局绑定确认";return;}
        if(started&&(bound<1||next!=bound+1)){fail("轮次不同步，停止上传");return;}
        if(known<next||!pending.isEmpty()){status="已识别下一局，等待上一局确认";return;}
        Integer previous=started?bound:null;
        target=next;round=target;joining=true;ended=false;started=true;submitted=false;result=null;
        stableCards=waitingCards;candidate=waitingCandidate;handStreak=waitingHandStreak;clearWaiting();
        startEvent=UUID.randomUUID().toString();
        enqueue("round.join",target,new JSONObject().put("start_event_id",startEvent).put("sync_basis",previous==null?"initial_start":"end_then_start").put("previous_round_version",previous==null?JSONObject.NULL:previous));
        status="新局绑定中";
    }
    public static JSONArray identities(List<String> cards)throws JSONException {
        if(cards.size()!=13)return null;TreeMap<String,Integer> counts=new TreeMap<>();
        for(String token:cards){String[] a=token.split(":",-1);if(a.length!=2||!HandSnapshots.SUITS.contains(a[0])||!HandSnapshots.RANKS.contains(a[1]))return null;counts.merge(token,1,Integer::sum);if(counts.get(token)>2)return null;}
        JSONArray out=new JSONArray();for(var e:counts.entrySet()){String[] a=e.getKey().split(":");String suit=List.of("s","h","c","d").get(HandSnapshots.SUITS.indexOf(a[0]));for(int i=0;i<e.getValue();i++)out.put(new JSONObject().put("rank",a[1]).put("suit",suit));}return out;
    }
    private void submit()throws JSONException{if(ended||submitted||stableCards==null||known!=target||closedState||closedThrough>=target)return;submitted=true;enqueue("hand.submit",target,new JSONObject().put("cards",stableCards));status="手牌已确认，等待服务器";}
    public void receive(JSONObject m)throws JSONException {
        if(m.optInt("protocol_version")!=1||!tenant.equals(m.optString("tenant_id")))return;
        String type=m.optString("type");JSONObject p=m.getJSONObject("payload");int version=m.optInt("round_version");
        if(type.equals("round.state")){
            if(known==0&&!started){
                // Before the first server snapshot, visual evidence has no trustworthy version.
                // Keep the local panel visible, but require a fresh pre-deal -> start transition.
                armed=false;clearSince=-1;clearWaiting();
            }
            known=version;receivedState=true;enabled=p.optBoolean("enabled");
            if(blocked&&enabled&&p.has("bound_round_version")&&p.isNull("bound_round_version")){
                // Server confirms no binding after administrator re-registration. Keep device id,
                // discard lost visual evidence and require a fresh pre-deal -> start transition.
                blocked=false;started=ended=joining=submitted=false;target=bound=round=0;
                pending.clear();stableCards=null;result=null;startEvent="";gap();persist();
            }
            closedState=p.optString("state").equals("closed");
            if(!enabled){result=null;status="租户已停用";return;}
            if(closedState){closedThrough=Math.max(closedThrough,version);result=null;status="本轮已关闭，等待本机结束与新局";return;}
            if(!blocked&&started&&!ended&&target!=known){status="服务端已换轮，等待本机真实结束";result=null;}
            else if(!blocked&&!started)status="连接成功，请等待发牌前→开局";
            tryBegin();return;
        }
        String reply=m.optString("reply_to");JSONObject request=pending.get(reply);
        if(type.equals("ack")&&request!=null){
            String action=request.getString("type");if(!action.equals(p.optString("action"))||version!=request.getInt("round_version"))return;
            if(action.equals("round.join")){
                if(p.optInt("bound_round_version")!=target)return;
                bound=target;joining=false;pending.remove(reply);persist();
                if(ended)enqueue("round.end",target,new JSONObject());else submit();
            }else{pending.remove(reply);persist();if(action.equals("hand.submit"))status="手牌已上报，等待其他设备";}
        }else if(type.equals("error")){
            String code=p.optString("code");
            if(request!=null&&code.equals("ROUND_CLOSED")&&(request.optString("type").equals("round.end")||(request.optString("type").equals("hand.submit")&&bound==request.optInt("round_version")))){closedThrough=Math.max(closedThrough,request.optInt("round_version"));result=null;pending.remove(reply);persist();tryBegin();return;}
            if(request!=null||Set.of("SYNC_REQUIRED","TENANT_NOT_FOUND","TENANT_DISABLED","CLIENT_LIMIT","CLIENT_ID_IN_USE").contains(code))fail(code+"："+p.optString("message"));
        }else if(type.equals("round.result")&&enabled&&!closedState&&!blocked&&started&&!ended&&bound==target&&version==bound&&known==bound&&version>closedThrough){
            result=serverResult(p,version);status="服务器结果 · 版本 "+version;
        }
        tryBegin();
    }
    static HandReply serverResult(JSONObject p,int version)throws JSONException {
        if(p.getInt("remaining_count")!=13)throw new JSONException("结果数量错误");JSONArray cards=p.getJSONArray("cards"),out=new JSONArray();int total=0;
        for(int i=0;i<cards.length();i++){JSONObject c=cards.getJSONObject(i);int suit=List.of("s","h","c","d").indexOf(c.getString("suit"));if(suit<0)throw new JSONException("结果花色错误");total+=c.getInt("count");out.put(new JSONObject().put("suit",HandSnapshots.SUITS.get(suit)).put("rank",c.getString("rank")).put("count",c.get("count")));}
        if(total!=13)throw new JSONException("结果总数错误");return new HandReply(new JSONObject().put("schema","jpq.hand-result/1").put("round_id",version).put("event_id","ws-"+version).put("cards",out));
    }
}
