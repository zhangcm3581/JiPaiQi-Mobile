"""Offline capture audit; emits summaries without player identities."""
import collections
import json
from pathlib import Path
import re

root = Path(__file__).resolve().parents[2] / 'build/rea/session-20261012'
groups = {}
for line in (root / 'events.log').read_text().splitlines():
    match = re.search(r'JPQ_CAPTURE (\d+) (\d+) (\d+) (\d+) (.*)', line)
    if match:
        session, sequence, offset, length, text = match.groups()
        groups.setdefault((session, int(sequence), int(length)), {})[int(offset)] = text
records, incomplete = [], []
for (session, sequence, length), parts in groups.items():
    cursor = 0
    for offset, part in sorted(parts.items()):
        if cursor != offset:
            break
        cursor += len(part.encode('utf-16-le', errors='surrogatepass')) // 2
    if cursor != length:
        incomplete.append(sequence)
        continue
    records.append(json.loads(''.join(parts[k] for k in sorted(parts))))
(root / 'events.jsonl').write_text(''.join(json.dumps(r, ensure_ascii=False) + '\n' for r in records))
sessions = collections.defaultdict(set)
for r in records:
    sessions[r['session']].add(r['sequence'])
missing = {s: sorted(set(range(1, max(values) + 1)) - values) for s, values in sessions.items()}
rounds = []
for r in records:
    if r['type'] == 'incoming-packet' and r.get('command') == 21:
        cards = r['data'].get('hand_cards', [])
        decoded = []
        for value in cards:
            suit, rank = value >> 4, value & 15
            decoded.append(('?', 's', 'h', 'c', 'd')[suit] + {11: 'J', 12: 'Q', 13: 'K', 14: 'A'}.get(rank, str(rank))
                           if 1 <= suit <= 4 and 2 <= rank <= 14 else 'UNKNOWN:' + str(value))
        rounds.append({'gameRound': r['data'].get('currentRound'), 'sequence': r['sequence'],
                       'timeMs': r['timeMs'], 'count': len(cards), 'cards': decoded,
                       'encodingValid': all(not c.startswith('UNKNOWN') for c in decoded)})
counts = collections.Counter((r['type'], r.get('command')) for r in records)
audit = {'records': len(records), 'incompleteRecords': incomplete, 'missingSequences': missing,
         'captureErrors': [r for r in records if r['type'] == 'capture-error'], 'rounds': rounds,
         'commands': [{'type': t, 'command': c, 'count': count} for (t, c), count in counts.items()],
         'settlements': sum(r['type'] == 'incoming-packet' and r.get('command') == 29 for r in records),
         'roomSettlements': sum(r['type'] == 'incoming-packet' and r.get('command') == 30 for r in records),
         'limits': 'Sequence continuity audits emitted records only; it does not prove coverage of unhooked connections or untriggered events.'}
(root / 'audit.json').write_text(json.dumps(audit, ensure_ascii=False, indent=2))
print(json.dumps({k: v for k, v in audit.items() if k not in ('rounds', 'commands', 'limits')}, ensure_ascii=False))
print('Rounds:', [r['gameRound'] for r in rounds])
