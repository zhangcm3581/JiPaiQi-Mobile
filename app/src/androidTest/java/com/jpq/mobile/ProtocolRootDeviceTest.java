package com.jpq.mobile;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class ProtocolRootDeviceTest {
 @Test public void apkStartsRootObserverWithoutComputerInjection()throws Exception{
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
  CountDownLatch ready=new CountDownLatch(1);AtomicReference<String> diagnostic=new AtomicReference<>("");
  RootProtocolCollector collector=new RootProtocolCollector(context,event->{if(event.optString("event").equals("ready"))ready.countDown();},diagnostic::set);
  try{collector.start();boolean ok=ready.await(25,TimeUnit.SECONDS);assertTrue("Observer not ready: "+diagnostic.get(),ok);for(int i=0;i<12;i++){Thread.sleep(2500);assertTrue("observer heartbeat missing at "+i+": "+diagnostic.get(),collector.observerAgeMillis()<2500);}assertEquals("",diagnostic.get());}
  finally{collector.close();Thread.sleep(2500);java.lang.reflect.Field f=RootProtocolCollector.class.getDeclaredField("lease");f.setAccessible(true);assertFalse("lease directory must be removed",((java.io.File)f.get(collector)).getParentFile().exists());}
 }
}
