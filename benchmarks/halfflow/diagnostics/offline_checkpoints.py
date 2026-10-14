#!/usr/bin/env python3
"""Export immutable raw checkpoints with a separate, complete attempt ledger."""
import argparse
from datetime import datetime, timezone
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time

from trajectory_study import ROOT, read, write, sha, verify, verify_engine


def freeze(output, engine_study, captures, pilot):
    if output.exists():
        raise FileExistsError(output)
    _, source = verify(engine_study)
    panel, _ = verify(captures)
    inputs = dict(read(captures/'engine/inputs.json'))
    tasks = []
    for case in panel['cases']:
        for lane in panel['lanes']:
            if pilot and (case['id'],lane['id']) != ('oasis-11-to-12','selected-control'):
                continue
            parent = captures/case['id']
            fit = parent/(lane['id']+'-fit-receipt.json')
            if not fit.exists():
                raise ValueError('Cannot export a nonterminal fit: '+str(fit))
            inputs[str(fit)] = sha(fit)
            events = parent/lane['id']/'checkpoints/events.jsonl'
            if not events.exists():
                if read(fit)['exit_code'] == 0:
                    raise ValueError('Successful fit is missing its events')
                continue
            inputs[str(events)] = sha(events)
            if inputs[str(events)] != read(fit)['events_sha256']:
                raise ValueError('Committed event stream changed')
            import json
            records = [json.loads(line) for line in events.read_text().splitlines()]
            for event in records:
                if event['directory'] is None or (pilot and event['serial'] not in [1,23]):
                    continue
                checkpoint = events.parent/event['directory']
                if read(checkpoint/'checkpoint.json') != event:
                    raise ValueError('Checkpoint differs from committed event')
                raw = [checkpoint/'checkpoint.json']
                for arm in ['fixed','moving']:
                    raw += [checkpoint/('midpoint-'+arm+'-residual'+s) for s in ['.f64','.json','.valid-u8']]
                    raw += [checkpoint/('midpoint-'+arm+'-affine.json')]
                inputs.update({str(path):sha(path) for path in raw})
                expected = {}
                for name in ['halfflow','halfflow-rejected']:
                    if (checkpoint/(name+'-fixed-to-moving.json')).exists():
                        expected = {name+'-'+direction+suffix:sha(checkpoint/(name+'-'+direction+suffix))
                            for direction in ['fixed-to-moving','moving-to-fixed']
                            for suffix in ['.f64','.valid-u8','.json','-warped.f64']}
                        expected.update({name:sha(checkpoint/name) for name in ['export-status.txt','export-diagnostics.txt']})
                tasks.append(dict(pair=case['id'],policy=lane['id'],event=event['serial'],
                    checkpoint=str(checkpoint), data=case['data'], expected_synchronous_exports=expected,
                    fit_exit_code=read(fit)['exit_code']))
    if pilot and (len(tasks)!=2 or {t['event'] for t in tasks}!={1,23}):
        raise ValueError('Pilot must include coarse start and fine start')
    output.mkdir(parents=True)
    shutil.copyfile(__file__, output/'source-offline_checkpoints.py')
    write(output/'protocol.json',dict(schema='hfq-offline-checkpoint-export-v1',
        created_at=datetime.now(timezone.utc).isoformat(), engine=str(engine_study/'engine'),
        engine_freeze_sha256=sha(engine_study/'engine/freeze.json'), source_sha256=source['sha256'],
        capture_protocol_sha256=sha(captures/'engine/protocol.json'), captures=str(captures),
        driver_sha256=sha(Path(__file__)), inputs=inputs,tasks=tasks,workers=1,
        timeout_seconds=3600,environment=panel['environment'],
        exact_replay_required=any(t['expected_synchronous_exports'] for t in tasks),
        rule='Every committed selected raw state gets one export receipt. Export failure or rejection remains explicit. A partial or failed source fit never becomes a completed fit through export.',
        pilot=pilot,qualification_claim=False,runtime_comparison_allowed=False))
    print('FROZEN',len(tasks),'offline exports; synchronous byte parity where available',flush=True)


