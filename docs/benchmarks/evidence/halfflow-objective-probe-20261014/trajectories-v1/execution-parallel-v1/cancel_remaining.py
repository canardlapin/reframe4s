import hashlib
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import time
from datetime import datetime, timezone

root = Path(__file__).resolve().parent
study = root.parent
config = json.loads((root/'protocol.json').read_text())
dispatcher_pid = 3419
expected_driver = 'benchmarks/halfflow/diagnostics/diagnostic_dispatch.py'
wrapper = str(study/'engine/frozen-source/benchmarks/halfflow/reference/measure_process.py')
pending = []
for task in config['tasks']:
    receipt = study/task['case']/(task['policy']+'-fit-receipt.json')
    if not receipt.exists():
        pending.append(dict(task, fit_receipt=str(receipt),
            measurement=str(study/task['case']/(task['policy']+'-measurement.json'))))
assert len(pending) == 7
plan = {'created_at': datetime.now(timezone.utc).isoformat(), 'dispatcher_pid': dispatcher_pid,
    'reason': 'Both additional full trajectories exceeded the declared one-hour diagnostic deadline during synchronous checkpoint capture; stop remaining jobs and redesign export scheduling',
    'protocol_sha256': hashlib.sha256((root/'protocol.json').read_bytes()).hexdigest(),
    'source_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
    'tasks': pending, 'scope': 'Only direct measured child groups owned by this verified dispatcher',
    'preserve': 'Dispatcher writes normal failed-fit receipts; existing artifacts and outcomes are retained'}
with (root/'cancellation-plan-v1.json').open('x') as stream:
    json.dump(plan, stream, indent=2); stream.write('\n')
allowed = {row['measurement'] for row in pending}
signalled = []
seen = set()
deadline = time.monotonic()+120
while time.monotonic() < deadline:
    rows = []
    for line in subprocess.check_output(['ps','-axo','pid=,ppid=,pgid=,command='], text=True).splitlines():
        fields = line.strip().split(None,3)
        if len(fields) != 4: continue
        try: args = shlex.split(fields[3])
        except ValueError: continue
        rows.append((int(fields[0]), int(fields[1]), int(fields[2]), args))
    parents = [r for r in rows if r[0] == dispatcher_pid]
    if not parents: break
    assert len(parents[0][3]) > 1 and parents[0][3][1] == expected_driver, 'Dispatcher identity changed'
    for pid, ppid, pgid, args in rows:
        if ppid != dispatcher_pid or pid in seen: continue
        assert len(args) > 3 and args[1] == wrapper and '--receipt' in args
        measurement = args[args.index('--receipt')+1]
        assert measurement in allowed and pgid == pid and pgid not in [dispatcher_pid, os.getpgrp()]
        os.killpg(pgid, signal.SIGTERM)
        seen.add(pid)
        signalled.append({'pid':pid,'pgid':pgid,'measurement':measurement,'signal':'SIGTERM',
                          'time':datetime.now(timezone.utc).isoformat()})
    if all(Path(task['fit_receipt']).exists() for task in pending): break
    time.sleep(.25)
complete = all(Path(task['fit_receipt']).exists() for task in pending)
result = {'signalled':signalled,'all_cancelled_tasks_receipted':complete,
    'receipts':{task['fit_receipt']:json.loads(Path(task['fit_receipt']).read_text())['exit_code']
                for task in pending if Path(task['fit_receipt']).exists()}}
with (root/'cancellation-receipt-v1.json').open('x') as stream:
    json.dump(result,stream,indent=2);stream.write('\n')
print(json.dumps({'signalled_groups':len(signalled),'all_cancelled_tasks_receipted':complete}))
if not complete: raise SystemExit(2)
