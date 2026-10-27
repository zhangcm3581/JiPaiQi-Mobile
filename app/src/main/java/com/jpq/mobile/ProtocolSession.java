package com.jpq.mobile;

import org.json.*;
import java.util.*;
import java.util.function.Consumer;

/** Worker-owned state machine. A game event keeps its original server version on retries. */
public final class ProtocolSession implements WireSession {
    public final String tenant,client,mode;
    private final Consumer<String> save;
    java.util.function.BiConsumer<String,String> trace=(direction,message)->{};
    public void audit(String direction,String message){trace.accept(direction,message);}
    private final LinkedHashMap<String,JSONObject> pending=new LinkedHashMap<>();
    private final LinkedHashSet<String> retired=new LinkedHashSet<>();
    private int known,target;
    private boolean online,synced,enabled,submitted,comparing,ended=true,blocked;
    private String gameKey="",event="";
    private JSONArray waiting;
    public boolean panel;
    public HandReply result;
    public String status="等待启动";
    public ProtocolSession(String tenant,String client,String restored,Consumer<String> save)throws JSONException {
        this(tenant,client,restored,save,ProtocolDeck.STANDARD);
    }
    public ProtocolSession(String tenant,String client,String restored,Consumer<String> save,String mode)throws JSONException {
        if(!ProtocolDeck.valid(mode))throw new JSONException("未知模式");
        this.mode=mode;this.tenant=tenant;this.client=client;this.save=save;
        if(restored!=null&&!restored.isEmpty()){
            JSONObject state=new JSONObject(restored);
            if(!tenant.equals(state.getString("tenant"))||!client.equals(state.getString("client")))throw new JSONException("identity mismatch");
            JSONArray old=state.optJSONArray("retired");if(old!=null)for(int i=0;i<old.length();i++)retired.add(old.getString(i));
            // Process restart/pause discards pending observations: wait for a NEW live deal.
            String active=state.optString("game_key");if(!active.isEmpty())retired.add(active);
        }
    }
    public String tenantId(){return tenant;} public String clientId(){return client;}
    public void networkStatus(String text){status=text;}
    public void registrationFailed(String text){blocked=true;result=null;panel=false;status=text;}
    public JSONObject message(String type,Integer version,JSONObject payload)throws JSONException {
        return new JSONObject().put("protocol_version",1).put("type",type).put("tenant_id",tenant).put("client_id",client)
            .put("request_id",UUID.randomUUID().toString()).put("round_version",version==null?JSONObject.NULL:version).put("payload",payload);
    }
    private void persist()throws JSONException {
        while(retired.size()>128)retired.remove(retired.iterator().next());
        save.accept(new JSONObject().put("tenant",tenant).put("client",client).put("game_key",gameKey).put("retired",new JSONArray(retired)).toString());
    }
    private void queue(String type,JSONObject payload)throws JSONException {
        JSONObject request=message(type,target,payload);pending.put(request.getString("request_id"),request);
    }
    public List<String> outgoing(){
        if(!online||!synced||!enabled||blocked)return List.of();
        List<String> values=new ArrayList<>();for(JSONObject request:pending.values())values.add(request.toString());return values;
    }
    public void connection(boolean value){online=value;synced=false;panel=false;if(!value&&!blocked)status="连接中断，等待同步";}
    public static JSONArray decode(JSONArray values)throws JSONException {return ProtocolDeck.decode(values,ProtocolDeck.STANDARD);}
    public void game(JSONObject observation)throws JSONException {
        String kind=observation.getString("event"),key=observation.optString("game_key");
        if(kind.equals("deal")){
            JSONArray cards=ProtocolDeck.decode(observation.getJSONArray("cards"),mode);
            if(!key.matches("[A-Za-z0-9_-]+:[1-9][0-9]*"))throw new JSONException("缺少本地发牌标识");
            if(retired.contains(key)||key.equals(gameKey))return;
            if(known==0){retired.add(key);persist();hide();status="首次同步尚未完成，本次发牌不提交，请同步后进入新局";return;}
            if(!gameKey.isEmpty())retired.add(gameKey);
            // Never repurpose unresolved requests from a previous local game.
            int next = !gameKey.isEmpty() && submitted ? Math.max(known,target+1) : known;
            pending.entrySet().removeIf(e->!e.getValue().optString("type").equals("round.end"));
            gameKey=key;event=UUID.randomUUID().toString();waiting=cards;target=next;
            ended=comparing=submitted=false;result=null;panel=false;persist();
            submit();
        }else if(key.equals(gameKey)&&!gameKey.isEmpty()){
            if(kind.equals("compare")&&!ended&&!comparing){comparing=true;hide();if(submitted)queue("round.compare",new JSONObject());}
            if((kind.equals("end")||kind.equals("settled"))&&!ended){ended=true;hide();waiting=null;if(submitted)queue("round.end",new JSONObject());persist();}
        }
    }
    private void hide(){panel=false;result=null;}
    private void submit()throws JSONException {
        if(waiting==null||submitted||ended||comparing||blocked||!online||!synced||!enabled)return;
        if(target==0)target=known;
        if(target>known){status="已收到新局手牌，等待上一局结束确认";return;}
        if(target!=known){waiting=null;hide();status="发牌所属版本已结束，等待下一次发牌";return;}
        JSONObject payload=new JSONObject().put("start_event_id",event).put("cards",waiting);
        if(!ProtocolDeck.STANDARD.equals(mode))payload.put("mode",mode);
        queue("hand.submit",payload);
        submitted=true;waiting=null;status="13张手牌已发送，等待确认";
    }
    public void receive(JSONObject message)throws JSONException {
        if(message.optInt("protocol_version")!=1||!tenant.equals(message.optString("tenant_id")))return;
        String type=message.optString("type");int version=message.optInt("round_version");JSONObject data=message.getJSONObject("payload");
        if(type.equals("error")&&Set.of("TENANT_NOT_FOUND","TENANT_DISABLED","CLIENT_LIMIT").contains(data.optString("code"))){registrationFailed(data.optString("code")+"："+data.optString("message"));return;}
        if(type.equals("round.state")){
            if(version<known)return;
            JSONArray capabilities=data.optJSONArray("capabilities");
            Set<String> supported=new HashSet<>();if(capabilities!=null)for(int i=0;i<capabilities.length();i++)supported.add(capabilities.optString(i));
            if(!supported.contains("round.phase/1")||!supported.contains("game_key/1")){
                registrationFailed("服务器尚未支持协议版，请先更新服务端");return;
            }
            if((!ProtocolDeck.STANDARD.equals(mode)&&!supported.contains("deck.mode/1"))||(ProtocolDeck.TWO.equals(mode)&&!supported.contains("deck.two-jokers/1"))){
                registrationFailed("服务器尚未支持所选有王模式（2王/六王），请先更新服务端");return;
            }
            known=version;synced=true;enabled=data.optBoolean("enabled");
            pending.entrySet().removeIf(e->e.getValue().optInt("round_version")<known);
            if(target>0&&version>target){pending.clear();waiting=null;ended=true;hide();status="服务器已结束本局，等待新发牌";}
            if(!enabled||data.optString("state").equals("closed")){hide();return;}
            if(version==target&&data.optString("phase").equals("comparing")){comparing=true;hide();}
            submit();return;
        }
        if(type.equals("round.phase")){
            if(version!=target||ended)return;
            String phase=data.optString("phase");
            if(phase.equals("comparing")){comparing=true;hide();status="比牌阶段，面板已隐藏";}
            if(phase.equals("ended")){ended=true;waiting=null;pending.clear();hide();status="本局已结束";}
            return;
        }
        if(type.equals("round.result")){
            if(online&&synced&&enabled&&!blocked&&!ended&&!comparing&&submitted&&version==target&&version==known
                &&client.equals(data.optString("client_id"))&&event.equals(data.optString("start_event_id"))){
                if(!mode.equals(data.optString("mode",ProtocolDeck.STANDARD)))return;
                result=RoundSession.serverResult(data,version);panel=true;status="服务器结果";
            }return;
        }
        JSONObject request=pending.get(message.optString("reply_to"));if(request==null)return;
        if(type.equals("ack")&&version==request.optInt("round_version")&&request.optString("type").equals(data.optString("action"))){
            pending.remove(message.optString("reply_to"));status="服务器已确认";submit();
        }else if(type.equals("error")){
            String code=data.optString("code");
            if(code.equals("ROUND_CLOSED")){pending.remove(message.optString("reply_to"));if(version>=target){pending.clear();waiting=null;ended=true;hide();status="旧轮已关闭，等待下一次发牌";}}
            else registrationFailed(code+"："+data.optString("message"));
        }
    }
}
