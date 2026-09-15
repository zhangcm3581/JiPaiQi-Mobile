package com.jpq.mobile;
import org.junit.Test;
import static org.junit.Assert.*;
public class MissedEndDiagnosticTest {
 @Test public void compareNormalAndMissedEndAcrossTwoMoreGames() throws Exception {
  RoundSessionTest f=new RoundSessionTest();
  for(boolean miss:new boolean[]{false,true}) {
   RoundSession s=f.session();f.start(s,0);f.ack(s,f.request(s));
   if(!miss){f.end(s,1200);f.ack(s,f.request(s));}
   for(int v=2;v<=3;v++) {
    f.state(s,v);f.start(s,v*2000);
    System.out.println("missEnd="+miss+" round="+v+" requests="+s.outgoing().size()+" needsHands="+s.needsHands()+" status="+s.status);
    assertEquals(miss?0:1,s.outgoing().size());
    if(!miss){f.ack(s,f.request(s));f.end(s,v*2000+1200);f.ack(s,f.request(s));}
   }
   if(miss){f.end(s,8000);f.error(s,f.request(s),"ROUND_CLOSED");f.start(s,8400);assertEquals(3,f.request(s).getInt("round_version"));}
  }
 }
}
