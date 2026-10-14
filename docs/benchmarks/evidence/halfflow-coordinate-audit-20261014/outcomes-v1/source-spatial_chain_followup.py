#!/usr/bin/env python3
"""Replicate the fixed spatial-chain diagnostic at declared later/different states."""
import argparse
from datetime import datetime, timezone
import math
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time

from trajectory_study import ROOT, read, write, sha, verify, verify_engine

HERE = Path(__file__).resolve().parent
SELECTION = [('oasis-11-to-12','step-scaled-fine1.5','LevelEnd'),
             ('oasis-13-to-14','selected-control','LevelStart'),
             ('oasis-13-to-14','selected-control','LevelEnd')]


def summarize(result, initial, protocol):
    if [d['direction'] for d in result['directions']] != protocol['directions']:
        raise ValueError('Unexpected direction ledger')
    for direction in result['directions']:
        for row in direction['rows']:
            if row['direction']!=direction['direction']:raise ValueError('Mislabeled derivative row')
            fields=['epsilon_mm','plus_loss','minus_loss','finite_difference','analytic',
                    'intensity_chain_derivative','spatial_chain_derivative']
            if any(not isinstance(row[k],(float,int)) or isinstance(row[k],bool) or not math.isfinite(row[k]) for k in fields):
                raise ValueError('Nonfinite derivative measurement')
            if row['epsilon_mm']<=0:raise ValueError('Nonpositive perturbation')
            numerical=(row['plus_loss']-row['minus_loss'])/(2*row['epsilon_mm'])
            if not math.isclose(numerical,row['finite_difference'],rel_tol=1e-12,abs_tol=1e-15):
                raise ValueError('Finite difference arithmetic differs from losses')
            for derivative,field in [('analytic','relative_error'),('intensity_chain_derivative','intensity_chain_relative_error'),
                                     ('spatial_chain_derivative','spatial_chain_relative_error')]:
                actual=abs(numerical-row[derivative])/max(1e-12,abs(numerical)+abs(row[derivative]))
                if not isinstance(row[field],(float,int)) or not math.isclose(actual,row[field],rel_tol=1e-12,abs_tol=1e-15):
                    raise ValueError('Derivative error arithmetic differs from measurements')
    verdicts = {}
    for label, field in [('production','relative_error'),('intensity','intensity_chain_relative_error'),
                         ('spatial','spatial_chain_relative_error')]:
        rows = []
        for direction in result['directions']:
            if [r['epsilon_mm'] for r in direction['rows']] != protocol['epsilons_mm']:
                raise ValueError('Unexpected epsilon ledger')
            errors = [r[field] for r in direction['rows']]
            if not errors or any(not isinstance(e,(float,int)) or not math.isfinite(e) or e < 0 for e in errors):
                raise ValueError('Invalid measured derivative error')
            decreasing = any(c < b < a for a,b,c in zip(errors,errors[1:],errors[2:]))
            rows.append(dict(direction=direction['direction'],minimum_relative_error=min(errors),
                             two_step_convergence_region=decreasing,
                             passed=decreasing and min(errors)<=protocol['maximum_minimum_relative_derivative_error']))
        verdicts[label] = rows
    support = {arm:initial['spatial_chain_'+arm] for arm in ['fixed','moving']}
    complete = all(s['unavailable']==0 and s['evaluated']==s['nonzeroIntensityDerivatives']
                   and s['nonzeroIntensityDerivatives']>0 for s in support.values())
    if not result['current_loss_unchanged']:
        raise ValueError('Frozen objective mutated')
    return dict(directions=verdicts,support=support,complete_spatial_support=complete,
        production_criterion_pass=all(r['passed'] for r in verdicts['production']),
        intensity_criterion_pass=all(r['passed'] for r in verdicts['intensity']),
        spatial_criterion_pass=complete and all(r['passed'] for r in verdicts['spatial']))


