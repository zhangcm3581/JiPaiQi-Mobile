package com.jpq.mobile;
import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
public class ProtocolGameWaitTest {
 @Test public void waitsBeyondOldTimeoutThenFindsGame()throws Exception{
  AtomicInteger polls=new AtomicInteger(),delays=new AtomicInteger();
  assertEquals("4432",RootProtocolCollector.waitForGame(()->polls.incrementAndGet()>20?"4432":"",()->false,()->delays.incrementAndGet()));
  assertEquals(20,delays.get());
 }
 @Test public void pauseStopsWaiting()throws Exception{
  AtomicInteger polls=new AtomicInteger();
  assertNull(RootProtocolCollector.waitForGame(()->{polls.incrementAndGet();return "";},()->polls.get()>=3,()->{}));
  assertEquals(3,polls.get());
 }
 @Test public void pauseDuringProbeDoesNotAttach()throws Exception{
  AtomicInteger stop=new AtomicInteger();
  assertNull(RootProtocolCollector.waitForGame(()->{stop.set(1);return "4432";},()->stop.get()==1,()->fail()));
 }
 @Test(expected=IOException.class) public void rootFailureIsNotMissingGame()throws Exception{
  RootProtocolCollector.waitForGame(()->{throw new IOException("Root denied");},()->false,()->fail());
 }
}
