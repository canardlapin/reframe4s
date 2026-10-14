#!/usr/bin/env python3
"""Separately declare smaller-step diagnosis of the failed spatial replication."""
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

from spatial_chain_followup import read, write, sha, summarize
from objective_probe import ROOT, freeze_engine, verify_engine
from archive_objective_probe import source_delta

HERE = Path(__file__).resolve().parent
NEW_STEPS = [.001, .0005, .0002, .0001, .00005, .00002]
TASK_ID = 'oasis-13-to-14-selected-control-levelend'
ALLOWED_CHANGES = {
    'benchmarks/flashalign/runner/src/main/scala/reframe4s/halfflow/RealImageObjectiveAudit.scala',
    'benchmarks/flashalign/runner/src/test/scala/reframe4s/halfflow/RealImageObjectiveAuditSuite.scala'}


def freeze(output, prior, classpath):
    if output.exists(): raise FileExistsError(output)
    old = read(prior/'protocol.json')
    receipt = read(prior/(TASK_ID+'-receipt.json'))
    if receipt['exit_code'] != 0 or not receipt['analysis_completed'] or receipt['spatial_criterion_pass']:
        raise ValueError('Expected the completed failed spatial replication')
    if receipt['protocol_sha256'] != sha(prior/'protocol.json'):
        raise ValueError('Original protocol changed')
    original_result = prior/TASK_ID/'result.json'
    if sha(original_result) != receipt['result_sha256']:
        raise ValueError('Original derivative measurements changed')
    for name, digest in old['inputs'].items():
        if sha(name) != digest: raise ValueError('Original input changed')
    task = receipt['task']
    if task != next(t for t in old['tasks'] if t['id'] == TASK_ID):
        raise ValueError('Original checkpoint identity changed')
    inputs = old['inputs'] | {str(p): sha(p) for p in [prior/'protocol.json',
        prior/(TASK_ID+'-receipt.json'), original_result, prior/TASK_ID/'initial.json']}
    sources = [Path(__file__), HERE/'spatial_chain_followup.py', HERE/'objective_probe.py',
        HERE/'archive_objective_probe.py', HERE/'trajectory_study.py',
        ROOT/'benchmarks/flashalign/run_real_mri_recovery.py', ROOT/'benchmarks/flashalign/real_image_court.py']
    protocol = dict(schema='hfq-spatial-smaller-step-diagnostic-v1',
        created_at=datetime.now(timezone.utc).isoformat(), task=task,
        cases=[dict(id=task['pair'], data=task['data'])],
        prior=str(prior), original_result=str(original_result), inputs=inputs,
        directions=old['directions'], original_epsilons_mm=old['epsilons_mm'],
        new_epsilons_mm=NEW_STEPS, epsilons_mm=old['epsilons_mm']+NEW_STEPS,
        expected_loss_tolerance=old['expected_loss_tolerance'],
        maximum_minimum_relative_derivative_error=old['maximum_minimum_relative_derivative_error'],
        convergence_rule=old['convergence_rule'],
        sources={str(p): sha(p) for p in sources}, environment=old['environment'],
        workers=1, timeout_seconds=3600, candidate_policies_added=0,
        question='Does the finite difference approach the fixed spatial derivative below the original 0.002mm minimum step?',
        original_replication_pass=False,
        analysis_rule='Reproduce all original 24 measurements exactly. Evaluate all three directions at the six new steps with the unchanged 0.005 symmetric relative error and three-point decreasing-error rule. Retain every outcome. This extension never changes the failed original replication verdict.',
        interpretation_limit='Small-step agreement can support an asymptotic derivative at this state; it cannot establish useful finite-step accuracy, a globally smooth derivative or anatomical benefit. Failure does not by itself locate the remaining defect.',
        qualification_claim=False, runtime_comparison_allowed=False)
    output.mkdir(parents=True)
    write(output/'declared-protocol.json', protocol)
    freeze_engine(output/'engine', output/'declared-protocol.json', classpath)
    _, engine = verify_engine(output/'engine')
    _, original_engine = verify_engine(Path(old['engine']))
    changed = {name for name in set(engine['files']) | set(original_engine['files'])
               if engine['files'].get(name) != original_engine['files'].get(name)}
    if changed != ALLOWED_CHANGES:
        raise ValueError('Unexpected numerical source delta: '+str(changed))
    source_delta(Path(old['engine']), output/'engine', output)
    delta = read(output/'source-delta.json')
    delta['base_reproduction'] = ('P01 control-source.tar.gz, then D01 instrumentation.patch, '
        'then halfflow-spatial-chain-20261014/source.patch; this patch changes only the audit runner and its test')
    (output/'source-delta.json').write_text(json.dumps(delta, indent=2)+'\n')
    for path in sources: shutil.copyfile(path, output/('source-'+path.name))
    write(output/'driver-seal.json', dict(engine_freeze_sha256=sha(output/'engine/freeze.json'),
        protocol_sha256=sha(output/'declared-protocol.json'), sources=protocol['sources'],
        changed_source_files=sorted(changed), source_sha256=engine['sha256']))
    print('FROZEN: failed state, all original 24 measurements, 18 additional measurements; unchanged derivative criterion', flush=True)


