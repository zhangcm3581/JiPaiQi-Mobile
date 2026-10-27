package com.jpq.mobile;
import android.content.*;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import okhttp3.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class ProtocolServiceDeviceTest {
 Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
 <T>T onWorker(ProtocolService service,Callable<T> action)throws Exception{FutureTask<T> task=new FutureTask<>(action);((Handler)field(service,"worker")).post(task);return task.get(5,TimeUnit.SECONDS);}
 void await(Callable<Boolean> check)throws Exception{long deadline=SystemClock.elapsedRealtime()+12000;while(SystemClock.elapsedRealtime()<deadline){if(check.call())return;Thread.sleep(50);}fail("service lifecycle timeout");}
 @Test public void actualForegroundServiceStartsPausesRestarts()throws Exception{
  assertEquals("http://127.0.0.1:18080",BuildConfig.SERVER_URL);
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();String tenant="8"+System.currentTimeMillis();OkHttpClient http=new OkHttpClient();
  try(Response response=http.newCall(new Request.Builder().url(BuildConfig.SERVER_URL+"/api/tenants").post(RequestBody.create(new JSONObject().put("tenant_id",tenant).toString(),MediaType.get("application/json"))).build()).execute()){assertEquals(201,response.code());}
  SharedPreferences pref=context.getSharedPreferences("hand_upload",0);String previousTenant=pref.getString("tenant_id",""),previousClient=pref.getString("client_id","");
  pref.edit().putString("tenant_id",tenant).putString("client_id","service-test").commit();
  try{
   context.startForegroundService(new Intent(context,ProtocolService.class));await(()->ProtocolService.current!=null&&field(ProtocolService.current,"overlay")!=null);
   ProtocolService service=ProtocolService.current;
   for(int cycle=0;cycle<2;cycle++){
    InstrumentationRegistry.getInstrumentation().runOnMainSync(service::toggle);
    await(()->onWorker(service,()->{ProtocolSession s=(ProtocolSession)field(service,"session");return s!=null&&(boolean)field(s,"synced")&&s.status.contains("采集已连接");}));
    assertFalse(onWorker(service,()->((ProtocolSession)field(service,"session")).panel));
    InstrumentationRegistry.getInstrumentation().runOnMainSync(service::toggle);
    await(()->onWorker(service,()->field(service,"session")==null&&field(service,"socket")==null&&field(service,"collector")==null));
    Thread.sleep(2200);
   }
  }finally{context.stopService(new Intent(context,ProtocolService.class));pref.edit().putString("tenant_id",previousTenant).putString("client_id",previousClient).commit();http.dispatcher().executorService().shutdown();http.connectionPool().evictAll();}
 }
}