def run(output):
    protocol=read(output/'protocol.json');engine=Path(protocol['engine'])
    if sha(Path(__file__))!=protocol['driver_sha256'] or sha(engine/'freeze.json')!=protocol['engine_freeze_sha256']:
        raise ValueError('Export driver or engine seal changed')
    verify_engine(engine)
    for path,digest in protocol['inputs'].items():
        if sha(path)!=digest:
            raise ValueError('Frozen export input changed: '+path)
    env=os.environ.copy();env.update(protocol['environment'])
    cp=(engine/'classpath.txt').read_text().strip()
    wrapper=engine/'frozen-source/benchmarks/halfflow/reference/measure_process.py'
    rows=[]
    for task in protocol['tasks']:
        parent=output/task['pair']/task['policy'];parent.mkdir(parents=True,exist_ok=True)
        name=f"event-{task['event']:04d}"
        target=parent/name;receipt=parent/(name+'-receipt.json')
        if receipt.exists():
            rows.append(read(receipt));continue
        log=parent/(name+'.log');measurement=parent/(name+'-measurement.json')
        if target.exists() or log.exists():
            raise FileExistsError('Unreceipted export attempt retained: '+str(target))
        cmd=[shutil.which('java'),'-Xmx6G','-XX:ActiveProcessorCount=1','-cp',cp,
            'reframe4s.halfflow.RealImageCheckpointExport',task['data']+'/moving.nii.gz',
            task['data']+'/fixed.nii.gz',task['data']+'/fixed-mask.nii.gz',task['checkpoint'],str(target)]
        row=dict(task=task,command=cmd,protocol_sha256=sha(output/'protocol.json'),
                 runtime_comparison_allowed=False,qualification_claim=False)
        started=time.monotonic();print('START',task['pair'],task['policy'],name,flush=True)
        with log.open('x') as stream:
            process=subprocess.Popen([sys.executable,str(wrapper),'--receipt',str(measurement),'--',*cmd],
                cwd=ROOT,env=env,stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
            try:row['exit_code']=process.wait(timeout=protocol['timeout_seconds'])
            except subprocess.TimeoutExpired:
                os.killpg(process.pid,signal.SIGTERM)
                try:process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid,signal.SIGKILL);process.wait()
                row.update(exit_code=124,failure='Declared offline export deadline exceeded')
        row.update(wall_seconds=time.monotonic()-started,log_sha256=sha(log),
                   measurement=read(measurement) if measurement.exists() else None)
        result=target/'result.json'
        if row['exit_code']==0 and result.exists():
            row['result']=read(result)
            row['outputs']={p.name:sha(p) for p in target.iterdir() if p.is_file()}
        row['synchronous_parity']={name:(sha(target/name)==digest if (target/name).exists() else False)
                                  for name,digest in task['expected_synchronous_exports'].items()}
        write(receipt,row);rows.append(row)
        print('DONE',name,'exit',row['exit_code'],'parity',row['synchronous_parity'],flush=True)
    write(output/'results.json',dict(rows=rows,qualification_claim=False,runtime_comparison_allowed=False,
        all_exports_completed=all(r['exit_code']==0 and 'result' in r for r in rows),
        synchronous_parity_pass=all(all(r['synchronous_parity'].values()) for r in rows)))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('command',choices=['freeze','run']);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--engine-study',type=Path);p.add_argument('--captures',type=Path)
    p.add_argument('--pilot',action='store_true');a=p.parse_args()
    if a.command=='freeze':
        if a.engine_study is None or a.captures is None:p.error('freeze requires --engine-study and --captures')
        freeze(a.output.resolve(),a.engine_study.resolve(),a.captures.resolve(),a.pilot)
    else:run(a.output.resolve())
