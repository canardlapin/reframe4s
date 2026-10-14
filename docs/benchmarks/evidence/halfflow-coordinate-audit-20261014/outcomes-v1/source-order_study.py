#!/usr/bin/env python3
"""Freeze and audit image/arm role swaps on three observed saved work grids."""
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
    'modules/reframe4s-halfflow/shared/src/test/scala/reframe4s/halfflow/HalfFlowCcEquivarianceSuite.scala',
    'benchmarks/flashalign/runner/src/main/scala/reframe4s/halfflow/RealImageOrderAudit.scala'}


def finite(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def summarize(initial, result, task, protocol):
    loss_limit = protocol['loss_tolerance']
    if any(result[k] != protocol[k] for k in ['loss_tolerance', 'gradient_absolute_tolerance',
                                             'gradient_relative_tolerance', 'epsilons_mm']):
        raise ValueError('Declared tolerances or perturbations changed')
    if (initial['level_index'], initial['shrink']) != (task['level_index'], task['shrink']):
        raise ValueError('Wrong captured level')
    a, b = initial['original_audit']['loss'], initial['swapped_audit']['loss']
    if not finite(a) or not finite(b) or abs(a-task['expected_loss']) > loss_limit:
        raise ValueError('Captured objective not reproduced')
    if initial['initial_loss_absolute_difference'] != abs(a-b):
        raise ValueError('Initial loss arithmetic differs')
    gradients = {}
    for name in ['production', 'spatial']:
        item = initial[name+'_antisymmetry']
        if any(not finite(item[k]) or item[k] < 0 for k in
               ['maximum_absolute_sum', 'relative_l2_sum', 'original_l2', 'swapped_l2']):
            raise ValueError('Invalid gradient measurement')
        if min(item['original_l2'], item['swapped_l2']) <= 0:
            raise ValueError('Missing derivative')
        passed = item['maximum_absolute_sum'] <= protocol['gradient_absolute_tolerance'] and \
            item['relative_l2_sum'] <= protocol['gradient_relative_tolerance']
        if item['passed'] != passed: raise ValueError('Gradient verdict differs')
        gradients[name] = passed
    swapped = initial['original_fixed_support'] == initial['swapped_moving_support'] and \
        initial['original_moving_support'] == initial['swapped_fixed_support']
    support = [initial[order+'_'+arm+'_support'] for order in ['original', 'swapped'] for arm in ['fixed', 'moving']]
    complete = all(s['unavailable'] == 0 and s['evaluated'] == s['nonzeroIntensityDerivatives'] and
                   s['evaluated'] > 0 for s in support)
    if swapped != initial['support_swapped_exact'] or complete != initial['complete_spatial_support']:
        raise ValueError('Support accounting differs')
    expected = [(d,e,s) for d in protocol['directions'] for e in protocol['epsilons_mm'] for s in [-1.0,1.0]]
    actual = [(r['direction'], r['epsilon_mm'], r['sign']) for r in result['rows']]
    if actual != expected: raise ValueError('Incomplete or substituted candidate ledger')
    for row in result['rows']:
        if not finite(row['original_loss']) or not finite(row['swapped_opposite_loss']):
            raise ValueError('Nonfinite candidate loss')
        error = abs(row['original_loss']-row['swapped_opposite_loss'])
        if error != row['absolute_difference'] or row['passed'] != (error <= loss_limit):
            raise ValueError('Candidate verdict arithmetic differs')
    maximum = max(r['absolute_difference'] for r in result['rows'])
    if maximum != result['maximum_candidate_loss_difference']: raise ValueError('Candidate maximum differs')
    passed = all(gradients.values()) and swapped and complete and initial['production_validity_swapped_exact'] and \
        abs(a-b) <= loss_limit and maximum <= loss_limit and result['original_current_loss_unchanged'] and \
        result['swapped_current_loss_unchanged']
    if result['passed'] != passed: raise ValueError('Aggregate verdict differs')
    return dict(passed=passed, candidate_pairs=len(expected), maximum_candidate_loss_difference=maximum,
        gradient_antisymmetry_passes=gradients, support_swapped_exact=swapped, complete_spatial_support=complete,
        interpretation='Same-work-grid role-swap identity only; not a derivative-accuracy, reverse-fit or anatomical-benefit result.')


def freeze(output, prior, classpath):
    if output.exists(): raise FileExistsError(output)
    previous, old_source = verify_engine(prior/'engine')
    completed = read(prior/'results.json')
    if not completed['all_states_completed'] or not completed['fine_neutrality_pass']:
        raise ValueError('Completed pyramid study required')
    tasks = []
    inputs = dict(previous['inputs'])
    inputs[str(prior/'results.json')] = sha(prior/'results.json')
    for name in STATES:
        task = dict(next(t for t in previous['tasks'] if t['id'] == name))
        baseline = prior/name/'initial.json'; task['baseline_initial'] = str(baseline)
        inputs[str(baseline)] = sha(baseline)
        tasks.append(task)
    paths = [Path(__file__), HERE/'objective_probe.py', HERE/'spatial_chain_followup.py',
        HERE/'archive_objective_probe.py', ROOT/'benchmarks/flashalign/run_real_mri_recovery.py',
        ROOT/'benchmarks/flashalign/real_image_court.py']
    protocol = dict(schema='hfq-order-study-v1', created_at=datetime.now(timezone.utc).isoformat(), tasks=tasks,
        cases=previous['cases'], inputs=inputs, sources={str(p): sha(p) for p in paths},
        loss_tolerance=1e-12, gradient_absolute_tolerance=1e-11, gradient_relative_tolerance=1e-10,
        directions=DIRECTIONS, epsilons_mm=EPSILONS, environment=previous['environment'], workers=1,
        timeout_seconds=3600, java_heap='6G', java_active_processors=1,
        selection='Fixed OASIS11 coarse end, OASIS13 fine end, MMRR11 fine start; no outcome-based replacement.',
        scope='Swap prepared images with their validity masks and saved arms on the same original work grid; compare opposite signed candidates. The original fixed fitting mask stays attached to its image. No optimizer, reference-grid exchange, anatomical labels or reverse fit.',
        analysis_rule='All three declared states and all 12 signed candidate pairs per state retained; reproduce prior complete original audit exactly; any missing support, changed validity, failed loss/gradient identity or process failure fails the three-state replication.',
        candidate_policies_added=0, qualification_claim=False, runtime_comparison_allowed=False)
    output.mkdir(parents=True); write(output/'declared-protocol.json', protocol)
    freeze_engine(output/'engine',output/'declared-protocol.json',classpath)
    _, source = verify_engine(output/'engine')
    changed = {name for name in set(source['files']) | set(old_source['files'])
               if source['files'].get(name) != old_source['files'].get(name)}
    if changed != ALLOWED: raise ValueError('Unexpected source delta: '+str(changed))
    source_delta(prior/'engine',output/'engine',output)
    delta = read(output/'source-delta.json')
    delta['base_reproduction'] = ('P01 control-source.tar.gz, D01 instrumentation.patch, spatial-chain source.patch, '
        'smaller-step-outcomes-v1/source.patch, then pyramid-chain declaration-v1/source.patch')
    (output/'source-delta.json').write_text(json.dumps(delta,indent=2)+'\n')
    for p in paths: shutil.copyfile(p,output/('source-'+p.name))
    write(output/'driver-seal.json',dict(protocol_sha256=sha(output/'declared-protocol.json'),
        engine_freeze_sha256=sha(output/'engine/freeze.json'),source_sha256=source['sha256']))
    print('FROZEN 3 role-swap states; 12 candidate pairs per state',flush=True)


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
            (output/'engine/classpath.txt').read_text().strip(),'reframe4s.halfflow.RealImageOrderAudit',
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
                initial,result = read(target/'initial.json'),read(target/'result.json')
                if initial['original_audit'] != read(Path(task['baseline_initial']))['audit']:
                    raise ValueError('Original pyramid audit differs')
                row.update(summary=summarize(initial,result,task,protocol),analysis_completed=True,
                    initial_sha256=sha(target/'initial.json'),result_sha256=sha(target/'result.json'),
                    original_audit_exact=True)
            except Exception as error: row['failure'] = type(error).__name__+': '+str(error)
        write(receipt,row); rows.append(row)
        print('DONE',task['id'],'exit',row['exit_code'],'analysis',row['analysis_completed'],flush=True)
    write(output/'results.json',dict(rows=rows,
        all_states_completed=all(r['exit_code']==0 and r['analysis_completed'] for r in rows),
        order_replication_pass=all(r.get('summary',{}).get('passed',False) for r in rows),
        qualification_claim=False,runtime_comparison_allowed=False))


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('command',choices=['freeze','run']); p.add_argument('--output',type=Path,required=True)
    p.add_argument('--prior',type=Path); p.add_argument('--classpath',type=Path)
    args = p.parse_args()
    if args.command == 'freeze':
        if args.prior is None or args.classpath is None: p.error('freeze requires --prior and --classpath')
        freeze(args.output.resolve(),args.prior.resolve(),args.classpath.resolve())
    else: run(args.output.resolve())
