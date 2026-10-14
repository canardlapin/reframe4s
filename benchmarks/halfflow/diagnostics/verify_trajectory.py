#!/usr/bin/env python3
"""Check checkpoint identity, event continuity and exact final-map replay.

This verifies saved artifacts independently of the observer's in-memory tests.
It never upgrades geometry or convergence status.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path


def read(path):return json.loads(Path(path).read_text())


def sha(path):
    digest=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''):digest.update(chunk)
    return digest.hexdigest()


def verify(study,case,policy):
    base=study/case/policy
    fit_path=base.parent/(policy+'-fit-receipt.json')
    fit=read(fit_path);protocol=read(study/'engine/protocol.json')
    events_path=base/'checkpoints/events.jsonl'
    events=[json.loads(line) for line in events_path.read_text().splitlines()]
    failures=[];captured=0;levels={};artifact_hashes={}
    def check(condition,message):
        if not condition:failures.append(message)
    check(fit['exit_code']==0,'Fit did not complete successfully')
    check(sha(events_path)==fit.get('events_sha256'),'Event stream differs from the completed fit receipt')
    check(sha(study/'engine/protocol.json')==fit['protocol_sha256'],'Fit protocol identity mismatch')
    check(sha(study/'diagnostic-seal.json')==fit['diagnostic_seal_sha256'],'Fit source seal identity mismatch')
    checkpoint_hash=sha(study/'checkpoint-manifest.json')
    check(sha(base/'checkpoints/manifest.json')==checkpoint_hash,'Captured manifest copy differs')
    for serial,event in enumerate(events,1):
        info=event['info'];level=info['levelIndex'];kind=info['kind']
        check(event['serial']==serial,f'Event serial is not contiguous at {serial}')
        check(event['manifest_sha256']==checkpoint_hash,f'Manifest identity mismatch at event {serial}')
        previous=levels.get(level)
        if previous is None:
            check(kind=='LevelStart' and info['attempts']==0 and info['acceptedSteps']==0,
                  f'Level {level} does not start at declared zero counters')
        elif kind in ['Accepted','Rejected']:
            check(info['attempts']==previous['attempts']+1,f'Attempt counter discontinuity at event {serial}')
            check(info['acceptedSteps']==previous['acceptedSteps']+(kind=='Accepted'),f'Accepted counter discontinuity at event {serial}')
            check(info['before']['loss']==previous['after']['loss'],f'Objective continuity mismatch at event {serial}')
            trial=info['trial']
            check(trial is not None,f'Missing trial diagnostics at event {serial}')
            if trial:
                check(trial['accepted']==(kind=='Accepted'),f'Trial/event verdict mismatch at event {serial}')
                check(trial['currentLoss']==info['before']['loss'],f'Frozen current loss mismatch at event {serial}')
                if kind=='Accepted':
                    candidate=trial['candidateLoss']
                    check(candidate is not None and math.isfinite(candidate) and candidate<info['before']['loss'],
                          f'Accepted trial lacks a finite frozen loss reduction at event {serial}')
                else:check(info['after']==info['before'],f'Rejected trial changed current objective at event {serial}')
        elif kind=='LevelEnd':
            check(info['attempts']==previous['attempts'] and info['acceptedSteps']==previous['acceptedSteps'],
                  f'Level-end counters changed at event {serial}')
            check(info['before']==previous['after'] and info['after']==previous['after'],
                  f'Level-end objective changed at event {serial}')
            check(info['termination'] is not None,f'Missing termination at event {serial}')
        else:check(False,f'Unexpected level event order at event {serial}')
        levels[level]=info
        if event['directory'] is not None:
            captured+=1
            check(event['capture_status']=='Captured',f'Directory with noncaptured status at event {serial}')
            directory=base/'checkpoints'/event['directory']
            check(read(directory/'checkpoint.json')==event,f'Checkpoint record differs from event stream at {serial}')
            check((directory/'export-status.txt').read_text().strip()==event['export_status'],
                  f'Export status differs at event {serial}')
            for arm in ['fixed','moving']:
                name='midpoint-'+arm+'-residual';meta=read(directory/(name+'.json'))
                voxels=math.prod(meta['shape'])
                check((directory/(name+'.f64')).stat().st_size==24*voxels,f'Wrong coordinate size at event {serial}/{arm}')
                check((directory/(name+'.valid-u8')).stat().st_size==voxels,f'Wrong validity size at event {serial}/{arm}')
                check((directory/(name+'-warped.f64')).stat().st_size==8*voxels,f'Wrong warped size at event {serial}/{arm}')
                for suffix in ['.json','.f64','.valid-u8']:
                    path=directory/(name+suffix);artifact_hashes[str(path.relative_to(base))]=sha(path)
                affine=read(directory/('midpoint-'+arm+'-affine.json'))
                check(len(affine)==16 and all(math.isfinite(v) for v in affine),f'Invalid affine at event {serial}/{arm}')
        else:check(event['capture_status']!='Captured',f'Captured event lacks directory at {serial}')
    check(captured<=protocol['checkpoint_policy']['maximum_states'],'Hard cross-level snapshot budget exceeded')
    check(captured==fit.get('saved_states'),'Captured count differs from fit receipt')
    check(len(events)==fit.get('events'),'Scalar event count differs from fit receipt')
    for level,info in levels.items():check(info['kind']=='LevelEnd',f'Level {level} lacks terminal event')
    final=[e for e in events if e['info']['kind']=='LevelEnd'][-1]
    check(final['directory'] is not None,'Final state was not captured')
    matches={}
    if final['directory'] is not None:
        directory=base/'checkpoints'/final['directory']
        for direction in ['fixed-to-moving','moving-to-fixed']:
            for suffix in ['.json','.f64','.valid-u8','-warped.f64']:
                name='halfflow-'+direction+suffix
                matched=(base/name).exists() and (directory/name).exists() and sha(base/name)==sha(directory/name)
                matches[name]=matched;check(matched,'Final checkpoint differs from final export: '+name)
    return {'schema':'hfq-trajectory-verification-v1','case':case,'policy':policy,
            'verifier_sha256':sha(Path(__file__)),'fit_receipt_sha256':sha(fit_path),
            'event_count':len(events),'captured_states':captured,'final_checkpoint_matches_export':matches,
            'raw_artifact_sha256':artifact_hashes,'failures':failures,'pass':not failures,
            'geometry_or_convergence_claim':False}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--study',type=Path,required=True);parser.add_argument('--case',required=True)
    parser.add_argument('--policy',required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    if args.output.exists():raise FileExistsError(args.output)
    report=verify(args.study.resolve(),args.case,args.policy)
    with args.output.open('x') as stream:json.dump(report,stream,indent=2);stream.write('\n')
    print(json.dumps({k:v for k,v in report.items() if k!='raw_artifact_sha256'}))
    if not report['pass']:raise SystemExit(2)
