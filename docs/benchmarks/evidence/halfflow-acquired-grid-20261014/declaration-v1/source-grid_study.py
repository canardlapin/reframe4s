#!/usr/bin/env python3
"""Freeze and audit exact source-grid reindexing on three observed MRI checkpoints."""
import argparse
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time

from objective_probe import ROOT, freeze_engine, verify_engine
from spatial_chain_followup import read, write, sha
from archive_objective_probe import source_delta

HERE = Path(__file__).resolve().parent
STATES = ['oasis-11-to-12-level0-levelend', 'oasis-13-to-14-level1-levelend',
          'mmrr-21-11-to-12-level1-levelstart']
DIRECTIONS = ['translation', 'smooth-wave', 'negative-gradient']
EPSILONS = [.01, .0001]
ALLOWED = {
    'benchmarks/flashalign/runner/src/test/scala/reframe4s/halfflow/RealImageGridAuditSuite.scala',
    'benchmarks/flashalign/runner/src/main/scala/reframe4s/halfflow/RealImageGridAudit.scala'}


def finite(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def summarize(initial, result, task, protocol):
    limit = protocol['loss_tolerance']
    for key in ['loss_tolerance','gradient_absolute_tolerance','gradient_relative_tolerance','epsilons_mm']:
        if result[key] != protocol[key]: raise ValueError('Declared criterion changed')
    if (initial['level_index'],initial['shrink']) != (task['level_index'],task['shrink']):
        raise ValueError('Wrong captured level')
    loss = initial['original_audit']['loss']
    if not finite(loss) or abs(loss-task['expected_loss'])>limit: raise ValueError('Captured loss differs')
    if [r['variant'] for r in result['variants']] != protocol['variants']:
        raise ValueError('Incomplete or substituted variant ledger')
    summaries = []
    expected = [(d,e,s) for d in protocol['directions'] for e in protocol['epsilons_mm'] for s in [-1.0,1.0]]
    for variant in result['variants']:
        name=variant['variant']; gradients={}
        for arm in ['fixed','moving']:
            axes=protocol['reindexing'][arm]['axes'] if name in [arm+'-reindexed','both-reindexed'] else [0,1,2]
            if variant[arm+'_shape'] != [initial[arm+'_shape'][i] for i in axes]:
                raise ValueError('Wrong reindexed shape')
        for kind in ['production','spatial']:
            item=variant[kind+'_agreement']
            if any(not finite(item[k]) or item[k]<0 for k in
                   ['maximum_absolute_difference','relative_l2_difference','original_l2','reindexed_l2']):
                raise ValueError('Invalid gradient measurement')
            if min(item['original_l2'],item['reindexed_l2'])<=0: raise ValueError('Missing derivative')
            passed=item['maximum_absolute_difference']<=protocol['gradient_absolute_tolerance'] and \
                item['relative_l2_difference']<=protocol['gradient_relative_tolerance']
            if item['passed'] != passed: raise ValueError('Gradient verdict differs')
            gradients[kind]=passed
        support=[initial[a+'_support'] for a in ['fixed','moving']]+[variant[a+'_support'] for a in ['fixed','moving']]
        complete=all(s['unavailable']==0 and s['evaluated']==s['nonzeroIntensityDerivatives'] and s['evaluated']>0 for s in support)
        exact=all(initial[a+'_support']==variant[a+'_support'] for a in ['fixed','moving'])
        if complete!=variant['complete_spatial_support'] or exact!=variant['support_exact']:
            raise ValueError('Support accounting differs')
        if [(r['direction'],r['epsilon_mm'],r['sign']) for r in variant['rows']]!=expected:
            raise ValueError('Incomplete or substituted candidate ledger')
        for row in variant['rows']:
            if row['variant']!=name or not finite(row['original_loss']) or not finite(row['reindexed_loss']):
                raise ValueError('Invalid candidate')
            error=abs(row['original_loss']-row['reindexed_loss'])
            if row['absolute_difference']!=error or row['passed']!=(error<=limit):
                raise ValueError('Candidate arithmetic differs')
        maximum=max(r['absolute_difference'] for r in variant['rows'])
        reindexed_loss=variant['audit']['loss']
        if not finite(reindexed_loss): raise ValueError('Invalid reindexed loss')
        initial_difference=abs(loss-reindexed_loss)
        if initial_difference!=variant['initial_loss_absolute_difference']: raise ValueError('Initial loss arithmetic differs')
        passed=all(gradients.values()) and exact and complete and variant['production_validity_exact'] and \
            initial_difference<=limit and variant['current_loss_unchanged'] and maximum<=limit
        if passed!=variant['passed']: raise ValueError('Variant verdict differs')
        summaries.append(dict(variant=name,passed=passed,candidate_pairs=len(expected),
            maximum_candidate_loss_difference=maximum,initial_loss_absolute_difference=initial_difference,
            gradient_agreement_passes=gradients,support_exact=exact,complete_spatial_support=complete))
    passed=all(v['passed'] for v in summaries) and result['original_current_loss_unchanged']
    if passed!=result['passed']: raise ValueError('Aggregate verdict differs')
    return dict(passed=passed,variants=summaries,candidate_pairs=sum(v['candidate_pairs'] for v in summaries),
        interpretation='Prepared source-grid representation invariance only; not reference-grid exchange, reverse fitting, boundary admission or anatomical benefit.')


def verify_measurements(target, task):
    initial, result = read(target/'initial.json'), read(target/'result.json')
    if initial['original_audit'] != read(Path(task['baseline_initial']))['original_audit']:
        raise ValueError('Original role-audit baseline differs')
    baseline = read(Path(task['baseline_result']))['rows']
    expected = [(r['direction'],r['epsilon_mm'],r['sign'],r['original_loss']) for r in baseline]
    for variant in result['variants']:
        if variant != read(target/(variant['variant']+'.json')):
            raise ValueError('Variant report differs')
        if [(r['direction'],r['epsilon_mm'],r['sign'],r['original_loss']) for r in variant['rows']] != expected:
            raise ValueError('Original signed candidate losses differ')
    recorded = [json.loads(line) for line in (target/'curve.jsonl').read_text().splitlines()]
    if recorded != [r for v in result['variants'] for r in v['rows']]:
        raise ValueError('Curve ledger differs')
    return initial, result


def freeze(output, prior, base, classpath):
    if output.exists(): raise FileExistsError(output)
    previous, _ = verify_engine(prior/'engine')
    _, old_source = verify_engine(base)
    completed = read(prior/'results.json')
    if not completed['all_states_completed'] or not completed['order_replication_pass']:
        raise ValueError('Completed role-swap study required')
    tasks = []
    inputs = dict(previous['inputs'])
    inputs[str(prior/'results.json')] = sha(prior/'results.json')
    for name in STATES:
        task = dict(next(t for t in previous['tasks'] if t['id'] == name))
        baseline = prior/name/'initial.json'; task['baseline_initial'] = str(baseline)
        inputs[str(baseline)] = sha(baseline)
        baseline_result = prior/name/'result.json'; task['baseline_result'] = str(baseline_result)
        inputs[str(baseline_result)] = sha(baseline_result)
        tasks.append(task)
    paths = [Path(__file__), HERE/'archive_grid_study.py', HERE/'test_grid_study.py',
        HERE/'objective_probe.py', HERE/'spatial_chain_followup.py',
        HERE/'archive_objective_probe.py', ROOT/'benchmarks/flashalign/run_real_mri_recovery.py',
        ROOT/'benchmarks/flashalign/real_image_court.py']
    protocol = dict(schema='hfq-grid-study-v1', created_at=datetime.now(timezone.utc).isoformat(), tasks=tasks,
        cases=previous['cases'], inputs=inputs, sources={str(p): sha(p) for p in paths},
        loss_tolerance=1e-12, gradient_absolute_tolerance=1e-11, gradient_relative_tolerance=1e-10,
        variants=['fixed-reindexed','moving-reindexed','both-reindexed'],
        reindexing={'fixed': {'axes':[2,0,1],'signs':[-1,1,1]}, 'moving':{'axes':[1,0,2],'signs':[1,-1,1]}},
        directions=DIRECTIONS, epsilons_mm=EPSILONS, environment=previous['environment'], workers=1,
        timeout_seconds=3600, java_heap='6G', java_active_processors=1,
        selection='Fixed OASIS11 coarse end, OASIS13 fine end, MMRR11 fine start; no outcome-based replacement.',
        scope='Exactly reindex prepared fixed, moving or both source images and validity masks with independent voxel axis permutations/reflections. Physical samples, work grid, residuals and affines unchanged. No resampling, optimizer, reference-grid exchange, labels or reverse fit.',
        analysis_rule='Retain all three states, all three variants and all twelve signed candidate comparisons per variant (108 total). Original complete audits must reproduce exactly. Any missing support, changed validity, failed loss/gradient identity or process failure fails this replication.',
        candidate_policies_added=0, qualification_claim=False, runtime_comparison_allowed=False)
    output.mkdir(parents=True); write(output/'declared-protocol.json', protocol)
    freeze_engine(output/'engine',output/'declared-protocol.json',classpath)
    _, source = verify_engine(output/'engine')
    changed = {name for name in set(source['files']) | set(old_source['files'])
               if source['files'].get(name) != old_source['files'].get(name)}
    if changed != ALLOWED: raise ValueError('Unexpected source delta: '+str(changed))
    source_delta(base,output/'engine',output)
    delta = read(output/'source-delta.json')
    delta['base_reproduction'] = 'Reconstruct the spatial-direction declaration source from its documented patch chain, then apply this source.patch.'
    (output/'source-delta.json').write_text(json.dumps(delta,indent=2)+'\n')
    for p in paths: shutil.copyfile(p,output/('source-'+p.name))
    write(output/'driver-seal.json',dict(protocol_sha256=sha(output/'declared-protocol.json'),
        engine_freeze_sha256=sha(output/'engine/freeze.json'),source_sha256=source['sha256']))
    print('FROZEN 3 acquired-grid states; 108 candidate comparisons',flush=True)


def run(output):
    protocol, source = verify_engine(output/'engine'); seal = read(output/'driver-seal.json')
    if sha(output/'engine/freeze.json') != seal['engine_freeze_sha256'] or \
            sha(output/'declared-protocol.json') != seal['protocol_sha256']:
        raise ValueError('Study seal changed')
    for path, digest in (protocol['inputs'] | protocol['sources']).items():
        if sha(path) != digest: raise ValueError('Frozen source/input changed: '+path)
    env = os.environ.copy(); env.update(protocol['environment']); rows = []
    for task in protocol['tasks']:
        target = output/task['id']; receipt = output/(task['id']+'-receipt.json'); log = output/(task['id']+'.log')
        if target.exists() or receipt.exists() or log.exists(): raise FileExistsError('Prior attempt retained')
        command = [shutil.which('java'),'-Xmx6G','-XX:ActiveProcessorCount=1','-cp',
            (output/'engine/classpath.txt').read_text().strip(),'reframe4s.halfflow.RealImageGridAudit',
            task['data']+'/moving.nii.gz',task['data']+'/fixed.nii.gz',task['data']+'/fixed-mask.nii.gz',
            task['checkpoint'],str(target),str(task['expected_loss']),str(task['level_index'])]
        row = dict(task=task,command=command,protocol_sha256=seal['protocol_sha256'],
            source_sha256=source['sha256'],analysis_completed=False,qualification_claim=False,runtime_comparison_allowed=False)
        started = time.monotonic(); print('START',task['id'],flush=True)
        with log.open('x') as stream:
            process = subprocess.Popen(command,cwd=ROOT,env=env,stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
            try: row['exit_code'] = process.wait(timeout=protocol['timeout_seconds'])
            except subprocess.TimeoutExpired:
                os.killpg(process.pid,signal.SIGTERM)
                try: process.wait(timeout=5)
                except subprocess.TimeoutExpired: os.killpg(process.pid,signal.SIGKILL); process.wait()
                row.update(exit_code=124,failure='Declared diagnostic deadline exceeded')
        row.update(log_sha256=sha(log),diagnostic_wall_seconds=time.monotonic()-started)
        if row['exit_code'] == 0:
            try:
                initial,result = verify_measurements(target,task)
                row.update(summary=summarize(initial,result,task,protocol),analysis_completed=True,
                    initial_sha256=sha(target/'initial.json'),result_sha256=sha(target/'result.json'),
                    detail_sha256={p.name:sha(p) for p in target.iterdir() if p.is_file()},
                    original_audit_exact=True)
            except Exception as error: row['failure'] = type(error).__name__+': '+str(error)
        write(receipt,row); rows.append(row)
        print('DONE',task['id'],'exit',row['exit_code'],'analysis',row['analysis_completed'],flush=True)
    write(output/'results.json',dict(rows=rows,
        all_states_completed=all(r['exit_code']==0 and r['analysis_completed'] for r in rows),
        grid_replication_pass=all(r.get('summary',{}).get('passed',False) for r in rows),
        qualification_claim=False,runtime_comparison_allowed=False))


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('command',choices=['freeze','run']); p.add_argument('--output',type=Path,required=True)
    p.add_argument('--prior',type=Path); p.add_argument('--base',type=Path); p.add_argument('--classpath',type=Path)
    args = p.parse_args()
    if args.command == 'freeze':
        if args.prior is None or args.base is None or args.classpath is None: p.error('freeze requires --prior, --base and --classpath')
        freeze(args.output.resolve(),args.prior.resolve(),args.base.resolve(),args.classpath.resolve())
    else: run(args.output.resolve())