def run(output):
    seal = read(output/'driver-seal.json')
    if sha(output/'engine/freeze.json') != seal['engine_freeze_sha256']:
        raise ValueError('Frozen engine changed')
    protocol, source = verify_engine(output/'engine')
    if sha(output/'declared-protocol.json') != seal['protocol_sha256']:
        raise ValueError('Declaration changed')
    for path, digest in (protocol['inputs'] | protocol['sources']).items():
        if sha(path) != digest: raise ValueError('Frozen input/source changed: '+path)
    target, log = output/'audit', output/'run.log'
    if target.exists() or log.exists() or (output/'receipt.json').exists():
        raise FileExistsError('Prior attempt retained')
    task = protocol['task']
    command = [shutil.which('java'), '-Xmx6G', '-XX:ActiveProcessorCount=1', '-cp',
        (output/'engine/classpath.txt').read_text().strip(), 'reframe4s.halfflow.RealImageObjectiveAudit',
        task['data']+'/moving.nii.gz', task['data']+'/fixed.nii.gz', task['data']+'/fixed-mask.nii.gz',
        task['checkpoint'], str(target), str(task['expected_loss']), str(task['damping']),
        ','.join(map(str, protocol['epsilons_mm']))]
    row = dict(command=command, source_sha256=source['sha256'],
        protocol_sha256=sha(output/'declared-protocol.json'), analysis_completed=False,
        original_replication_pass=False, qualification_claim=False, runtime_comparison_allowed=False)
    env = os.environ.copy(); env.update(protocol['environment']); started = time.monotonic()
    with log.open('x') as stream:
        process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=stream,
            stderr=subprocess.STDOUT, start_new_session=True)
        try: row['exit_code'] = process.wait(timeout=protocol['timeout_seconds'])
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGTERM)
            try: process.wait(timeout=5)
            except subprocess.TimeoutExpired: os.killpg(process.pid, signal.SIGKILL); process.wait()
            row.update(exit_code=124, failure='Declared diagnostic deadline exceeded')
    row.update(diagnostic_wall_seconds=time.monotonic()-started, log_sha256=sha(log))
    if row['exit_code'] == 0:
        try:
            initial, result = read(target/'initial.json'), read(target/'result.json')
            if not math.isfinite(initial['audit']['loss']) or abs(initial['audit']['loss']-task['expected_loss']) > protocol['expected_loss_tolerance']:
                raise ValueError('Captured objective not reproduced')
            full_summary = summarize(result, initial, protocol)
            baseline = read(Path(protocol['original_result']))
            count = len(protocol['original_epsilons_mm'])
            for old, new in zip(baseline['directions'], result['directions'], strict=True):
                if old['rows'] != new['rows'][:count]:
                    raise ValueError('Original derivative measurements not exactly reproduced')
            subset = dict(result, directions=[dict(d, rows=d['rows'][count:]) for d in result['directions']])
            new_summary = summarize(subset, initial, dict(protocol, epsilons_mm=protocol['new_epsilons_mm']))
            row.update(analysis_completed=True, original_measurements_exact=True,
                original_measurement_count=count*len(protocol['directions']),
                additional_measurement_count=len(NEW_STEPS)*len(protocol['directions']),
                all_step_summary=full_summary, new_step_summary=new_summary,
                initial_sha256=sha(target/'initial.json'), result_sha256=sha(target/'result.json'))
        except Exception as error: row['failure'] = type(error).__name__+': '+str(error)
    write(output/'receipt.json', row)
    print('DONE exit', row['exit_code'], 'analysis', row['analysis_completed'],
        'small-step spatial', row.get('new_step_summary', {}).get('spatial_criterion_pass'), flush=True)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('command', choices=['freeze', 'run']); p.add_argument('--output', type=Path, required=True)
    p.add_argument('--prior', type=Path); p.add_argument('--classpath', type=Path)
    args = p.parse_args()
    if args.command == 'freeze':
        if args.prior is None or args.classpath is None: p.error('freeze requires --prior and --classpath')
        freeze(args.output.resolve(), args.prior.resolve(), args.classpath.resolve())
    else: run(args.output.resolve())
