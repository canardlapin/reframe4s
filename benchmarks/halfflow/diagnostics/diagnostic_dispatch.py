#!/usr/bin/env python3
"""Prospectively amend diagnostic concurrency after the control replay passes.

All numerical binaries, inputs, policies, captures and per-job limits remain
those of the original sealed study. Two independent diagnostic processes are
permitted; their wall times are explicitly excluded from performance evidence.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time

from trajectory_study import read, write, sha, verify, require_control_replay

HERE=Path(__file__).resolve().parent


def freeze(study,amendment):
    if amendment.exists():raise FileExistsError(amendment)
    protocol,source=verify(study)
    pilot=study/'oasis-11-to-12/selected-control-fit-receipt.json'
    require_control_replay(read(pilot))
    artifact_check=study/'oasis-11-to-12/selected-control-checkpoint-verification.json'
    if not read(artifact_check)['pass']:raise ValueError('Pilot artifact verification must pass')
    tasks=[]
    for case in protocol['cases']:
        for lane in protocol['lanes']:
            receipt=study/case['id']/(lane['id']+'-fit-receipt.json')
            if receipt.exists():
                if receipt!=pilot:raise ValueError('Unexpected completed fit before this amendment')
            else:tasks.append({'case':case['id'],'policy':lane['id']})
    if len(tasks)!=9:raise ValueError('Expected precisely nine pending diagnostic replays')
    amendment.mkdir(parents=True)
    paths=[Path(__file__),HERE/'trajectory_study.py']
    for path in paths:shutil.copyfile(path,amendment/('source-'+path.name))
    write(amendment/'protocol.json',{
        'schema':'hfq-diagnostic-concurrency-amendment-v1','created_at':datetime.now(timezone.utc).isoformat(),
        'study':str(study),'original_protocol_sha256':sha(study/'engine/protocol.json'),
        'original_diagnostic_seal_sha256':sha(study/'diagnostic-seal.json'),
        'pilot_fit_receipt_sha256':sha(pilot),'pilot_artifact_verification_sha256':sha(artifact_check),
        'numeric_source_sha256':source['sha256'],'sources':{str(p):sha(p) for p in paths},
        'tasks':tasks,'workers':2,'java_heap_per_job':'6G','java_active_processors_per_job':1,
        'timeout_seconds_per_job':protocol['fit_timeout_seconds'],
        'java_version':subprocess.run(['java','-version'],capture_output=True,text=True,check=True).stderr.strip(),
        'purpose':'Reduce diagnostic export wall time; preserve the declared ten-case/policy combinations and every captured state',
        'unchanged':'Frozen numerical classes, inputs, affine, optimizer policy, checkpoint selection, geometry gates and per-job deadline',
        'runtime_comparison_allowed':False,'qualification_claim':False})
    print('FROZEN two-worker diagnostic amendment for',len(tasks),'pending replays',flush=True)


def run(amendment):
    config=read(amendment/'protocol.json');study=Path(config['study'])
    for path,digest in config['sources'].items():
        if sha(path)!=digest:raise ValueError('Dispatch source changed: '+path)
    protocol,source=verify(study)
    if sha(study/'engine/protocol.json')!=config['original_protocol_sha256'] or sha(study/'diagnostic-seal.json')!=config['original_diagnostic_seal_sha256']:
        raise ValueError('Original study identity changed')
    pilot=study/'oasis-11-to-12/selected-control-fit-receipt.json'
    require_control_replay(read(pilot))
    artifact_check=study/'oasis-11-to-12/selected-control-checkpoint-verification.json'
    if sha(pilot)!=config['pilot_fit_receipt_sha256'] or sha(artifact_check)!=config['pilot_artifact_verification_sha256']:
        raise ValueError('Pilot evidence changed')
    env=os.environ.copy();env.update(protocol['environment'])
    cp=(study/'engine/classpath.txt').read_text().strip()
    wrapper=study/'engine/frozen-source/benchmarks/halfflow/reference/measure_process.py'
    def job(task):
        case=next(c for c in protocol['cases'] if c['id']==task['case'])
        lane=next(l for l in protocol['lanes'] if l['id']==task['policy'])
        parent=study/case['id'];parent.mkdir(exist_ok=True)
        target=parent/lane['id'];receipt=parent/(lane['id']+'-fit-receipt.json')
        if receipt.exists():
            if read(receipt).get('execution_amendment_sha256')!=sha(amendment/'protocol.json'):
                raise ValueError('Unexpected preexisting receipt: '+str(receipt))
            return
        if target.exists():raise FileExistsError('Unreceipted attempt retained: '+str(target))
        capture=protocol['checkpoint_policy']
        command=[shutil.which('java'),'-Xmx6G','-XX:ActiveProcessorCount=1','-cp',cp,
                 'reframe4s.halfflow.RealImageRegistration',case['data']+'/moving.nii.gz',case['data']+'/fixed.nii.gz',
                 case['data']+'/fixed-mask.nii.gz',str(target),lane['mode'],case['saved_affine'],*lane['flags'],
                 '--checkpoint-accepted',','.join(map(str,capture['accepted_steps'])),
                 '--checkpoint-rejected',','.join(map(str,capture['rejected_attempts'])),
                 '--checkpoint-max-states',str(capture['maximum_states']),
                 '--checkpoint-manifest',str(study/'checkpoint-manifest.json')]
        log=parent/(lane['id']+'-fit.log');measurement=parent/(lane['id']+'-measurement.json')
        row={'schema':'hfq-trajectory-fit-v1','pair':case['id'],'lane':lane['id'],'command':command,
             'protocol_sha256':sha(study/'engine/protocol.json'),'diagnostic_seal_sha256':sha(study/'diagnostic-seal.json'),
             'execution_amendment_sha256':sha(amendment/'protocol.json'),'source_sha256':source['sha256'],
             'runtime_comparison_allowed':False,'qualification_claim':False}
        print('START',case['id'],lane['id'],flush=True);started=time.monotonic()
        with log.open('x') as stream:
            process=subprocess.Popen([sys.executable,str(wrapper),'--receipt',str(measurement),'--',*command],
                env=env,stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
            try:row['exit_code']=process.wait(timeout=config['timeout_seconds_per_job'])
            except subprocess.TimeoutExpired:
                os.killpg(process.pid,signal.SIGTERM)
                try:process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid,signal.SIGKILL);process.wait()
                row['exit_code']=124;row['failure']='Declared diagnostic fit deadline exceeded'
        row['diagnostic_wall_seconds']=time.monotonic()-started
        row['measurement']=read(measurement) if measurement.exists() else None
        row['log_sha256']=sha(log)
        events=target/'checkpoints/events.jsonl'
        if events.exists():
            records=[json.loads(line) for line in events.read_text().splitlines()]
            row['events']=len(records);row['saved_states']=sum(r['directory'] is not None for r in records)
            row['events_sha256']=sha(events)
        expected=case['expected_outputs'].get(lane['id'],{})
        row['historical_replay']={name:(sha(target/name)==digest if (target/name).exists() else False)
                                  for name,digest in expected.items()}
        write(receipt,row)
        print('DONE',case['id'],lane['id'],'exit',row['exit_code'],'states',row.get('saved_states'),
              'historical_replay',row['historical_replay'],flush=True)
    with ThreadPoolExecutor(max_workers=config['workers']) as pool:
        list(pool.map(job,config['tasks']))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['freeze','run']);parser.add_argument('--study',type=Path)
    parser.add_argument('--amendment',type=Path,required=True)
    args=parser.parse_args()
    if args.command=='freeze':
        if args.study is None:parser.error('--study required')
        freeze(args.study.resolve(),args.amendment.resolve())
    else:run(args.amendment.resolve())
