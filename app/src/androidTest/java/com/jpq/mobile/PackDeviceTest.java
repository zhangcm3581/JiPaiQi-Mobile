package com.jpq.mobile;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.*;
import java.util.zip.*;
@RunWith(AndroidJUnit4.class)
public class PackDeviceTest {
    @Test public void failedImportsPreserveInstalledPack()throws Exception{
        var c=InstrumentationRegistry.getInstrumentation().getTargetContext();Pack before=Pack.current(c);String manifest=before.json.toString();
        for(String path:new String[]{"../escape.txt","manifest.json"}){
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){zip.putNextEntry(new ZipEntry(path));zip.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();}
            try{Pack.install(c,new ByteArrayInputStream(bytes.toByteArray()));fail("Invalid archive accepted");}catch(IOException expected){}catch(IllegalArgumentException expected){}catch(org.json.JSONException expected){}
            assertEquals(manifest,Pack.current(c).json.toString());assertFalse(new File(c.getFilesDir(),"escape.txt").exists());
        }
    }
}
