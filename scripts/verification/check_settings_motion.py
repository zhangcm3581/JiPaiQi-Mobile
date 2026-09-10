#!/usr/bin/env python3
"""Record real composited frames while toggling settings; fail on toolbar movement.
Run with the app paused and settings closed. --bar uses physical screen pixels.
Requires Python with OpenCV. Leaves settings closed; never taps Start or Save.
"""
import argparse,json,pathlib,subprocess,time
import cv2
import numpy as np
p=argparse.ArgumentParser();p.add_argument('--adb',required=True);p.add_argument('--serial',required=True)
p.add_argument('--bar',type=int,nargs=4,required=True,metavar=('X','Y','W','H'))
p.add_argument('--out',type=pathlib.Path,required=True);a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True)
adb=[a.adb,'-s',a.serial]
def call(*args):return subprocess.check_output(adb+list(args))
x,y,w,h=a.bar
before=cv2.imdecode(np.frombuffer(call('exec-out','screencap','-p'),np.uint8),cv2.IMREAD_COLOR)
# Exclude rounded edges, the status dot, and the form above the fixed controls.
left,top=x+int(w*.04),y+int(h*.12)
template=before[top:y+int(h*.94),left:x+int(w*.96)]
record=subprocess.Popen(adb+['shell','screenrecord','--time-limit','7','--bit-rate','8000000','/sdcard/jpq-settings-motion.mp4'],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
time.sleep(.6)
for _ in range(8):
 call('shell','input','tap',str(x+w*3//10),str(y+h//2));time.sleep(.42)
stdout,stderr=record.communicate(timeout=12)
if record.returncode:raise RuntimeError(stderr.decode())
call('pull','/sdcard/jpq-settings-motion.mp4',str(a.out/'motion.mp4'))
video=cv2.VideoCapture(str(a.out/'motion.mp4'));frames=[];frame_no=0
form_h=int(h*3.35)
form_y=y-form_h if y>=form_h+24 else y+h
form_y=max(0,min(form_y,before.shape[0]-form_h))
form_before=before[form_y:form_y+form_h,x:x+w]
visible_intervals=0;was_visible=False
while True:
 ok,frame=video.read()
 if not ok:break
 match=cv2.matchTemplate(frame,template,cv2.TM_CCOEFF_NORMED);_,score,_,point=cv2.minMaxLoc(match)
 form_delta=float(cv2.absdiff(frame[form_y:form_y+form_h,x:x+w],form_before).mean())
 visible=form_delta>10
 if visible and not was_visible:visible_intervals+=1
 was_visible=visible
 frames.append({'frame':frame_no,'seconds':round(video.get(cv2.CAP_PROP_POS_MSEC)/1000,3),'score':round(score,3),'dx':point[0]-left,'dy':point[1]-top,'form_delta':round(form_delta,2)})
 frame_no+=1
video.release();(a.out/'frames.json').write_text(json.dumps(frames,indent=2))
tracked=[f for f in frames if f['score']>.8];moved=[f for f in tracked if abs(f['dx'])>1 or abs(f['dy'])>1]
print(json.dumps({'frames':len(frames),'tracked':len(tracked),'form_visible_intervals':visible_intervals,'moved_frames':len(moved),'max_dx':max((abs(f['dx']) for f in tracked),default=-1),'max_dy':max((abs(f['dy']) for f in tracked),default=-1),'examples':moved[:6]},ensure_ascii=False))
assert len(tracked)>len(frames)*.9,'Toolbar was not visible for enough recorded frames'
assert not moved,'Settings animation moved the bottom control bar in composited screen frames'

assert visible_intervals>=4,'Settings did not visibly open on every requested cycle'
assert not was_visible,'Settings did not finish closed'
