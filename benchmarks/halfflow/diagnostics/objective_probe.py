#!/usr/bin/env python3
"""Freeze and run a production-gradient audit at the completed pilot checkpoint.

This prepares D02's acquired-image derivative evidence from D01's verified
capture. It is not a new optimizer policy or a qualification fit.
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

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT/'benchmarks/flashalign'))
from run_real_mri_recovery import freeze as freeze_engine, verify as verify_engine
from real_image_court import sha


def read(path):
    return json.loads(path.read_text())


def write(path, value):
    with path.open('x') as stream:
        json.dump(value, stream, indent=2)
        stream.write('\n')


def freeze(study, trajectories, classpath, derivative_baseline=None):
    if study.exists():
        raise FileExistsError(study)
    base = trajectories/'oasis-11-to-12/selected-control'
    fit = base.parent/'selected-control-fit-receipt.json'
    check = base.parent/'selected-control-checkpoint-verification.json'
    if not read(check)['pass'] or read(fit)['exit_code'] != 0:
        raise ValueError('A completed independently verified pilot is required')
    events_path = base/'checkpoints/events.jsonl'
    if sha(events_path) != read(fit)['events_sha256']:
        raise ValueError('Pilot event identity changed')
    events = [json.loads(line) for line in events_path.read_text().splitlines()]
    event = next(row for row in events if row['serial'] == 23)
    if event['info']['kind'] != 'LevelStart' or event['info']['levelIndex'] != 1:
        raise ValueError('Expected the predeclared fine-level start')
    checkpoint = base/'checkpoints'/event['directory']
    inputs = {}
    for arm in ['fixed', 'moving']:
        for suffix in ['.json', '.f64', '.valid-u8']:
            path = checkpoint/('midpoint-'+arm+'-residual'+suffix)
            digest = sha(path)
            if read(check)['raw_artifact_sha256'][str(path.relative_to(base))] != digest:
                raise ValueError('Verified checkpoint changed: '+str(path))
            inputs[str(path)] = digest
        path = checkpoint/('midpoint-'+arm+'-affine.json')
        inputs[str(path)] = sha(path)
    inputs[str(checkpoint/'checkpoint.json')] = sha(checkpoint/'checkpoint.json')
    case = next(c for c in read(trajectories/'engine/protocol.json')['cases'] if c['id'] == 'oasis-11-to-12')
    protocol = {'schema': 'hfq-objective-chain-pilot-v1', 'created_at': datetime.now(timezone.utc).isoformat(),
        'cases': [case], 'checkpoint': str(checkpoint), 'checkpoint_inputs': inputs,
        'pilot_fit_sha256': sha(fit), 'pilot_verification_sha256': sha(check),
        'expected_loss': event['info']['after']['loss'], 'damping': event['info']['control']['damping'],
        'directions': ['translation', 'smooth-wave', 'negative-gradient'],
        'epsilons_mm': [0.5, 0.2, 0.1, 0.05, 0.02, 0.01, 0.005, 0.002],
        'expected_loss_tolerance': 1e-12, 'maximum_minimum_relative_derivative_error': 0.005,
        'convergence_rule': 'At least three consecutive epsilon entries with strictly decreasing relative error',
        'intensity_chain_criterion': 'Apply the same 0.005 minimum symmetric relative error and three-point decreasing-error rule to CC intensity-gradient dot actual sampled-image differences versus the frozen-objective finite difference',
        'interpretation': 'If the intensity chain passes while the production velocity gradient fails, the discrepancy lies between sampled-image changes and the production spatial/composition gradient. This does not establish an anatomical fix.',
        'criterion_source': 'Existing HalfFlowCcEngineSuite synthetic production directional-derivative criterion',
        'scope': 'Actual frozen production objective and gradient; actual paired integration/composition/interpolation before accumulated smoothing',
        'remaining_scope': 'This pilot does not close D01/D02; other checkpoints, scales, image order, mixed grids and regularization remain',
        'java_heap': '6G', 'java_active_processors': 1, 'timeout_seconds': 3600,
        'runtime_comparison_allowed': False, 'qualification_claim': False}
    if derivative_baseline is not None:
        if read(derivative_baseline/'receipt.json')['exit_code'] != 0:
            raise ValueError('Derivative baseline must have completed')
        protocol['derivative_baseline'] = str(derivative_baseline)
        protocol['derivative_baseline_inputs'] = {str(p):sha(p) for p in
            [derivative_baseline/'receipt.json', derivative_baseline/'audit/result.json']}
    study.mkdir(parents=True)
    write(study/'declared-protocol.json', protocol)
    freeze_engine(study/'engine', study/'declared-protocol.json', classpath)
    verify_engine(study/'engine')
    shutil.copyfile(__file__, study/'source-objective_probe.py')
    write(study/'driver-seal.json', {'source_sha256': sha(Path(__file__)),
        'engine_freeze_sha256': sha(study/'engine/freeze.json'),
        'driver_scope': 'No source, data or policy changes after this seal'})
    print('FROZEN production-gradient pilot: three directions, eight physical epsilon values', flush=True)


def run(study):
    seal = read(study/'driver-seal.json')
    if sha(Path(__file__)) != seal['source_sha256'] or sha(study/'engine/freeze.json') != seal['engine_freeze_sha256']:
        raise ValueError('Probe driver or frozen build changed')
    protocol, source = verify_engine(study/'engine')
    for name, digest in protocol['checkpoint_inputs'].items():
        if sha(name) != digest:
            raise ValueError('Checkpoint input changed: '+name)
    for name, digest in protocol.get('derivative_baseline_inputs', {}).items():
        if sha(name) != digest:
            raise ValueError('Original derivative evidence changed: '+name)
    case = protocol['cases'][0]
    output = study/'audit'
    if output.exists() or (study/'receipt.json').exists() or (study/'run.log').exists():
        raise FileExistsError('Prior attempt retained; use a newly declared run')
    command = [shutil.which('java'), '-Xmx6G', '-XX:ActiveProcessorCount=1', '-cp',
        (study/'engine/classpath.txt').read_text().strip(), 'reframe4s.halfflow.RealImageObjectiveAudit',
        case['data']+'/moving.nii.gz', case['data']+'/fixed.nii.gz', case['data']+'/fixed-mask.nii.gz',
        protocol['checkpoint'], str(output), str(protocol['expected_loss']), str(protocol['damping'])]
    started = time.monotonic()
    with (study/'run.log').open('x') as stream:
        process = subprocess.Popen(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = process.wait(timeout=protocol['timeout_seconds'])
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            code = 124
    row = {'schema': 'hfq-objective-probe-receipt-v1', 'exit_code': code,
        'diagnostic_wall_seconds': time.monotonic()-started, 'source_sha256': source['sha256'],
        'protocol_sha256': sha(study/'engine/protocol.json'), 'run_log_sha256': sha(study/'run.log'),
        'qualification_claim': False, 'runtime_comparison_allowed': False}
    if code == 0:
        result = read(output/'result.json')
        if [r['direction'] for r in result['directions']] != protocol['directions']:
            raise ValueError('Unexpected probe direction ledger')
        verdicts = []
        chain_verdicts = []
        for direction in result['directions']:
            if [r['epsilon_mm'] for r in direction['rows']] != protocol['epsilons_mm']:
                raise ValueError('Unexpected epsilon ledger')
            errors = [r['relative_error'] for r in direction['rows']]
            converges = any(c < b < a for a,b,c in zip(errors, errors[1:], errors[2:]))
            verdicts.append({'direction': direction['direction'], 'minimum_relative_error': min(errors),
                'two_step_convergence_region': converges,
                'pass': converges and min(errors) <= protocol['maximum_minimum_relative_derivative_error']})
            chain_errors = [r['intensity_chain_relative_error'] for r in direction['rows']]
            chain_converges = any(c < b < a for a,b,c in zip(chain_errors, chain_errors[1:], chain_errors[2:]))
            chain_verdicts.append({'direction':direction['direction'], 'minimum_relative_error':min(chain_errors),
                'two_step_convergence_region':chain_converges,
                'pass':chain_converges and min(chain_errors)<=protocol['maximum_minimum_relative_derivative_error']})
        row.update(directions=verdicts, gradient_criterion_pass=all(r['pass'] for r in verdicts),
            intensity_chain_directions=chain_verdicts,
            intensity_chain_criterion_pass=all(r['pass'] for r in chain_verdicts),
            result_sha256=sha(output/'result.json'), initial_sha256=sha(output/'initial.json'))
        if 'derivative_baseline' in protocol:
            old=read(Path(protocol['derivative_baseline'])/'audit/result.json')['directions']
            keys=['direction','epsilon_mm','analytic','finite_difference','relative_error','plus_loss','minus_loss']
            before=[{k:r[k] for k in keys} for d in old for r in d['rows']]
            after=[{k:r[k] for k in keys} for d in result['directions'] for r in d['rows']]
            row['baseline_numerics_reproduced_exactly']=before==after
    write(study/'receipt.json', row)
    print(json.dumps(row, indent=2), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['freeze', 'run'])
    parser.add_argument('--study', type=Path, required=True)
    parser.add_argument('--trajectories', type=Path)
    parser.add_argument('--classpath', type=Path)
    parser.add_argument('--derivative-baseline', type=Path)
    args = parser.parse_args()
    if args.command == 'freeze':
        if args.trajectories is None or args.classpath is None:
            parser.error('--trajectories and --classpath required')
        freeze(args.study.resolve(), args.trajectories.resolve(), args.classpath.resolve(),
               args.derivative_baseline.resolve() if args.derivative_baseline else None)
    else:
        run(args.study.resolve())
