package com.jpq.mobile;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
/** Localhost-only seven-producer regression, with one roster replacement and a missed end. */
public class MissedEndWireProbe extends RestartRecoveryProbe {
 public static void main(String[] args)throws Exception {
  endpoint=args[0];check(endpoint.startsWith("http://127.0.0.1:"),"local only");tenant=args[1];Path saved=Path.of(args[2]);Files.createDirectories(saved);
  HttpResponse<String> created=HTTP.send(HttpRequest.newBuilder(URI.create(endpoint+"/api/tenants")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(new JSONObject().put("tenant_id",tenant).put("round_version",7).toString())).build(),HttpResponse.BodyHandlers.ofString());check(created.statusCode()==201,created.body());
  for(String suit:HandSnapshots.SUITS)for(String rank:HandSnapshots.RANKS)for(int n=0;n<2;n++)deck.add(suit+":"+rank);
  try {
   for(int i=0;i<8;i++)peers.add(new Peer(i,saved.resolve(i+".json"),false));until(()->peers.stream().allMatch(p->p.state));
   for(int r=0;r<4;r++) {
    int version=7+r;int[] group=r==0?new int[]{0,1,2,3,4,5,6}:new int[]{0,1,2,3,4,5,7};long t=r*5000L;
    List<String> shuffled=new ArrayList<>(deck);Collections.rotate(shuffled,r*13);
    for(int slot=0;slot<7;slot++){RoundSession s=peers.get(group[slot]).session;s.frame(t,false,false,List.of());s.frame(t+600,false,false,List.of());List<String> h=shuffled.subList(slot*13,slot*13+13);s.frame(t+800,true,false,h);s.frame(t+1000,true,false,h);}
    until(()->Arrays.stream(group).allMatch(i->peers.get(i).session.result!=null&&peers.get(i).session.result.round==version));
    for(int i:group){HandReply result=peers.get(i).session.result;check(result.totalCount==13,"13 result cards");for(int row=0;row<8;row++)for(int col=0;col<13;col++){String token=HandSnapshots.SUITS.get(row/2)+":"+HandSnapshots.RANKS.get(col);check(result.occupied(row,col)==(Collections.frequency(shuffled.subList(91,104),token)>row%2),"exact result");}}
    System.out.println("PASS version="+version+" seven uploads and exact results; replaced="+(r>0)+" missed-end-client="+(r>=1?1:"none"));
    for(int i:group){if(r>=1&&i==1)continue;RoundSession s=peers.get(i).session;s.frame(t+1200,false,true,List.of());s.frame(t+1400,false,true,List.of());}
    until(()->Arrays.stream(group).allMatch(i->peers.get(i).session.result==null&&peers.get(i).session.outgoing().isEmpty()));
   }
  } finally {for(Peer p:peers)p.socket.abort();}
 }
}
