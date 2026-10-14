#!/usr/bin/env python3
"""Launch the declared reference study after verified diagnostic completion.

The monitor records process metadata only, never arguments or environment.
Observed external numerical activity excludes this entire timing run; outcomes
are retained. Sampling does not certify absence of activity between samples.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'benchmarks/halfflow/diagnostics'))
from verify_offline_ledger import inspect
from trajectory_study import verify, read, write, sha

NUMERICAL = re.compile(r'(?i)^(java|node|python[^/]*|r|rscript|sbt|scala|clang[^/]*|cc1|make|cmake)$')


def classify_processes(text, owned_root):
    processes = []
    for line in text.splitlines():
        fields = line.strip().split(None,3)
        if len(fields)!=4: raise ValueError('Unexpected process table row')
        pid,ppid,cpu,command = fields
        processes.append(dict(pid=int(pid),ppid=int(ppid),cpu_percent=float(cpu),name=Path(command).name))
    owned = {owned_root}
    while True:
        expanded = owned | {p['pid'] for p in processes if p['ppid'] in owned}
        if expanded==owned: break
        owned = expanded
    numerical = [dict(p,owned=p['pid'] in owned) for p in processes if NUMERICAL.fullmatch(p['name'])]
    external = [p for p in numerical if not p['owned'] and p['cpu_percent']>=10.0]
    return dict(at=datetime.now(timezone.utc).isoformat(),numerical_processes=numerical,
        external_active_numerical_processes=external)


def snapshot():
    return classify_processes(subprocess.check_output(['ps','-axo','pid=,ppid=,%cpu=,comm='],text=True),os.getpid())


def preflight(study, diagnostics):
    p = read(study/'protocol.json')
    if p['schema']!='hfq-p02-regularized-isolated-cost-v1': raise ValueError('Wrong timing declaration')
    for group in ['sources','inputs','pending_diagnostic_protocols']:
        for name,digest in p[group].items():
            if sha(name)!=digest: raise ValueError('Declared identity changed: '+name)
    capture = diagnostics/'trajectories-deferred-v2'
    protocol,source = verify(capture); fits = []
    for case in protocol['cases']:
        for lane in protocol['lanes']:
            path = capture/case['id']/(lane['id']+'-fit-receipt.json')
            row = read(path)
            if (row['pair']!=case['id'] or row['lane']!=lane['id'] or row['source_sha256']!=source['sha256'] or
                    row['protocol_sha256']!=sha(capture/'engine/protocol.json') or
                    row['diagnostic_seal_sha256']!=sha(capture/'diagnostic-seal.json')):
                raise ValueError('Source fit identity changed')
            if row['events_sha256']!=sha(path.parent/lane['id']/'checkpoints/events.jsonl') or \
                    row['log_sha256']!=sha(path.parent/(lane['id']+'-fit.log')):
                raise ValueError('Terminal diagnostic artifacts changed')
            fits.append(dict(pair=row['pair'],lane=row['lane'],exit_code=row['exit_code'],
                receipt_sha256=sha(path),events_sha256=row['events_sha256']))
    if len(fits)!=10: raise ValueError('Ten terminal diagnostic fits required')
    ledger = inspect(diagnostics/'offline-analysis-oasis11-v1')
    if not ledger['ledger_complete'] or ledger['expected_event_count']!=74 or ledger['expected_export_count']!=29:
        raise ValueError('First offline batch not exactly complete')
    order = read(diagnostics/'order-study-v1/results.json')
    if len(order['rows'])!=3 or not order['all_states_completed']: raise ValueError('Role audits incomplete')
    role_receipts = {}
    for row in order['rows']:
        path = diagnostics/'order-study-v1'/(row['task']['id']+'-receipt.json')
        if read(path)!=row: raise ValueError('Role audit ledger changed')
        role_receipts[row['task']['id']] = sha(path)
    next_batch = diagnostics/'offline-panel-oasis13-mmrr11-v1'
    if (next_batch/'results.json').exists() or list(next_batch.glob('*/*/event-*-receipt.json')):
        raise ValueError('Next offline batch has already started')
    if (study/'results.json').exists() or list(study.glob('*/*-receipt.json')):
        raise ValueError('Timing launch requires an unstarted study')
    return dict(verified_at=datetime.now(timezone.utc).isoformat(),fits=fits,offline_ledger=ledger,
        role_audit_receipts=role_receipts,next_export_batch_unstarted=True,
        terminal_worker_handles_observed_by_agent={'81646':0,'50624':0,'51301':0},
        terminal_handle_observation='Tool handles polled to exit zero immediately before this launch preparation.')


def run(study, diagnostics):
    plan_path = study/'isolation-plan.json'
    if plan_path.exists(): raise FileExistsError('Prior isolation attempt retained; use a new declared study')
    checks = preflight(study,diagnostics)
    write(study/'prerequisite-verification.json',checks)
    plan = dict(schema='hfq-isolation-plan-v1',created_at=datetime.now(timezone.utc).isoformat(),
        protocol_sha256=sha(study/'protocol.json'),launcher_sha256=sha(__file__),sample_interval_seconds=15,
        external_numeric_cpu_threshold_percent=10.0,
        exclusion_rule='Any observed external numerical process at or above 10% of one CPU, monitoring error, '
            'or a sample gap over 45 seconds excludes the entire run from runtime comparisons. Preserve all fitting/anatomical outcomes.',
        numerical_executable_pattern=NUMERICAL.pattern,
        scope='No other benchmark fits, exports, scoring or builds during this run. Process metadata only. '
            'Low-overhead sampling is supplementary evidence, not proof of an otherwise idle machine.',
        qualification_claim=False)
    write(plan_path,plan)
    initial = snapshot()
    if initial['external_active_numerical_processes']:
        write(study/'isolation-launch-rejected.json',initial)
        raise RuntimeError('External numerical activity; timing not launched')
    env = os.environ.copy(); env.update(read(study/'protocol.json')['environment']); env['MPLCONFIGDIR']='/private/tmp/hfq-mpl'
    command = [sys.executable,str(ROOT/'benchmarks/halfflow/reference/budget_study.py'),'run','--study',str(study)]
    print('LAUNCH isolated 3-pair x 4-lane reference study',flush=True)
    violations = []; started = time.monotonic(); samples = 0; previous = started; exit_code = None
    with (study/'driver.log').open('x') as logfile, (study/'isolation-samples.jsonl').open('x') as monitor:
        process = subprocess.Popen(command,cwd=ROOT,env=env,stdout=logfile,stderr=subprocess.STDOUT,start_new_session=True)
        write(study/'launch-receipt.json',dict(started_at=datetime.now(timezone.utc).isoformat(),pid=process.pid,
            command=command,prerequisite_verification_sha256=sha(study/'prerequisite-verification.json'),
            isolation_plan_sha256=sha(plan_path),initial_process_sample=initial,qualification_claim=False))
        try:
            while True:
                now = time.monotonic()
                try: sample = snapshot()
                except Exception as error: sample = dict(at=datetime.now(timezone.utc).isoformat(),monitor_error=str(error))
                sample['elapsed_seconds'] = now-started; sample['sample_gap_seconds'] = now-previous
                previous = now
                if sample.get('external_active_numerical_processes') or 'monitor_error' in sample or sample['sample_gap_seconds']>45:
                    violations.append(samples)
                monitor.write(json.dumps(sample)+'\n'); monitor.flush(); samples += 1
                try:
                    exit_code = process.wait(timeout=15)
                    break
                except subprocess.TimeoutExpired: pass
        except BaseException:
            os.killpg(process.pid,signal.SIGTERM)
            try: process.wait(timeout=5)
            except subprocess.TimeoutExpired: os.killpg(process.pid,signal.SIGKILL);process.wait()
            violations.append(samples)
            raise
        finally:
            write(study/'isolation-completion.json',dict(ended_at=datetime.now(timezone.utc).isoformat(),
                driver_exit_code=exit_code,samples=samples,violation_sample_indices=violations,
                isolation_pass=exit_code==0 and not violations,runtime_comparison_allowed=exit_code==0 and not violations,
                driver_log_sha256=sha(study/'driver.log'),samples_sha256=sha(study/'isolation-samples.jsonl'),
                qualification_claim=False,scope='Read this overlay before using any per-attempt runtime_comparison_allowed flag.'))
    print('FINISHED',exit_code,'isolation violations',len(violations),flush=True)
    return exit_code


if __name__=='__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--study',type=Path,required=True);parser.add_argument('--diagnostics',type=Path,required=True)
    args = parser.parse_args();raise SystemExit(run(args.study.resolve(),args.diagnostics.resolve()))
