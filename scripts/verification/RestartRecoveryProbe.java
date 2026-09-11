package com.jpq.mobile;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Isolated real-server probe. Run seed and restore in separate JVMs/classes. */
public class RestartRecoveryProbe {
    static final HttpClient HTTP=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final List<Peer> peers=new ArrayList<>();
    static String endpoint,tenant;
    static final List<String> deck=new ArrayList<>();
    static class Peer implements WebSocket.Listener {
        final Queue<String> incoming=new ConcurrentLinkedQueue<>();
        final Set<String> sent=new HashSet<>();
        final StringBuilder chunk=new StringBuilder();
        final RoundSession session;WebSocket socket;boolean state;
        Peer(int i,Path saved,boolean restore)throws Exception {
            session=new RoundSession(tenant,"test_"+i,restore?Files.readString(saved):"",value->{try{Files.writeString(saved,value);}catch(Exception e){throw new RuntimeException(e);}});
            session.connection(true);
            socket=HTTP.newWebSocketBuilder().buildAsync(URI.create(endpoint.replace("http:","ws:")+"/ws/client?tenant_id="+tenant+"&client_id=test_"+i),this).join();
        }
        public void onOpen(WebSocket s){s.request(1);}
        public CompletionStage<?> onText(WebSocket s,CharSequence data,boolean last){chunk.append(data);if(last){incoming.add(chunk.toString());chunk.setLength(0);}s.request(1);return null;}
        void tick()throws Exception {
            String value;while((value=incoming.poll())!=null){JSONObject msg=new JSONObject(value);if(msg.getString("type").equals("error")&&!msg.getJSONObject("payload").optString("code").equals("ROUND_CLOSED"))throw new AssertionError(msg);session.receive(msg);if(msg.getString("type").equals("round.state"))state=true;}
            for(String msg:session.outgoing())if(sent.add(new JSONObject(msg).getString("request_id")))socket.sendText(msg,true).join();
        }
    }
    static void until(java.util.function.BooleanSupplier condition)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        do{for(Peer p:peers)p.tick();if(condition.getAsBoolean())return;Thread.sleep(5);}while(System.nanoTime()<end);
        throw new AssertionError("timeout: "+peers.stream().map(p->p.session.status).toList());
    }
    static void frame(long time,boolean start,boolean end,boolean hands)throws Exception {
        for(int i=0;i<peers.size();i++)peers.get(i).session.frame(time,start,end,hands?deck.subList(i*13,(i+1)*13):List.of());
    }
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void roster(Path saved)throws Exception {
        int[][] groups={{0,1,2,3,4,5,6},{0,1,2,3,4,5,11},{0,1,2,3,4,5,11},{0,1,2,3,4,5,6}};
        try {
            for(int i=0;i<20;i++)peers.add(new Peer(i,saved.resolve(i+".json"),false));
            until(()->peers.stream().allMatch(p->p.state));
            for(int r=0;r<groups.length;r++){
                int[] group=groups[r];int version=7+r;long base=r*5000L;
                for(int slot=0;slot<7;slot++){
                    RoundSession s=peers.get(group[slot]).session;
                    s.frame(base,false,false,List.of());s.frame(base+600,false,false,List.of());
                    s.frame(base+800,true,false,deck.subList(slot*13,(slot+1)*13));s.frame(base+1000,true,false,deck.subList(slot*13,(slot+1)*13));
                }
                until(()->Arrays.stream(group).allMatch(i->peers.get(i).session.result!=null&&peers.get(i).session.result.round==version));
                for(int i:group){check(peers.get(i).session.result.totalCount==13,"13 results");check(peers.get(i).session.panel,"visible while arranging");
                    for(int row=0;row<8;row++)for(int col=0;col<13;col++){String token=HandSnapshots.SUITS.get(row/2)+":"+HandSnapshots.RANKS.get(col);check(peers.get(i).session.result.occupied(row,col)==(Collections.frequency(deck.subList(91,104),token)>row%2),"incorrect card");}
                }
                for(int i:group){RoundSession s=peers.get(i).session;s.frame(base+1200,false,true,List.of());s.frame(base+1400,false,true,List.of());check(!s.panel,"end hides panel");}
                until(()->Arrays.stream(group).allMatch(i->peers.get(i).session.outgoing().isEmpty()));
                System.out.println("PASS roster: round="+version+" clients="+Arrays.toString(Arrays.stream(group).map(i->i+1).toArray()));
            }
        }finally{for(Peer p:peers)p.socket.abort();}
    }
    public static void main(String[] args)throws Exception {
        endpoint=args[0];check(endpoint.startsWith("http://127.0.0.1:"),"isolated localhost only");tenant=args[1];Path saved=Path.of(args[2]);Files.createDirectories(saved);boolean restore=!List.of("seed","roster").contains(args[3]);
        for(String suit:HandSnapshots.SUITS)for(String rank:HandSnapshots.RANKS)for(int n=0;n<2;n++)deck.add(suit+":"+rank);
        if(!restore){HttpResponse<String> r=HTTP.send(HttpRequest.newBuilder(URI.create(endpoint+"/api/tenants")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(new JSONObject().put("tenant_id",tenant).put("round_version",7).toString())).build(),HttpResponse.BodyHandlers.ofString());check(r.statusCode()==201,r.body());}
        if(args[3].equals("roster")){roster(saved);return;}
        try {
            for(int i=0;i<7;i++)peers.add(new Peer(i,saved.resolve(i+".json"),restore&&!args[3].equals("empty")));
            until(()->peers.stream().allMatch(p->p.state));
            if(restore){
                frame(0,true,false,true);frame(200,true,false,true);
                for(Peer p:peers)check(p.session.outgoing().isEmpty(),"startup must not replay old requests or use mid-round cards");
                frame(400,false,true,false);frame(600,false,true,false);
            }
            frame(800,false,false,false);frame(1400,false,false,false);frame(1600,true,false,true);frame(1800,true,false,true);frame(2000,true,false,true);
            until(()->peers.stream().allMatch(p->p.session.result!=null));
            for(Peer p:peers){check(p.session.result.totalCount==13,"must return exactly 13");check(p.session.result.round==(restore?8:7),"wrong round");check(p.session.panel,"arranging panel must show");for(int row=0;row<8;row++)for(int col=0;col<13;col++){String token=HandSnapshots.SUITS.get(row/2)+":"+HandSnapshots.RANKS.get(col);check(p.session.result.occupied(row,col)==(Collections.frequency(deck.subList(91,104),token)>row%2),"incorrect result card");}}
            if(!restore){
                peers.get(0).session.frame(2200,false,true,List.of());peers.get(0).session.frame(2400,false,true,List.of());
                until(()->peers.stream().allMatch(p->p.session.result==null)&&peers.get(0).session.outgoing().isEmpty());
            }else{
                frame(2200,false,false,false);frame(2800,false,false,false);
                for(Peer p:peers)check(!p.session.panel,"comparison panel must hide");
                frame(3000,false,true,false);frame(3200,false,true,false);
                // One end advances the round; other ends receive ROUND_CLOSED, as normal.
                until(()->peers.stream().allMatch(p->p.session.outgoing().isEmpty()));
            }
            System.out.println("PASS "+args[3]+": seven clients, exact 13-card results, round "+(restore?8:7));
        }finally{for(Peer p:peers)p.socket.abort();}
    }
}
