#!/usr/bin/env python3
"""Freeze and replay observed HalfFlow policies with bounded checkpoint capture.

This driver runs frozen classes and helpers. Diagnostic timings cannot be used
as production performance estimates. All failures and rejected states remain.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT/'benchmarks/flashalign'))
from run_real_mri_recovery import freeze as freeze_engine, verify as verify_engine
from real_image_court import sha


def read(path):
    return json.loads(Path(path).read_text())


def write(path, value):
    with Path(path).open('x') as stream:
        json.dump(value, stream, indent=2); stream.write('\n')


def declared_panel():
    recovery = ROOT/'benchmarks/flashalign/local-results/real-mri-recovery-20261011'
    backtracking = ROOT/'benchmarks/flashalign/local-results/real-mri-backtracking-20261011/sentinels'
    old = read(backtracking/'protocol.json')
    cases = old['cases'][:]
    assert [c['id'] for c in cases] == ['oasis-11-to-12', 'oasis-13-to-14', 'mmrr-21-11-to-12']
    for case in cases:
        case['selection_reason'] = 'Previously observed refinement sentinel required by HFQ-D01'
        case['historical_outputs'] = {
            'selected-control': str(Path(case['previous_selected_report']).parent),
            'step-scaled-fine1.5': str(backtracking/case['id']/'step-scaled-fine1.5')}
    for phase in ['sentinels', 'development']:
        for case in read(recovery/phase/'protocol.json')['cases']:
            if case['id'] in ['oasis-1-to-10', 'oasis-17-to-18']:
                case['selection_reason'] = ('Predeclared representative selected-policy success: '
                    'both directions previously improved Dice and median-label HD95 over the saved affine')
                case['historical_outputs'] = {'selected-control': str(recovery/phase/case['id']/'hf40-total1.5-boundary6')}
                cases.append(case)
    assert len(cases) == 5 and len({c['id'] for c in cases}) == 5
    control = read(ROOT/'benchmarks/halfflow/qualification/protocol-v1.json')['control']
    lanes = [{'id':'selected-control', 'mode':control['mode'], 'flags':control['flags']},
             next(lane for lane in old['lanes'] if lane['id']=='step-scaled-fine1.5')]
    # These hashes are used to test exact replay, not to select a policy.
    for case in cases:
        case['expected_outputs'] = {}
        for lane, directory in case['historical_outputs'].items():
            maps = {name:sha(Path(directory)/name) for name in [
                'halfflow-fixed-to-moving.f64', 'halfflow-moving-to-fixed.f64',
                'halfflow-fixed-to-moving.valid-u8', 'halfflow-moving-to-fixed.valid-u8']}
            case['expected_outputs'][lane] = maps
    return cases, lanes


def freeze(study, classpath, prior_study=None):
    if study.exists():
        raise FileExistsError(study)
    cases, lanes = declared_panel()
    study.mkdir(parents=True)
    protocol = {
        'schema':'hfq-d01-trajectories-v1', 'created_at':datetime.now(timezone.utc).isoformat(),
        'purpose':'Observed-data diagnosis of selected and completed-but-anatomically-worse refinement; no new policy tuning',
        'cases':cases, 'lanes':lanes, 'workers':1,
        'checkpoint_policy':{'accepted_steps':[1,2,5,10,15,20], 'rejected_attempts':[1,2,6],
                             'level_start_and_end':True, 'maximum_states':24},
        'fixed_audit_contract_sha256':sha(HERE/'audit-contract-v1.json'),
        'fit_timeout_seconds':3600, 'java_heap':'6G', 'java_active_processors':1,
        'environment':{'OMP_NUM_THREADS':'1','OPENBLAS_NUM_THREADS':'1','ITK_GLOBAL_DEFAULT_NUMBER_OF_THREADS':'1',
                       'MKL_NUM_THREADS':'1','VECLIB_MAXIMUM_THREADS':'1'},
        'timing_scope':'Diagnostic export overhead included; unusable for production runtime comparison',
        'selection':'All declared states and failures retained. Label-selected best checkpoints are post-hoc diagnostics only, never runtime selection.',
        'qualification_claim':False, 'candidate_policies_added':0,
    }
    if prior_study is not None:
        prior, _ = verify(prior_study)
        if prior['cases'] != cases or prior['lanes'] != lanes or prior['checkpoint_policy'] != protocol['checkpoint_policy']:
            raise ValueError('Deferred-export replay must retain the original panel and capture policy')
        receipts = [prior_study/c['id']/(lane['id']+'-fit-receipt.json') for c in cases for lane in lanes]
        if not all(path.exists() for path in receipts):
            raise ValueError('Original attempt ledger must be terminal before replay')
        protocol.update(schema='hfq-d01-deferred-trajectories-v1', defer_native_export=True,
            original_protocol_sha256=sha(prior_study/'engine/protocol.json'),
            original_attempts={str(path):sha(path) for path in receipts},
            timing_scope='Raw diagnostic capture within fit; native checkpoint export deferred to a separate receipted phase. All times excluded from production comparisons.',
            export_policy='Ordinary final export remains within the unchanged one-hour fit deadline. Every captured state receives an independent offline export receipt; deferred states are not admitted maps.',
            runtime_comparison_allowed=False)
    declared = study/'declared-protocol.json'; write(declared, protocol)
    engine = study/'engine'
    freeze_engine(engine, declared, classpath)
    verify_engine(engine)
    extra = list(HERE.glob('*.py'))+[HERE/'audit-contract-v1.json']
    extra += list((ROOT/'benchmarks/halfflow/reference').glob('*.py'))
    hashes = {}
    for path in extra:
        relative = path.relative_to(ROOT)
        target = engine/'frozen-source'/relative
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            assert sha(target) == sha(path), str(relative)
        else:
            shutil.copyfile(path, target)
        hashes[str(relative)] = sha(target)
    manifest = {'schema':'hfq-checkpoint-selection-v1', 'policy':protocol['checkpoint_policy'],
                'protocol_sha256':sha(engine/'protocol.json'), 'engine_freeze_sha256':sha(engine/'freeze.json'),
                'diagnostic_only':True, 'labels_used_for_capture':False}
    write(study/'checkpoint-manifest.json',manifest)
    write(study/'diagnostic-seal.json', {
        'schema':'hfq-d01-seal-v1','created_at':datetime.now(timezone.utc).isoformat(),
        'engine_freeze_sha256':sha(engine/'freeze.json'), 'extra_sources':hashes,
        'checkpoint_manifest_sha256':sha(study/'checkpoint-manifest.json'),
        'base_commit':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
        'verification_logs':{str(p):sha(p) for p in (study.parent/'verification').glob('*.log')},
        'qualification_claim':False})
    print('FROZEN',len(cases),'observed pairs',len(lanes),'existing policies',flush=True)


def verify(study):
    engine=study/'engine'; seal=read(study/'diagnostic-seal.json')
    if sha(engine/'freeze.json') != seal['engine_freeze_sha256']:
        raise ValueError('Engine seal changed')
    protocol, source=verify_engine(engine)
    for name,digest in seal['extra_sources'].items():
        if sha(engine/'frozen-source'/name)!=digest:
            raise ValueError('Diagnostic source changed: '+name)
    if sha(study/'checkpoint-manifest.json')!=seal['checkpoint_manifest_sha256']:
        raise ValueError('Checkpoint selection changed')
    return protocol,source


def require_control_replay(row):
    replay=row.get('historical_replay',{})
    expected={'halfflow-'+direction+suffix for direction in ['fixed-to-moving','moving-to-fixed']
              for suffix in ['.f64','.valid-u8']}
    if row['exit_code']!=0 or set(replay)!=expected or not all(replay.values()):
        raise RuntimeError('Required initial control neutrality replay failed; panel expansion stopped')


def run(study, limit=None):
    protocol,source=verify(study)
    prior_control=study/'oasis-11-to-12/selected-control-fit-receipt.json'
    if prior_control.exists():require_control_replay(read(prior_control))
    engine=study/'engine'; frozen=engine/'frozen-source'
    # Restart from the sealed driver, so later working-tree edits cannot change this run.
    driver=frozen/'benchmarks/halfflow/diagnostics/trajectory_study.py'
    if Path(__file__).resolve()!=driver.resolve():
        command=[sys.executable,str(driver),'run','--study',str(study)]
        if limit is not None: command += ['--limit',str(limit)]
        raise SystemExit(subprocess.call(command))
    env=os.environ.copy();env.update(protocol['environment'])
    cp=(engine/'classpath.txt').read_text().strip()
    completed=0
    for case in protocol['cases']:
        for lane in protocol['lanes']:
            parent=study/case['id']; parent.mkdir(exist_ok=True)
            target=parent/lane['id']; receipt=parent/(lane['id']+'-fit-receipt.json')
            if receipt.exists(): continue
            if target.exists():
                raise FileExistsError('Unreceipted prior attempt retained; inspect before a fresh declared attempt: '+str(target))
            capture=protocol['checkpoint_policy']
            cmd=[shutil.which('java'),'-Xmx6G','-XX:ActiveProcessorCount=1','-cp',cp,
                 'reframe4s.halfflow.RealImageRegistration',case['data']+'/moving.nii.gz',
                 case['data']+'/fixed.nii.gz',case['data']+'/fixed-mask.nii.gz',str(target),
                 lane['mode'],case['saved_affine'],*lane['flags'],
                 '--checkpoint-accepted',','.join(map(str,capture['accepted_steps'])),
                 '--checkpoint-rejected',','.join(map(str,capture['rejected_attempts'])),
                 '--checkpoint-max-states',str(capture['maximum_states']),
                 '--checkpoint-manifest',str(study/'checkpoint-manifest.json')]
            if protocol.get('defer_native_export'):
                cmd += ['--checkpoint-defer-export','true']
            measurement=parent/(lane['id']+'-measurement.json')
            wrapper=frozen/'benchmarks/halfflow/reference/measure_process.py'
            log=parent/(lane['id']+'-fit.log')
            row={'schema':'hfq-trajectory-fit-v1','pair':case['id'],'lane':lane['id'],'command':cmd,
                 'protocol_sha256':sha(engine/'protocol.json'),'diagnostic_seal_sha256':sha(study/'diagnostic-seal.json'),
                 'source_sha256':source['sha256'],'qualification_claim':False,'runtime_comparison_allowed':False}
            print('START',case['id'],lane['id'],flush=True)
            start=time.monotonic()
            with log.open('x') as stream:
                process=subprocess.Popen([sys.executable,str(wrapper),'--receipt',str(measurement),'--',*cmd],
                    env=env,stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
                try:
                    row['exit_code']=process.wait(timeout=protocol['fit_timeout_seconds'])
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid,signal.SIGTERM)
                    try:process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid,signal.SIGKILL);process.wait()
                    row['exit_code']=124;row['failure']='Declared diagnostic fit deadline exceeded'
            row['diagnostic_wall_seconds']=time.monotonic()-start
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
            if case['id']=='oasis-11-to-12' and lane['id']=='selected-control':
                require_control_replay(row)
            completed+=1
            if limit is not None and completed>=limit:return


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['freeze','run'])
    parser.add_argument('--study',type=Path,required=True)
    parser.add_argument('--classpath',type=Path)
    parser.add_argument('--limit',type=int)
    parser.add_argument('--prior-study',type=Path,help='Replay a terminal panel with native checkpoint export deferred')
    args=parser.parse_args()
    if args.limit is not None and args.limit<1:parser.error('--limit must be positive')
    if args.command=='freeze':
        if args.classpath is None:parser.error('--classpath required to freeze')
        freeze(args.study.resolve(),args.classpath.resolve(),args.prior_study.resolve() if args.prior_study else None)
    else:run(args.study.resolve(),args.limit)
