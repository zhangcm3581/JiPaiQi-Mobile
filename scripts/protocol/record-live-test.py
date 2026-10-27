"""Bounded local test evidence recorder; no game input or injected code."""
import json,subprocess,time,signal,shutil,sys
from pathlib import Path
from datetime import datetime
from zoneinfo import ZoneInfo
ADB=['/Users/neo/Library/Android/sdk/platform-tools/adb','-s','127.0.0.1:16384']
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
stop=False
signal.signal(signal.SIGTERM,lambda *_:globals().__setitem__('stop',True))
signal.signal(signal.SIGINT,lambda *_:globals().__setitem__('stop',True))
started=time.monotonic();tag=datetime.now(ZoneInfo('Asia/Shanghai')).strftime('%Y%m%d-%H%M%S')
(out/'recording.json').write_text(json.dumps({'host_pid':__import__('os').getpid(),'started':tag,'limit_minutes':30,'device':'127.0.0.1:16384'}))
log=(out/'collector.log').open('wb');logger=subprocess.Popen(ADB+['logcat','-T','1','-v','epoch','-e','JPQ_BRIDGE_'],stdout=log,stderr=subprocess.STDOUT)
try:
 for part in range(10):
  if stop or (out/'STOP').exists():break
  remote=f'/data/local/tmp/jpq-live-{tag}-{part:02d}.mp4';pidfile=f'/data/local/tmp/jpq-live-{tag}.pid'
  command=f'echo $$ > {pidfile}; exec screenrecord --bit-rate 2000000 --time-limit 180 {remote}'
  screen=subprocess.Popen(ADB+['shell',command],stdout=subprocess.DEVNULL,stderr=(out/f'video-{part:02d}.log').open('wb'))
  (out/'current-recording.json').write_text(json.dumps({'remote':remote,'pidfile':pidfile,'part':part}))
  while screen.poll() is None:
   if stop or (out/'STOP').exists() or time.monotonic()-started>1800:
    try:
     pid=subprocess.check_output(ADB+['shell','cat',pidfile],timeout=3).decode().strip()
     if pid.isdigit():
      cmd=subprocess.check_output(ADB+['shell','cat',f'/proc/{pid}/cmdline'],timeout=3).decode()
      if 'screenrecord' in cmd and remote in cmd:subprocess.run(ADB+['shell','kill','-INT',pid],timeout=3)
    except Exception:pass
    try:screen.wait(timeout=8)
    except subprocess.TimeoutExpired:screen.terminate()
    break
   try:
    journal=subprocess.check_output(ADB+['exec-out','run-as','com.jpq.mobile.protocol','cat','files/protocol-events.jsonl'],timeout=3,stderr=subprocess.DEVNULL)
    (out/'client-protocol.jsonl').write_bytes(journal)
    shutil.copyfile('/Volumes/Bot/code/JiPaiQi-Serve-protocol/build/protocol-test/single-account-lab.jsonl',out/'server-lab.jsonl')
   except Exception:pass
   time.sleep(1)
  pulled=subprocess.run(ADB+['pull',remote,str(out/f'video-{part:02d}.mp4')],stdout=subprocess.DEVNULL,timeout=30)
  if pulled.returncode==0:subprocess.run(ADB+['shell','rm',remote],timeout=3)
  if screen.returncode not in (0,None):break
finally:
 logger.terminate();logger.wait(timeout=5);log.close();(out/'finished.txt').write_text(datetime.now(ZoneInfo('Asia/Shanghai')).isoformat())