def freeze(output, pilot, captures):
    if output.exists():raise FileExistsError(output)
    reference = read(pilot/'receipt.json')
    if reference['exit_code'] != 0 or not reference['spatial_chain_criterion_pass']:
        raise ValueError('Completed spatial-chain pilot required before replication')
    prior, source = verify_engine(pilot/'engine')
    panel, _ = verify(captures)
    sealed_inputs = read(captures/'engine/inputs.json')
    inputs = {str(p):sha(p) for p in [pilot/'receipt.json',pilot/'engine/protocol.json',
              captures/'engine/protocol.json',captures/'diagnostic-seal.json']}
    tasks = []
    for pair, policy, kind in SELECTION:
        case = next(c for c in panel['cases'] if c['id']==pair)
        lane = next(l for l in panel['lanes'] if l['id']==policy)
        base = captures/pair/policy
        fit_path = base.parent/(policy+'-fit-receipt.json')
        fit = read(fit_path)
        events_path = base/'checkpoints/events.jsonl'
        if sha(events_path)!=fit['events_sha256']:raise ValueError('Changed committed events')
        import json
        events = [json.loads(line) for line in events_path.read_text().splitlines()]
        selected = [event for event in events if event['info']['levelIndex']==1 and event['info']['kind']==kind]
        if len(selected)!=1 or selected[0]['directory'] is None:raise ValueError('Missing unique declared checkpoint')
        event = selected[0]
        checkpoint = base/'checkpoints'/event['directory']
        if read(checkpoint/'checkpoint.json')!=event:raise ValueError('Checkpoint is not committed')
        paths = [fit_path,events_path,checkpoint/'checkpoint.json']
        for arm in ['fixed','moving']:
            paths += [checkpoint/('midpoint-'+arm+'-residual'+s) for s in ['.json','.f64','.valid-u8']]
            paths += [checkpoint/('midpoint-'+arm+'-affine.json')]
        for name in ['moving.nii.gz','fixed.nii.gz','fixed-mask.nii.gz']:
            path = Path(case['data'])/name
            if sha(path)!=sealed_inputs[str(path)]:raise ValueError('Prepared source input changed')
            paths.append(path)
        inputs.update({str(path):sha(path) for path in paths})
        info = event['info']
        tasks.append(dict(id=pair+'-'+policy+'-'+kind.lower(),pair=pair,policy=policy,event=event['serial'],
            kind=kind,checkpoint=str(checkpoint),data=case['data'],source_fit_exit_code=fit['exit_code'],
            source_fit_receipt_sha256=sha(fit_path),source_lane=lane,
            expected_loss=info['after']['loss'],damping=info['control']['damping']))
    sources = [Path(__file__),HERE/'trajectory_study.py',ROOT/'benchmarks/flashalign/run_real_mri_recovery.py',
               ROOT/'benchmarks/flashalign/real_image_court.py']
    output.mkdir(parents=True)
    for path in sources:shutil.copyfile(path,output/('source-'+path.name))
    protocol = dict(schema='hfq-spatial-chain-followup-v1',created_at=datetime.now(timezone.utc).isoformat(),
        pilot=str(pilot),pilot_receipt_sha256=sha(pilot/'receipt.json'),engine=str(pilot/'engine'),
        engine_freeze_sha256=sha(pilot/'engine/freeze.json'),source_sha256=source['sha256'],
        captures=str(captures),inputs=inputs,sources={str(p):sha(p) for p in sources},tasks=tasks,
        directions=prior['directions'],epsilons_mm=prior['epsilons_mm'],
        maximum_minimum_relative_derivative_error=prior['maximum_minimum_relative_derivative_error'],
        convergence_rule=prior['convergence_rule'],expected_loss_tolerance=prior['expected_loss_tolerance'],
        workers=1,timeout_seconds=3600,environment=panel['environment'],
        selection='Three fixed acquired-image states: fine end of the existing OASIS 11 step-scaled trajectory; fine start and end of the OASIS 13 selected-control trajectory. No output-based replacement or new optimizer policy.',
        success_rule='All three attempts must complete, reproduce their captured objective within 1e-12, retain the declared direction/epsilon ledger, and pass every spatial derivative criterion with complete support. Any failure remains in the denominator.',
        remaining_scope='Observed OASIS fine-scale replication only. Other cohorts, coarse levels, image order, boundary behavior and accumulated regularization remain required.',
        qualification_claim=False,runtime_comparison_allowed=False,candidate_policies_added=0)
    write(output/'protocol.json',protocol)
    print('FROZEN three additional states, unchanged engine/directions/epsilons/criteria',flush=True)


