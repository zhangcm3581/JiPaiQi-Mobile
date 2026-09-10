package com.jpq.mobile;

import android.content.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class IdentitySettingsDeviceTest {
    @Test public void manualIdentityPersistsAndMatchesEnvelope() throws Exception {
        var target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        var pref=target.getSharedPreferences("hand_upload",0);
        String oldTenant=pref.getString("tenant_id",null),oldClient=pref.getString("client_id",null);
        boolean oldEnabled=pref.getBoolean("enabled",false);
        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        pref.edit().remove("tenant_id").remove("client_id").putBoolean("enabled",false).commit();
        try {
            target.startActivity(new Intent(target,MainActivity.class).putExtra("hand_settings",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            device.waitForIdle();
            assertNotNull(device.wait(Until.findObject(By.text("设置")),5000));
            var tenant=device.findObject(new UiSelector().className("android.widget.EditText").instance(0));
            var client=device.findObject(new UiSelector().className("android.widget.EditText").instance(1));
            assertEquals("只保留两个 ID 输入框",2,device.findObjects(By.clazz("android.widget.EditText")).size());
            assertFalse(device.hasObject(By.textContains("http://")));
            assertFalse(device.hasObject(By.text("十三水助手")));
            tenant.setText("123456");client.setText("bad id");
            device.findObject(By.text("保存")).click();device.waitForIdle();
            assertTrue("无效 ID 不关闭设置",device.hasObject(By.text("保存")));
            assertEquals("无效输入不能保存","",pref.getString("tenant_id",""));
            client.setText("table1_device02");
            device.findObject(By.text("保存")).click();device.waitForIdle();
            assertEquals("123456",pref.getString("tenant_id",""));
            assertEquals("table1_device02",pref.getString("client_id",""));
            assertFalse("已移除旧上传开关",pref.contains("enabled"));
            assertFalse(device.hasObject(By.text("允许上传我的手牌数据")));
            String url=RoundSocket.url("http://127.0.0.1:18081","123456","table1_device02");
            assertTrue(url.endsWith("tenant_id=123456&client_id=table1_device02"));
            var session=new RoundSession("123456","table1_device02","",value->{});
            JSONObject message=session.message("ping",null,new JSONObject());
            assertEquals("123456",message.getString("tenant_id"));assertEquals("table1_device02",message.getString("client_id"));
            for(String[] ids:new String[][]{{"","device01"},{"123456",""},{"123456","bad id"}}){
                try{RoundSocket.url("http://127.0.0.1:18081",ids[0],ids[1]);fail("invalid identity accepted");}catch(IllegalArgumentException expected){}
            }
        } finally {
            pref.edit().putString("tenant_id",oldTenant).putString("client_id",oldClient).putBoolean("enabled",oldEnabled).commit();
            device.pressHome();
        }
    }
}
