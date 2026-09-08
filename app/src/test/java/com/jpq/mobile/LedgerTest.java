package com.jpq.mobile;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class LedgerTest {
    Ledger create(){return new Ledger(Map.of("3",4,"Q",4,"K",4,"A",3,"2",1),Map.of("me",16,"left",16,"right",16),3000,400,400,2,2);}
    List<Ledger.Observation> cards(String rank){return List.of(new Ledger.Observation("left","left","左方",rank.isEmpty()?List.of():Arrays.asList(rank.split(" ")),false));}
    void start(Ledger l){l.frame(0,true,false,List.of());l.frame(200,true,false,List.of());}
    @Test public void onlyCountsOnceAfterProtection(){Ledger l=create();start(l);for(int i=2;i<30;i++)l.frame(i*200,true,false,cards("3 3"));assertEquals(1,l.events.size());assertEquals(2,(int)l.remaining().get("3"));}
    @Test public void endAtDeadlineHasPriority(){Ledger l=create();start(l);l.frame(400,false,false,cards("3"));l.frame(600,false,false,cards("3"));l.frame(3600,true,true,cards("K"));assertEquals(Ledger.Status.REVIEW,l.events.get(0).status);assertEquals(4,(int)l.remaining().get("3"));assertEquals(Ledger.Phase.ENDED,l.phase);}
    @Test public void cannotRestartFromResidualHand(){Ledger l=create();start(l);l.frame(400,true,true,cards("K"));for(int i=3;i<20;i++)l.frame(i*200,true,false,cards("K"));assertEquals(Ledger.Phase.ENDED,l.phase);assertTrue(l.events.isEmpty());}
    @Test public void clearThenStartCreatesNextRound(){Ledger l=create();start(l);l.frame(400,true,true,cards("K"));l.frame(600,false,false,List.of());l.frame(1000,false,false,List.of());l.frame(1200,true,false,List.of());assertEquals(Ledger.Phase.ENDED,l.phase);l.frame(1400,true,false,List.of());assertEquals(2,l.round);assertEquals(Ledger.Phase.PLAYING,l.phase);}
    @Test public void disappearanceDoesNotUndoOrLoseNormalShortPlay(){Ledger l=create();start(l);l.frame(400,false,false,cards("3"));l.frame(600,false,false,cards("3"));l.frame(800,false,false,cards(""));l.frame(4000,false,false,cards(""));assertEquals(3,(int)l.remaining().get("3"));}
    @Test public void repeatAfterDisappearanceIsNotAutomaticallyDebited(){Ledger l=create();start(l);l.frame(400,false,false,cards("3"));l.frame(600,false,false,cards("3"));l.frame(3600,false,false,cards(""));l.frame(4000,false,false,cards(""));l.frame(4200,false,false,cards("3"));l.frame(4400,false,false,cards("3"));l.frame(8000,false,false,cards("3"));assertEquals(2,l.events.size());assertEquals(Ledger.Status.REVIEW,l.events.get(1).status);assertEquals(3,(int)l.remaining().get("3"));}
    @Test public void countsAreAtomicAndNeverNegative(){Ledger l=create();start(l);l.frame(400,false,false,cards("2 2 3"));l.frame(600,false,false,cards("2 2 3"));l.frame(4000,false,false,cards("2 2 3"));assertEquals(Ledger.Status.REVIEW,l.events.get(0).status);assertEquals(4,(int)l.remaining().get("3"));assertEquals(1,(int)l.remaining().get("2"));assertFalse(l.confirm(1));}
    @Test public void manuallyConfirmAndUndoCannotDoubleDebit(){Ledger l=create();start(l);l.frame(400,false,false,cards("3"));l.frame(600,false,false,cards("3"));l.interrupt();assertTrue(l.confirm(1));assertFalse(l.confirm(1));assertEquals(3,(int)l.remaining().get("3"));l.undo();assertEquals(4,(int)l.remaining().get("3"));assertTrue(l.confirm(1));}
    @Test public void oldRoundCannotBeAppliedToNewRound(){Ledger l=create();start(l);l.frame(400,false,false,cards("3"));l.frame(600,false,false,cards("3"));l.newRound();assertFalse(l.confirm(1));assertEquals(4,(int)l.remaining().get("3"));}
    @Test public void ambiguityDoesNotProducePartialPlay(){Ledger l=create();start(l);List<Ledger.Observation> frame=List.of(new Ledger.Observation("left","left","左方",List.of("3","Q"),true));for(int i=2;i<10;i++)l.frame(i*200,false,false,frame);assertTrue(l.events.isEmpty());}
    @Test public void excessiveSeatTotalIsHeld(){Ledger l=new Ledger(Map.of("3",4,"Q",4),Map.of("left",2),3000,400,400,1,2);start(l);l.frame(400,false,false,cards("3 3 Q"));l.frame(4000,false,false,cards("3 3 Q"));assertEquals(Ledger.Status.REVIEW,l.events.get(0).status);assertEquals(4,(int)l.remaining().get("Q"));}
}
