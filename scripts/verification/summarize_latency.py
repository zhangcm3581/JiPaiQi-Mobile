#!/usr/bin/env python3
"""Summarize device EndToEndLatencyTest JSON; keep controlled wait cases separate."""
import argparse,json,math,statistics
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('input',type=Path);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
d=json.loads(a.input.read_text());rows=d['records'];assert len(rows)==27 and all(r['correct_13_painted'] for r in rows)
def stats(values):
    v=sorted(values)
    return {'count':len(v),'mean_ms':statistics.mean(v),'median_ms':statistics.median(v),'p95_ms':v[math.ceil(len(v)*.95)-1],'min_ms':min(v),'max_ms':max(v),'over_2000_ms':sum(x>2000 for x in v)}
out={'endpoint':d['endpoint'],'normal':stats([r['screen_to_result_ms'] for r in rows if r['scenario']=='six_peers_ready_during_capture']),'delayed':stats([r['screen_to_result_ms'] for r in rows if r['scenario']=='last_peer_delayed_2500ms'])}
normal=rows[:24]
for key in ['request_to_result_ms','screen_to_empty_panel_ms','first_13_observed_to_result_ms','received_to_paint_ms']:
    out[key]=stats([r[key] for r in normal if r[key] is not None])
out['late_peer_send_to_paint']=stats([r['last_peer_send_to_paint_ms'] for r in rows[24:]])
a.output.write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n');print(json.dumps(out,ensure_ascii=False,indent=2))
