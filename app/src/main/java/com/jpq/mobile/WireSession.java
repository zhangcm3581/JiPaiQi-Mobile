package com.jpq.mobile;
import org.json.*;
import java.util.List;
interface WireSession {
    default void audit(String direction,String message){}
    String tenantId(); String clientId();
    JSONObject message(String type,Integer version,JSONObject payload) throws JSONException;
    List<String> outgoing();
    void connection(boolean online);
    void receive(JSONObject message) throws JSONException;
    void registrationFailed(String text);
    void networkStatus(String text);
}
