#!/usr/bin/env python3
"""Freeze a fine-parity control and coarse/cross-cohort derivative diagnostics."""
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
from trajectory_study import verify
from objective_probe import ROOT, freeze_engine, verify_engine
from archive_objective_probe import source_delta

HERE = Path(__file__).resolve().parent
SELECTION = [('oasis-13-to-14', 1, 'LevelEnd'),
             ('oasis-11-to-12', 0, 'LevelStart'), ('oasis-11-to-12', 0, 'LevelEnd'),
             ('mmrr-21-11-to-12', 0, 'LevelEnd'), ('mmrr-21-11-to-12', 1, 'LevelStart')]
ALLOWED_CHANGES = {
    'modules/reframe4s-halfflow/shared/src/main/scala/reframe4s/halfflow/HalfFlowCcEngine.scala',
    'modules/reframe4s-halfflow/shared/src/test/scala/reframe4s/halfflow/HalfFlowCcObjectiveProbeSuite.scala',
    'benchmarks/flashalign/runner/src/main/scala/reframe4s/halfflow/RealImageObjectiveAudit.scala'}


def freeze(output, prior, captures, classpath):
    if output.exists(): raise FileExistsError(output)
    reference = read(prior/'receipt.json')
    if reference['exit_code'] != 0 or not reference['analysis_completed'] or not reference['original_measurements_exact']:
        raise ValueError('Completed smaller-step baseline required')
    previous, old_source = verify_engine(prior/'engine')
    panel, _ = verify(captures)
    original_inputs = read(captures/'engine/inputs.json')
    baseline = prior/'audit/result.json'
    if sha(baseline) != reference['result_sha256']: raise ValueError('Fine baseline changed')
    inputs = {str(path): sha(path) for path in [prior/'receipt.json', baseline,
        prior/'audit/initial.json', captures/'engine/protocol.json', captures/'diagnostic-seal.json']}
    tasks, cases = [], {}
    for pair, level, kind in SELECTION:
        case = next(c for c in panel['cases'] if c['id'] == pair)
        cases[pair] = dict(id=pair, data=case['data'])
        parent = captures/pair; fit_path = parent/'selected-control-fit-receipt.json'
        fit = read(fit_path); events_path = parent/'selected-control/checkpoints/events.jsonl'
        if sha(events_path) != fit['events_sha256']: raise ValueError('Committed source events changed')
        events = [json.loads(line) for line in events_path.read_text().splitlines()]
        selected = [e for e in events if e['info']['levelIndex'] == level and e['info']['kind'] == kind]
        if len(selected) != 1 or selected[0]['directory'] is None: raise ValueError('Declared state missing')
        event = selected[0]; checkpoint = events_path.parent/event['directory']
        if read(checkpoint/'checkpoint.json') != event: raise ValueError('Uncommitted checkpoint')
        paths = [fit_path, events_path, checkpoint/'checkpoint.json']
        paths += [checkpoint/(f'midpoint-{arm}-'+name) for arm in ['fixed', 'moving']
                  for name in ['residual.json', 'residual.f64', 'residual.valid-u8', 'affine.json']]
        for name in ['moving.nii.gz', 'fixed.nii.gz', 'fixed-mask.nii.gz']:
            path = Path(case['data'])/name
            if sha(path) != original_inputs[str(path)]: raise ValueError('Prepared source image changed')
            paths.append(path)
        inputs.update({str(path): sha(path) for path in paths})
        tasks.append(dict(id=f'{pair}-level{level}-{kind.lower()}', pair=pair, policy='selected-control',
            level_index=level, shrink=event['info']['shrink'], kind=kind, event=event['serial'],
            checkpoint=str(checkpoint), data=case['data'], fit_exit_code=fit['exit_code'],
            expected_loss=event['info']['after']['loss'], damping=event['info']['control']['damping']))
    sources = [Path(__file__), HERE/'spatial_chain_followup.py', HERE/'trajectory_study.py',
        HERE/'objective_probe.py', HERE/'archive_objective_probe.py',
        ROOT/'benchmarks/flashalign/run_real_mri_recovery.py', ROOT/'benchmarks/flashalign/real_image_court.py']
    protocol = dict(schema='hfq-pyramid-chain-diagnostic-v1', created_at=datetime.now(timezone.utc).isoformat(),
        tasks=tasks, cases=list(cases.values()), inputs=inputs, sources={str(p): sha(p) for p in sources},
        fine_parity_result=str(baseline), fine_parity_source_sha256=old_source['sha256'],
        captures=str(captures), directions=previous['directions'], epsilons_mm=previous['epsilons_mm'],
        original_epsilons_mm=previous['original_epsilons_mm'], new_epsilons_mm=previous['new_epsilons_mm'],
        maximum_minimum_relative_derivative_error=previous['maximum_minimum_relative_derivative_error'],
        expected_loss_tolerance=previous['expected_loss_tolerance'], convergence_rule=previous['convergence_rule'],
        environment=panel['environment'], workers=1, timeout_seconds=3600,
        selection='Fixed fine OASIS13 endpoint neutrality control; OASIS11 coarse start/end; MMRR11 coarse end and fine start. All selected-control, all observed development inputs, no outcome-based replacement.',
        preparation='Native source images/mask -> exact production pyramid builder with shared maximum-capacity scratch, fixed then moving. Saved work grid rebuilt by the production pyramidGrid owner. No fitting or labels.',
        analysis_rule='First reproduce all 42 existing fine measurements exactly; a failed neutrality check prevents downstream attempts and is retained explicitly. Every attempted state must reproduce its captured objective within 1e-12. Report original-eight-step and additional-six-step verdicts separately for all three directions, with unchanged 0.005 and three-point-decrease criterion and complete spatial support.',
        original_fine_replication_pass=False, candidate_policies_added=0,
        remaining_scope='Observed diagnostic evidence only; image order, broader cohorts, mixed acquired grids, boundaries and production/anatomical validation remain.',
        qualification_claim=False, runtime_comparison_allowed=False)
    output.mkdir(parents=True); write(output/'declared-protocol.json', protocol)
    freeze_engine(output/'engine', output/'declared-protocol.json', classpath)
    _, engine = verify_engine(output/'engine')
    changed = {name for name in set(engine['files']) | set(old_source['files'])
               if engine['files'].get(name) != old_source['files'].get(name)}
    if changed != ALLOWED_CHANGES: raise ValueError('Unexpected source delta: '+str(changed))
    source_delta(prior/'engine', output/'engine', output)
    delta = read(output/'source-delta.json')
    delta['base_reproduction'] = ('P01 control-source.tar.gz, D01 instrumentation.patch, '
        'halfflow-spatial-chain-20261014/source.patch, then smaller-step-outcomes-v1/source.patch')
    (output/'source-delta.json').write_text(json.dumps(delta, indent=2)+'\n')
    for path in sources: shutil.copyfile(path, output/('source-'+path.name))
    write(output/'driver-seal.json', dict(engine_freeze_sha256=sha(output/'engine/freeze.json'),
        protocol_sha256=sha(output/'declared-protocol.json'), source_sha256=engine['sha256'],
        changed_source_files=sorted(changed)))
    print('FROZEN five states: one fine neutrality control, three coarse and one MMRR fine; 42 measurements per attempted state', flush=True)


