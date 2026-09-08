package com.jpq.mobile;
/** Correlates responses to the current round and request, discarding stale responses. */
public final class HandResultState {
    private int round;private String event="";private boolean active;
    public volatile HandReply reply;
    public synchronized void begin(int value){if(value!=round){round=value;event="";reply=null;}active=true;}
    public synchronized void expect(String id){event=id;reply=null;}
    public synchronized void end(){active=false;reply=null;event="";}
    public synchronized boolean accept(HandReply result){if(!active||result.round!=round||!result.eventId.equals(event))return false;reply=result;return true;}
}
