package com.jpq.mobile;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Root-only game observer. It never creates game requests or reads other players' cards. */
final class RootProtocolCollector implements AutoCloseable {
    private final Context context;
    private final Consumer<JSONObject> events;
    private final Consumer<String> status;
    private final String token=UUID.randomUUID().toString().replace("-","");
    private final String prefix="JPQ_BRIDGE_"+token+" ";
    private final File lease;
    private final File injectorPid,loggerPid;
    private final ScheduledExecutorService executor=Executors.newScheduledThreadPool(3);
    private volatile boolean closed;
    private volatile long lastObserverAt;
    private volatile boolean observerReady,leaseReady,attaching;
    private volatile Process injector,logger;
    private synchronized void diagnostic(String line){
        try(FileWriter out=new FileWriter(new File(context.getFilesDir(),"protocol-collector.log"),true)){
            out.write(System.currentTimeMillis()+" "+line+"\n");
        }catch(IOException ignored){}
    }
    private int gameMachine(String pid)throws Exception{
        String path=check("awk '$NF ~ /libcocos2djs[.]so$/ {print $NF; exit}' /proc/"+pid+"/maps");
        if(!path.startsWith("/")||path.contains("\n"))throw new IOException("无法定位游戏模块文件，请保存诊断日志");
        String bytes=check("od -An -tu1 -j18 -N2 "+quote(path));
        String[] values=bytes.trim().split("\\s+");
        if(values.length!=2)throw new IOException("无法读取游戏模块架构");
        return Integer.parseInt(values[0]) | (Integer.parseInt(values[1])<<8);
    }
    RootProtocolCollector(Context context,Consumer<JSONObject> events,Consumer<String> status){
        this.context=context;this.events=events;this.status=status;lease=new File("/data/local/tmp/jpq-lease-"+token,"heartbeat");
        injectorPid=new File(context.getFilesDir(),"injector-"+token+".pid");loggerPid=new File(context.getFilesDir(),"logger-"+token+".pid");
    }
    private static String quote(String value){return "'"+value.replace("'","'\\''")+"'";}
    private Process root(String command)throws IOException{return new ProcessBuilder("su","-c",command).redirectErrorStream(true).start();}
    static byte[] readAll(InputStream input)throws IOException {
        try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();
        }
    }
    private String check(String command)throws Exception {
        Process process=root(command);
        if(!process.waitFor(8,TimeUnit.SECONDS)){process.destroy();throw new IOException("Root命令超时，请检查授权");}
        String text=new String(readAll(process.getInputStream()),StandardCharsets.UTF_8).trim();
        if(process.exitValue()!=0){diagnostic("root_exit="+process.exitValue()+" output="+text);throw new IOException("Root命令执行失败（"+process.exitValue()+"），请保存诊断日志");}return text;
    }
    interface PollDelay { void waitOnce() throws Exception; }
    static String waitForGame(Callable<String> probe,java.util.function.BooleanSupplier stopped,PollDelay delay)throws Exception {
        while(!stopped.getAsBoolean()){
            String pid=probe.call().trim();
            if(stopped.getAsBoolean())return null;
            if(pid.matches("[0-9]+"))return pid;
            if(!pid.isEmpty())throw new IOException("游戏进程查询返回异常");
            delay.waitOnce();
        }
        return null;
    }
    static void atomicLease(File destination,long timestamp)throws IOException {
        File temporary=new File(destination.getPath()+".new");
        Files.write(temporary.toPath(),String.valueOf(timestamp).getBytes(StandardCharsets.UTF_8));
        if(!temporary.setReadable(true,false))throw new IOException("无法设置心跳读取权限");
        Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    private synchronized void refreshLease()throws IOException{if(!closed)atomicLease(lease,System.currentTimeMillis());}
    long observerAgeMillis(){return android.os.SystemClock.elapsedRealtime()-lastObserverAt;}
    void start(){
        try(FileWriter out=new FileWriter(new File(context.getFilesDir(),"protocol-collector.log"),false)){out.write("version="+BuildConfig.VERSION_NAME+"\n");}catch(IOException ignored){}
        lastObserverAt=android.os.SystemClock.elapsedRealtime();
        executor.scheduleAtFixedRate(()->{
            if(closed)return;
            if(attaching&&observerAgeMillis()>15000){status.accept(observerReady?"游戏采集心跳中断，已暂停，请返回游戏后重新启动":"采集未能就绪，已暂停，请确认游戏已打开");close();return;}
            try{if(leaseReady)refreshLease();}catch(Exception e){status.accept("无法写入采集心跳");close();}
        },0,1,TimeUnit.SECONDS);
        executor.execute(()->{
            try {
                if(!check("id -u").equals("0"))throw new IOException("协议版需要Root授权");
                if(closed)return;
                events.accept(new JSONObject().put("event","waiting-game"));
                String pid=waitForGame(
                    ()->check("p=$(pidof com.sgjt.huiyi); rc=$?; if [ $rc -eq 1 ]; then exit 0; fi; [ $rc -eq 0 ] || exit $rc; p=${p%% *}; if grep -q libcocos2djs.so /proc/$p/maps; then printf '%s' \"$p\"; fi"),
                    ()->closed,()->Thread.sleep(1000));
                if(pid==null||closed)return;
                events.accept(new JSONObject().put("event","attaching-game"));
                // The injected code runs as the GAME uid, not root. Share only a read-only
                // timestamp through a random, non-listable directory owned by this APK.
                String directory=quote(lease.getParent());
                check("mkdir "+directory+" && chown "+android.os.Process.myUid()+" "+directory+" && chmod 711 "+directory);
                refreshLease();leaseReady=true;if(closed)return;
                String binary=context.getApplicationInfo().nativeLibraryDir+"/libjpq_inject.so";
                byte[] header=new byte[20];
                try(DataInputStream input=new DataInputStream(new FileInputStream(binary))){input.readFully(header);}
                int collectorMachine=CollectorArchitecture.machine(header);
                String host=check("uname -m");
                CollectorArchitecture.verifyHost(host,collectorMachine);
                int gameMachine=gameMachine(pid);
                String realm=CollectorArchitecture.realm(collectorMachine,gameMachine);
                diagnostic("host="+host+" collector_machine="+collectorMachine+" game_machine="+gameMachine+" realm="+realm+" game_pid="+pid);
                if(closed)return;
                lastObserverAt=android.os.SystemClock.elapsedRealtime();attaching=true;
                String observer=new String(readAll(context.getAssets().open("protocol-observer.js")),StandardCharsets.UTF_8).replace("__PREFIX__",JSONObject.quote(prefix));
                String source=bootstrap(observer,lease.getAbsolutePath(),prefix);
                File script=new File(context.getFilesDir(),"observer-"+token+".js");Files.write(script.toPath(),source.getBytes(StandardCharsets.UTF_8));
                if(closed)return;
                // Begin live log reading before installing callbacks; do not replay old logcat records.
                logger=root("echo $$ > "+quote(loggerPid.getAbsolutePath())+"; exec logcat --pid="+pid+" -T 1 -v raw -e "+quote(prefix));
                executor.execute(this::readEvents);
                injector=root("echo $$ > "+quote(injectorPid.getAbsolutePath())+"; exec "+quote(binary)+" --realm="+realm+" -p "+pid+" -s "+quote(script.getAbsolutePath()));
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(injector.getInputStream(),StandardCharsets.UTF_8))){
                    String line;
                    while(!closed&&(line=reader.readLine())!=null)diagnostic(line);
                }
                if(!closed){
                    int exit=injector.waitFor();diagnostic("injector_exit="+exit);
                    status.accept("采集退出（"+exit+"/"+realm+"），请保存诊断日志");close();
                }
            }catch(Exception error){diagnostic("startup_error="+error);if(!closed)status.accept(error.getMessage());close();}
        });
    }
    private void readEvents(){
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(logger.getInputStream(),StandardCharsets.UTF_8))){
            String line;while(!closed&&(line=reader.readLine())!=null){int at=line.indexOf(prefix);if(at>=0){String data=line.substring(at+prefix.length());if(data.length()<4096){JSONObject event=new JSONObject(data);String kind=event.optString("event");
                    if(kind.equals("ready")||kind.equals("heartbeat")){observerReady=true;lastObserverAt=android.os.SystemClock.elapsedRealtime();}
                    if(kind.equals("observer-lost")){status.accept("游戏采集入口发生变化，已暂停，请重新启动");close();return;}
                    if(!kind.equals("heartbeat"))events.accept(event);
                }}}
        }catch(Exception error){if(!closed)status.accept("采集消息读取失败");}
    }
    static String bootstrap(String observer,String leasePath,String prefix){
        return "'use strict';const m=Process.getModuleByName('libcocos2djs.so');"
            +"const get=new NativeFunction(m.getExportByName('_ZN2se12ScriptEngine11getInstanceEv'),'pointer',[]);"
            +"const ev=new NativeFunction(m.getExportByName('_ZN2se12ScriptEngine10evalStringEPKclPNS_5ValueES2_'),'bool',['pointer','pointer','long','pointer','pointer']);"
            +"const src=Memory.allocUtf8String("+JSONObject.quote(observer)+");"
            +"const stop=Memory.allocUtf8String("+JSONObject.quote("if(window.__jpqLiveBridge&&window.__jpqLiveBridge.token==="+JSONObject.quote(prefix)+")window.__jpqLiveBridge.stop();")+");"
            +"const health=Memory.allocUtf8String("+JSONObject.quote("if(window.__jpqLiveBridge&&window.__jpqLiveBridge.token==="+JSONObject.quote(prefix)+")window.__jpqLiveBridge.health();")+");const name=Memory.allocUtf8String('jpq-protocol');let installed=false,expired=false,lastValid=Date.now(),lastHealth=0;"
            +"const timer=setInterval(()=>{try{const text=File.readAllText("+JSONObject.quote(leasePath)+");if(/^[0-9]{13}$/.test(text))lastValid=Number(text);else if(text==='0')expired=true;}catch(e){}expired=expired||Date.now()-lastValid>4000;},500);"
            +"const hook=Interceptor.attach(m.getExportByName('_ZN7cocos2d9Scheduler6updateEf'),{onEnter(){"
            +"if(expired){if(installed)ev(get(),stop,-1,ptr(0),name);hook.detach();clearInterval(timer);return;}"
            +"if(!installed){installed=ev(get(),src,-1,ptr(0),name);}if(installed&&Date.now()-lastHealth>1000){lastHealth=Date.now();ev(get(),health,-1,ptr(0),name);}}});";
    }
    private void stopOwnedProcess(File pidFile){
        try {
            String pid=new String(Files.readAllBytes(pidFile.toPath()),StandardCharsets.UTF_8).trim();
            if(pid.matches("[0-9]+"))check("if tr '\\0' '\\n' < /proc/"+pid+"/cmdline | grep -q -F "+quote(token)+"; then kill -TERM "+pid+"; fi");
        }catch(Exception ignored){}finally{pidFile.delete();}
    }
    public synchronized void close(){
        if(closed)return;closed=true;
        try{atomicLease(lease,0);}catch(Exception ignored){}
        if(logger!=null)logger.destroy();
        // Give the in-process lease watchdog time to restore the original game method.
        executor.schedule(()->{stopOwnedProcess(loggerPid);stopOwnedProcess(injectorPid);if(injector!=null)injector.destroy();lease.delete();new File(lease.getPath()+".new").delete();try{check("rmdir "+quote(lease.getParent()));}catch(Exception error){android.util.Log.w("JPQ_PROTOCOL_TEST","临时心跳目录清理失败",error);}new File(context.getFilesDir(),"observer-"+token+".js").delete();executor.shutdownNow();},1500,TimeUnit.MILLISECONDS);
    }
}