def run(output):
    seal = read(output/'driver-seal.json'); protocol, source = verify_engine(output/'engine')
    if sha(output/'engine/freeze.json') != seal['engine_freeze_sha256'] or sha(output/'declared-protocol.json') != seal['protocol_sha256']:
        raise ValueError('Frozen study seal changed')
    for path, digest in (protocol['inputs'] | protocol['sources']).items():
        if sha(path) != digest: raise ValueError('Frozen source/input changed: '+path)
    env = os.environ.copy(); env.update(protocol['environment']); rows = []; parity = False
    for index, task in enumerate(protocol['tasks']):
        receipt = output/(task['id']+'-receipt.json'); target = output/task['id']; log = output/(task['id']+'.log')
        if receipt.exists() or target.exists() or log.exists(): raise FileExistsError('Prior attempt retained')
        row = dict(task=task, source_sha256=source['sha256'], protocol_sha256=sha(output/'declared-protocol.json'),
            analysis_completed=False, attempted=index == 0 or parity,
            original_fine_replication_pass=False, qualification_claim=False, runtime_comparison_allowed=False)
        if not row['attempted']:
            row.update(exit_code=None, failure='Not attempted because fine numerical neutrality was not established')
            write(receipt,row); rows.append(row); continue
        command = [shutil.which('java'), '-Xmx6G', '-XX:ActiveProcessorCount=1', '-cp',
            (output/'engine/classpath.txt').read_text().strip(), 'reframe4s.halfflow.RealImageObjectiveAudit',
            task['data']+'/moving.nii.gz', task['data']+'/fixed.nii.gz', task['data']+'/fixed-mask.nii.gz',
            task['checkpoint'], str(target), str(task['expected_loss']), str(task['damping']),
            ','.join(map(str, protocol['epsilons_mm'])), str(task['level_index'])]
        row['command'] = command; started = time.monotonic(); print('START', task['id'], flush=True)
        with log.open('x') as stream:
            process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
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
                if initial['level_index'] != task['level_index'] or initial['shrink'] != task['shrink']:
                    raise ValueError('Wrong prepared pyramid level')
                row['all_step_summary'] = summarize(result, initial, protocol)
                count = len(protocol['original_epsilons_mm'])
                for name, section, steps in [('original', slice(None,count), protocol['original_epsilons_mm']),
                                              ('small', slice(count,None), protocol['new_epsilons_mm'])]:
                    subset = dict(result, directions=[dict(d, rows=d['rows'][section]) for d in result['directions']])
                    row[name+'_step_summary'] = summarize(subset, initial, dict(protocol, epsilons_mm=steps))
                if index == 0:
                    if result != read(Path(protocol['fine_parity_result'])): raise ValueError('Fine baseline measurements differ')
                    parity = True; row['fine_measurements_exact'] = True
                row.update(analysis_completed=True, initial_sha256=sha(target/'initial.json'), result_sha256=sha(target/'result.json'))
            except Exception as error: row['failure'] = type(error).__name__+': '+str(error)
        write(receipt, row); rows.append(row)
        print('DONE', task['id'], 'exit', row['exit_code'], 'analysis', row['analysis_completed'], flush=True)
    write(output/'results.json', dict(rows=rows, fine_neutrality_pass=parity,
        all_states_completed=all(r['attempted'] and r['exit_code']==0 and r['analysis_completed'] for r in rows),
        original_fine_replication_pass=False, qualification_claim=False, runtime_comparison_allowed=False))


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('command', choices=['freeze','run']); p.add_argument('--output', type=Path, required=True)
    p.add_argument('--prior', type=Path); p.add_argument('--captures', type=Path); p.add_argument('--classpath', type=Path)
    args = p.parse_args()
    if args.command == 'freeze':
        if any(value is None for value in [args.prior, args.captures, args.classpath]): p.error('freeze requires --prior, --captures and --classpath')
        freeze(args.output.resolve(), args.prior.resolve(), args.captures.resolve(), args.classpath.resolve())
    else: run(args.output.resolve())