def run(output):
    protocol = read(output/'protocol.json')
    engine = Path(protocol['engine'])
    if sha(engine/'freeze.json')!=protocol['engine_freeze_sha256']:raise ValueError('Engine seal changed')
    _, source = verify_engine(engine)
    if source['sha256']!=protocol['source_sha256']:raise ValueError('Numerical source changed')
    for path,digest in (protocol['inputs']|protocol['sources']).items():
        if sha(path)!=digest:raise ValueError('Frozen input/source changed: '+path)
    env = os.environ.copy();env.update(protocol['environment'])
    rows = []
    for task in protocol['tasks']:
        target = output/task['id'];receipt = output/(task['id']+'-receipt.json')
        log = output/(task['id']+'.log')
        if receipt.exists():rows.append(read(receipt));continue
        if target.exists() or log.exists():raise FileExistsError('Unreceipted attempt retained: '+task['id'])
        command = [shutil.which('java'),'-Xmx6G','-XX:ActiveProcessorCount=1','-cp',
            (engine/'classpath.txt').read_text().strip(),'reframe4s.halfflow.RealImageObjectiveAudit',
            task['data']+'/moving.nii.gz',task['data']+'/fixed.nii.gz',task['data']+'/fixed-mask.nii.gz',
            task['checkpoint'],str(target),str(task['expected_loss']),str(task['damping'])]
        row = dict(task=task,command=command,protocol_sha256=sha(output/'protocol.json'),
                   analysis_completed=False,qualification_claim=False,runtime_comparison_allowed=False)
        print('START',task['id'],flush=True);started=time.monotonic()
        with log.open('x') as stream:
            process = subprocess.Popen(command,cwd=ROOT,env=env,stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
            try:row['exit_code']=process.wait(timeout=protocol['timeout_seconds'])
            except subprocess.TimeoutExpired:
                os.killpg(process.pid,signal.SIGTERM)
                try:process.wait(timeout=5)
                except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait()
                row.update(exit_code=124,failure='Declared diagnostic deadline exceeded')
        row.update(diagnostic_wall_seconds=time.monotonic()-started,log_sha256=sha(log))
        if row['exit_code']==0:
            try:
                initial,result=read(target/'initial.json'),read(target/'result.json')
                if not math.isfinite(initial['audit']['loss']) or abs(initial['audit']['loss']-task['expected_loss'])>protocol['expected_loss_tolerance']:
                    raise ValueError('Saved loss not reproduced')
                row.update(summarize(result,initial,protocol),analysis_completed=True,
                    initial_sha256=sha(target/'initial.json'),result_sha256=sha(target/'result.json'))
            except Exception as error:row['failure']=type(error).__name__+': '+str(error)
        write(receipt,row);rows.append(row)
        print('DONE',task['id'],'exit',row['exit_code'],'spatial',row.get('spatial_criterion_pass'),flush=True)
    write(output/'results.json',dict(rows=rows,
        spatial_replication_pass=all(r['exit_code']==0 and r['analysis_completed'] and r['spatial_criterion_pass'] for r in rows),
        qualification_claim=False,runtime_comparison_allowed=False))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('command',choices=['freeze','run']);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--pilot',type=Path);p.add_argument('--captures',type=Path)
    args=p.parse_args()
    if args.command=='freeze':
        if args.pilot is None or args.captures is None:p.error('freeze requires --pilot and --captures')
        freeze(args.output.resolve(),args.pilot.resolve(),args.captures.resolve())
    else:run(args.output.resolve())
