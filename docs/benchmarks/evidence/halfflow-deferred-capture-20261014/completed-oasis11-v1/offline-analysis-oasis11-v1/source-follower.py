#!/usr/bin/env python3
"""Score a sealed batch as export receipts arrive; never restart fits/exports."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time

from offline_analysis import inspect_plan, read, require, sha
from trajectory_study import write


def follow(analysis, deadline_seconds):
    protocol = read(analysis/'protocol.json')
    exports = Path(protocol['offline_exports']['directory'])
    export_protocol, tasks, ledger = inspect_plan(Path(protocol['trajectory_study']),exports)
    require(ledger == protocol['offline_exports']['fit_ledger'], 'Analysis scope changed')
    require(not (analysis/'follower-plan.json').exists(), 'Follower attempt already retained')
    driver = Path(__file__).parent/'checkpoint_analysis.py'
    require(protocol['sources'][str(driver)] == sha(driver), 'Analysis driver changed')
    shutil.copyfile(__file__,analysis/'source-follower.py')
    expected = sum(fit['events'] for fit in ledger)
    write(analysis/'follower-plan.json',dict(source_sha256=sha(Path(__file__)),
        created_at=datetime.now(timezone.utc).isoformat(),analysis_protocol_sha256=sha(analysis/'protocol.json'),
        export_protocol_sha256=sha(exports/'protocol.json'),expected_events=expected,
        deadline_seconds=deadline_seconds,poll_seconds=30,qualification_claim=False))
    start = time.monotonic()
    last_exports = None
    runs = 0
    while time.monotonic()-start < deadline_seconds:
        available = sum((exports/task['pair']/task['policy']/f"event-{task['event']:04d}-receipt.json").exists()
                        for task in tasks.values())
        if available != last_exports:
            runs += 1
            print('SCORE',runs,'terminal exports',available,'of',len(tasks),flush=True)
            code = subprocess.call([sys.executable,str(driver),'run','--output',str(analysis)])
            if code != 0:
                write(analysis/'follower-receipt.json',dict(exit_code=code,analysis_runs=runs,
                    ledger_complete=False,failure='Analysis subprocess failed; partial outputs retained',qualification_claim=False))
                raise SystemExit(code)
            last_exports = available
        rows = [read(path) for path in analysis.glob('*/*/event-*.json')]
        if len(rows) == expected:
            write(analysis/'follower-receipt.json',dict(exit_code=0,analysis_runs=runs,ledger_complete=True,
                event_statuses=dict(Counter(row['status'] for row in rows)),source_fit_ledger=ledger,
                note='Complete attempt accounting is not a scientific pass; failures/rejections retain their recorded status.',
                qualification_claim=False,runtime_comparison_allowed=False))
            print('DONE',len(rows),'events',flush=True)
            return
        require(len(rows) < expected, 'Unexpected analysis event count')
        time.sleep(30)
    write(analysis/'follower-receipt.json',dict(exit_code=124,analysis_runs=runs,ledger_complete=False,
        failure='Declared follower deadline reached; pending exports remain unscored',qualification_claim=False))
    raise SystemExit(124)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--analysis',type=Path,required=True)
    p.add_argument('--deadline-seconds',type=int,default=36000)
    args = p.parse_args()
    if args.deadline_seconds <= 0:p.error('positive deadline required')
    follow(args.analysis.resolve(),args.deadline_seconds)
