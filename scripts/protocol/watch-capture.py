"""Reassemble chunked JS records and preserve screenshots at stage changes."""
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'build/rea/session-20261012'
ADB = ['/Users/neo/Library/Android/sdk/platform-tools/adb', '-s', '127.0.0.1:16384']
pattern = re.compile(r'JPQ_CAPTURE (\d+) (\d+) (\d+) (\d+) (.*)')
groups = {}
seen = set()
stage_commands = {21, 22, 23, 29, 30, 36, 43, 47, 71, 72, 73}
with (OUT / 'events.log').open() as source, (OUT / 'verified-events.jsonl').open('a', buffering=1) as target:
    while (OUT / 'recording-active').exists():
        line = source.readline()
        if not line:
            time.sleep(0.15)
            continue
        match = pattern.search(line)
        if not match:
            continue
        session, sequence, offset, length, text = match.groups()
        key = session, sequence
        if key in seen:
            continue
        chunks = groups.setdefault(key, {})
        chunks[int(offset)] = text
        cursor = 0
        for start, part in sorted(chunks.items()):
            if start != cursor:
                break
            cursor += len(part.encode('utf-16-le', errors='surrogatepass')) // 2
        if cursor != int(length):
            continue
        record = json.loads(''.join(chunks[k] for k in sorted(chunks)))
        target.write(json.dumps(record, ensure_ascii=False) + '\n')
        target.flush()
        seen.add(key)
        del groups[key]
        command = record.get('command')
        if record['type'] == 'incoming-packet' and command in stage_commands:
            data = record.get('data') or {}
            print(json.dumps({'sequence': sequence, 'command': command,
                              'gameRound': data.get('currentRound'),
                              'handCount': len(data.get('hand_cards', [])),
                              'timeMs': record['timeMs']}), flush=True)
            with (OUT / f'stage-{sequence}-cmd-{command}.png').open('wb') as image:
                subprocess.run(ADB + ['exec-out', 'screencap', '-p'], stdout=image, timeout=15)
