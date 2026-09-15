package com.jpq.mobile;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
/** Replays recorded native Vision output through seven real sessions and local WebSockets. */
public class VideoStateWireProbe extends RestartRecoveryProbe {
 public static void main(String[] args)throws Exception {
  endpoint=args[0];check(endpoint.startsWith("http://127.0.0.1:"),"local only");tenant=args[1];Path saved=Path.of(args[2]);Files.createDirectories(saved);
  JSONArray samples=new JSONObject(Files.readString(Path.of(args[3]))).getJSONArray("samples");
  HttpResponse<String> created=HTTP.send(HttpRequest.newBuilder(URI.create(endpoint+"/api/tenants")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(new JSONObject().put("tenant_id",tenant).toString())).build(),HttpResponse.BodyHandlers.ofString());check(created.statusCode()==201,created.body());
  for(String suit:HandSnapshots.SUITS)for(String rank:HandSnapshots.RANKS)for(int n=0;n<2;n++)deck.add(suit+":"+rank);
  Set<Integer> complete=new TreeSet<>();boolean previousEnd=false;String mode=args.length>4?args[4]:"normal";
  try {
   for(int i=0;i<7;i++)peers.add(new Peer(i,saved.resolve(i+".json"),false));until(()->peers.stream().allMatch(p->p.state));
   for(int j=0;j<samples.length();j++){
    JSONObject s=samples.getJSONObject(j);boolean start=s.getBoolean("start"),end=s.getBoolean("end");long time=s.getLong("time_ms");List<String> own=new ArrayList<>();for(Object card:s.getJSONArray("cards"))own.add(card.toString());List<String> remaining=new ArrayList<>(deck);for(String card:own)check(remaining.remove(card),"valid cards");
    for(int i=0;i<7;i++){
     // Process each client's network before the next device to expose asynchronous end ordering.
     for(Peer p:peers)p.tick();
     List<String> cards=own.size()==13?(i==0?own:remaining.subList((i-1)*13,i*13)):List.of();
     boolean localEnd=end&&!(mode.equals("miss-six")&&i>0);
     if((mode.equals("gap-six")&&i>0&&end)||(mode.equals("miss-boundary-six")&&i>0&&time>=48000&&(!start||end))){peers.get(i).session.gap();}else peers.get(i).session.frame(time,start,localEnd,cards);peers.get(i).tick();
     if(end)Thread.sleep(2);
    }
    Thread.sleep(2);for(Peer p:peers)p.tick();
    for(Peer p:peers)if(p.session.result!=null){check(p.session.result.totalCount==13,"13 result");complete.add(p.session.result.round);}
    if(end&&!previousEnd)System.out.println("END at="+time+" completed="+complete+" statuses="+peers.stream().map(p->p.session.status).toList());previousEnd=end;
   }
   check(complete.size()==8,"expected eight complete rounds, actual="+complete+" statuses="+peers.stream().map(p->p.session.status).toList());
   System.out.println("PASS eight rounds, native recorded phases and asynchronous seven-client wire; completed="+complete);
  } finally {for(Peer p:peers)p.socket.abort();}
 }
}
