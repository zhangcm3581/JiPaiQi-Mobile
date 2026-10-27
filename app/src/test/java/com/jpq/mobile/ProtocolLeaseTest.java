package com.jpq.mobile;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.concurrent.atomic.*;
public class ProtocolLeaseTest {
 @Test public void concurrentReadsNeverSeeTruncatedLease()throws Exception{
  Path directory=Files.createTempDirectory("protocol-lease");java.io.File lease=directory.resolve("lease").toFile();
  RootProtocolCollector.atomicLease(lease,1791810000000L);AtomicBoolean finished=new AtomicBoolean();AtomicReference<Throwable> error=new AtomicReference<>();
  Thread writer=new Thread(()->{try{for(int i=0;i<2000;i++)RootProtocolCollector.atomicLease(lease,1791810000000L+i);}catch(Throwable e){error.set(e);}finally{finished.set(true);}});
  try{writer.start();int reads=0;while(!finished.get()){String value=new String(Files.readAllBytes(lease.toPath()));assertTrue(value,value.matches("[0-9]{13}"));reads++;}writer.join();assertNull(error.get());assertTrue(reads>0);RootProtocolCollector.atomicLease(lease,0);assertEquals("0",new String(Files.readAllBytes(lease.toPath())));}
  finally{writer.join();Files.deleteIfExists(lease.toPath());Files.deleteIfExists(directory.resolve("lease.new"));Files.delete(directory);}
 }
}
