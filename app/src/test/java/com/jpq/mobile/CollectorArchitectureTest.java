package com.jpq.mobile;
import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;
public class CollectorArchitectureTest {
 @Test public void ldPlayerUsesNativeCollectorAndEmulatedGame()throws Exception{
  CollectorArchitecture.verifyHost("x86_64",62);
  assertEquals("emulated",CollectorArchitecture.realm(62,183));
  assertEquals("emulated",CollectorArchitecture.realm(62,40));
 }
 @Test public void mumuArmRemainsNative()throws Exception{
  CollectorArchitecture.verifyHost("aarch64",183);
  assertEquals("native",CollectorArchitecture.realm(183,183));
 }
 @Test public void nativeX86GameRemainsNative()throws Exception{
  assertEquals("native",CollectorArchitecture.realm(62,62));
  assertEquals("native",CollectorArchitecture.realm(62,3));
 }
 @Test(expected=IOException.class) public void refuseArmExecutableOnX86()throws Exception{
  CollectorArchitecture.verifyHost("x86_64",183);
 }
 @Test public void elfHeaderDecodes()throws Exception{
  byte[] h=new byte[20];h[0]=0x7f;h[1]='E';h[2]='L';h[3]='F';h[5]=1;h[18]=(byte)183;
  assertEquals(183,CollectorArchitecture.machine(h));h[18]=62;assertEquals(62,CollectorArchitecture.machine(h));
 }
 @Test(expected=IOException.class) public void rejectMalformedElf()throws Exception{
  CollectorArchitecture.machine(new byte[20]);
 }
 @Test(expected=IOException.class) public void rejectUnsupportedPair()throws Exception{
  CollectorArchitecture.realm(183,62);
 }
}
