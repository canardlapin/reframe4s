#!/usr/bin/env python3
"""Seal and execute the declared six-pair development screen after its gates close."""
import argparse
import contextlib
from datetime import datetime, timezone
import gc
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import statistics
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'benchmarks/halfflow/diagnostics'))
sys.path.insert(0,str(ROOT/'benchmarks/halfflow/reference'))
from trajectory_study import read, write, sha, verify_engine
from audit_study import audit_maps
from fixed_audit import audit as fixed_audit

GATES = {'D01':'bd-01M4X4DY7DQCVK33MCK3T17NA1',
         'D02':'bd-01M4X4DYCNRG58VQVKJKH77WWR',
         'P02':'bd-01M4X4DXW7WGW5WK0J3Z6PFP98'}


def require_closed_gates(gates):
    missing = [name for name, row in gates.items() if row['status'] != 'closed']
    if set(gates) != set(GATES) or missing:
        raise ValueError('Execution gates remain open: '+', '.join(missing))


def gate_status():
    mote = shutil.which('mote') or str(Path.home()/'.cargo/bin/mote')
    rows = {}
    for name, issue in GATES.items():
        row = json.loads(subprocess.check_output([mote,'--actor','codex-hfq-reference-20261014',
            '--json','show',issue],cwd=ROOT,text=True))
        if row['id'] != issue or row.get('deleted_at'):
            raise ValueError('Missing or substituted execution gate')
        rows[name] = dict(issue=issue,status=row['status'],clock=row['clock'])
    return rows


def summarize(protocol, receipts):
    expected = {(r['pair'],r['lane']) for r in protocol['attempts']}
    rows = {}
    for row in receipts:
        key = row['pair'],row['lane']
        if key not in expected or key in rows:
            raise ValueError('Unexpected or duplicate attempt receipt')
        rows[key] = row
    pairs = []
    for case in protocol['design']['cases']:
        baseline = rows.get((case['id'],'step-scaled-baseline'))
        candidate = rows.get((case['id'],'spatial-chain-direction'))
        valid = all(row is not None and row.get('exit_code') == 0 and row.get('native_export_accepted')
            and row.get('audit_completed') and row.get('geometry_pass') and row.get('cells_pass')
            and row.get('anatomy_complete') and all(isinstance(row.get(m),(int,float)) and
                math.isfinite(row[m]) for m in ['mean_dice','mean_hd95_mm']) for row in [baseline,candidate])
        parity = bool(baseline is not None and baseline.get('baseline_replay_pass') is True)
        pairs.append(dict(pair=case['id'],comparable=bool(valid and parity),
            dice_change=candidate['mean_dice']-baseline['mean_dice'] if valid and parity else None,
            hd95_change_mm=candidate['mean_hd95_mm']-baseline['mean_hd95_mm'] if valid and parity else None))
    complete = len(rows) == len(expected) and all(p['comparable'] for p in pairs)
    dice = statistics.mean(p['dice_change'] for p in pairs) if complete else None
    hd = statistics.mean(p['hd95_change_mm'] for p in pairs) if complete else None
    rule = protocol['design']['expansion_rule']
    passed = bool(complete and dice > rule['mean_dice_change_gt'] and hd < rule['mean_hd95_change_mm_lt']
        and all(p['dice_change'] >= -rule['maximum_pair_dice_loss'] and
            p['hd95_change_mm'] <= rule['maximum_pair_hd95_increase_mm'] for p in pairs))
    return dict(planned_full_fits=len(expected),terminal_full_fits=len(rows),
        missing_attempts=[list(k) for k in sorted(expected-set(rows))],pairs=pairs,
        all_pairs_comparable=complete,mean_dice_change=dice,mean_hd95_change_mm=hd,
        expansion_allowed=passed,qualification_claim=False,runtime_comparison_allowed=False,
        qualifying_convergence_established=False,
        interpretation='Missing, failed, rejected, nonfinite or mismatched-baseline attempts prevent expansion. '
            'No complete-case averages, replacements or label-selected checkpoints. Budget/controller stops remain nonconverged.')


def freeze(output, engine):
    if output.exists():raise FileExistsError('Fresh execution declaration required')
    design, source = verify_engine(engine)
    if (design['status'] != 'execution-gated-design' or design['full_fits_executed'] != 0 or
            len(design['cases']) != 6 or len(design['lanes']) != 2):
        raise ValueError('Expected the original unexecuted six-pair design')
    source_expected = '4c3bed8ae13b67b453f570ff8a9923ab924658637d04161e7e9d9f044bb0a9b1'
    if source['sha256'] != source_expected:raise ValueError('Unexpected candidate source')
    build = read(engine.parent/'build-verification.json')
    if build['source_sha256'] != source_expected or build['checks'] != dict(JVM=30,JS=30,runner=4,process_monitor=4):
        raise ValueError('Missing affected-source checks')
    java = Path(shutil.which('java')).resolve()
    cp = (engine/'classpath.txt').read_text().strip()
    attempts = []
    for case in design['cases']:
        for lane in design['lanes']:
            target = output/case['id']/lane['id']
            command = [str(java),'-Xmx6G','-XX:ActiveProcessorCount=1','-cp',cp,
                'reframe4s.halfflow.RealImageRegistration',case['data']+'/moving.nii.gz',
                case['data']+'/fixed.nii.gz',case['data']+'/fixed-mask.nii.gz',str(target),
                lane['mode'],case['saved_affine'],*lane['flags']]
            if any(case[k] in command for k in ['moving_labels','fixed_labels']):
                raise ValueError('Evaluation labels leaked into a fit command')
            attempts.append(dict(ordinal=len(attempts)+1,pair=case['id'],lane=lane['id'],
                command=command,output=str(target),budget_charge_full_fits=1))
    paths = [Path(__file__).resolve(),Path(__file__).with_name('test_spatial_direction_study.py').resolve(),
        ROOT/'benchmarks/halfflow/diagnostics/trajectory_study.py',
        ROOT/'benchmarks/halfflow/diagnostics/fixed_audit.py',
        ROOT/'benchmarks/halfflow/diagnostics/audit-contract-v1.json',
        ROOT/'benchmarks/halfflow/reference/audit_study.py',
        ROOT/'benchmarks/halfflow/reference/canonical_ants.py',
        ROOT/'benchmarks/halfflow/reference/measure_process.py']
    paths += [ROOT/'benchmarks/flashalign'/n for n in ['run_real_mri_recovery.py','run_real_mri_assessment.py',
        'summarize_real_mri_assessment.py','real_image_court.py','real_mri_cell_topology.py']]
    output.mkdir(parents=True)
    for path in paths:shutil.copyfile(path,output/('source-'+path.name))
    write(output/'protocol.json',dict(schema='hfq-spatial-direction-execution-v1',
        created_at=datetime.now(timezone.utc).isoformat(),design=design,engine=str(engine),
        engine_freeze_sha256=sha(engine/'freeze.json'),source_sha256=source['sha256'],
        design_sha256=sha(engine/'protocol.json'),sources={str(p):sha(p) for p in paths},
        inputs=read(engine/'inputs.json'),java_sha256=sha(java),attempts=attempts,
        prerequisites=GATES,gate_rule='D01, D02 and P02 must be explicitly closed in Mote after their evidence is reviewed. '
            'This execution declaration does not close or waive any gate.',
        baseline_parity='All four prior map/validity identities must match where supplied; OASIS15 has no historical step-scaled baseline.',
        measurement='Single sequential worker, one native thread, one Java active processor, 6G heap; all times excluded from runtime comparisons.',
        anatomy='Both directions and original per-structure scores; final native output only; fixed-region intensity and independent deformation checks.',
        missing='All twelve declared rows remain in the denominator. Launch/terminal receipts are exclusive; no silent reruns.',
        qualification_claim=False,runtime_comparison_allowed=False))
    write(output/'gates-at-freeze.json',gate_status())
    write(output/'unstarted-summary.json',summarize(read(output/'protocol.json'),[]))
    print('FROZEN 12 attempt commands, evaluator and input identities; execution gates remain required',flush=True)


def verify(output):
    p = read(output/'protocol.json')
    if p['schema'] != 'hfq-spatial-direction-execution-v1' or p['prerequisites'] != GATES:
        raise ValueError('Wrong execution protocol')
    for group in ['sources','inputs']:
        for path,digest in p[group].items():
            if sha(path) != digest:raise ValueError(group+' identity changed: '+path)
    engine = Path(p['engine'])
    if sha(engine/'freeze.json') != p['engine_freeze_sha256']:
        raise ValueError('Frozen executable seal changed')
    design,source = verify_engine(engine)
    if design != p['design'] or source['sha256'] != p['source_sha256']:
        raise ValueError('Frozen scientific design changed')
    if sha(p['attempts'][0]['command'][0]) != p['java_sha256']:
        raise ValueError('Java executable changed')
    return p


def run(output):
    gates = gate_status();require_closed_gates(gates)
    p = verify(output)
    if (output/'launch.json').exists():raise FileExistsError('Prior launch retained; no automatic rerun')
    write(output/'launch.json',dict(started_at=datetime.now(timezone.utc).isoformat(),
        protocol_sha256=sha(output/'protocol.json'),gates=gates))
    env = os.environ.copy();env.update(p['design']['environment']);env['MPLCONFIGDIR']='/private/tmp/hfq-mpl'
    wrapper = ROOT/'benchmarks/halfflow/reference/measure_process.py'
    rows = []
    for attempt in p['attempts']:
        target = Path(attempt['output']);parent = target.parent;parent.mkdir(exist_ok=True)
        stem = attempt['lane'];log = parent/(stem+'-fit.log');audit_log = parent/(stem+'-audit.log')
        measurement = parent/(stem+'-measurement.json')
        write(parent/(stem+'-launch.json'),dict(attempt=attempt,protocol_sha256=sha(output/'protocol.json')))
        row = dict(pair=attempt['pair'],lane=stem,ordinal=attempt['ordinal'],
            protocol_sha256=sha(output/'protocol.json'),source_sha256=p['source_sha256'],
            qualification_claim=False,runtime_comparison_allowed=False)
        print('START',attempt['ordinal'],attempt['pair'],stem,flush=True)
        process = None;interrupted = None
        try:
            with log.open('x') as stream:
                process = subprocess.Popen([sys.executable,str(wrapper),'--receipt',str(measurement),'--',*attempt['command']],
                    cwd=ROOT,env=env,stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
                try:row['exit_code'] = process.wait(timeout=p['design']['timeout_seconds'])
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid,signal.SIGTERM)
                    try:process.wait(timeout=5)
                    except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait()
                    row.update(exit_code=124,failure='Declared one-hour fit deadline exceeded')
        except BaseException as error:
            if not isinstance(error,Exception):interrupted = error
            row.update(exit_code=130 if interrupted else 1,failure=type(error).__name__+': '+str(error))
        finally:
            if process is not None and process.poll() is None:
                os.killpg(process.pid,signal.SIGTERM)
                try:process.wait(timeout=5)
                except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait()
        if measurement.exists():row['measurement'] = read(measurement)
        if log.exists():row['fit_log_sha256'] = sha(log)
        case = next(c for c in p['design']['cases'] if c['id'] == attempt['pair'])
        expected = case['expected_baseline_outputs'] if stem == 'step-scaled-baseline' else {}
        parity = {name:(sha(target/name) == digest if (target/name).exists() else False) for name,digest in expected.items()}
        row.update(baseline_replay=parity,baseline_replay_pass=all(parity.values()),historical_baseline_available=bool(expected))
        diagnostics = target/'halfflow-diagnostics.txt'
        levels = re.findall(r'HalfFlowCcLevelDiagnostics\(([^,]+),([^,]+),([^,]+),(\d+),(\d+),([^,]+),',diagnostics.read_text()) if diagnostics.exists() else []
        row['stopping'] = dict(levels=[dict(shrink=int(x[0]),accepted_steps=int(x[3]),attempts=int(x[4]),status=x[5]) for x in levels],
            qualifying_convergence_established=False)
        try:
            if row['exit_code'] != 0:raise RuntimeError('Fit did not complete')
            lane = 'halfflow' if (target/'halfflow-fixed-to-moving.json').exists() else 'halfflow-rejected'
            admission = target/'export-diagnostics.txt'
            row['native_export_accepted'] = lane == 'halfflow' and admission.exists() and admission.read_text().splitlines()[0] == 'accepted'
            with audit_log.open('x') as stream,contextlib.redirect_stdout(stream),contextlib.redirect_stderr(stream):
                row['fixed_intensity'] = fixed_audit(Path(case['data']),target,lane)
                row.update(audit_maps(case,target,lane))
            row['audit_completed'] = True
            row['map_sha256'] = {path.name:sha(path) for path in target.iterdir() if path.suffix in ['.f64','.valid-u8']}
        except Exception as error:
            row.update(audit_completed=False,audit_failure=type(error).__name__+': '+str(error))
        if audit_log.exists():row['audit_log_sha256'] = sha(audit_log)
        write(parent/(stem+'-receipt.json'),row);rows.append(row)
        write(output/f"summary-after-{attempt['ordinal']:02d}.json",summarize(p,rows))
        print('DONE',attempt['ordinal'],'exit',row['exit_code'],'audit',row['audit_completed'],flush=True)
        if interrupted is not None:raise interrupted
        gc.collect()
    write(output/'results.json',dict(rows=rows,summary=summarize(p,rows)))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['freeze','verify','run']);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--engine',type=Path)
    args = parser.parse_args()
    if args.command == 'freeze':
        if args.engine is None:parser.error('freeze requires --engine')
        freeze(args.output.resolve(),args.engine.resolve())
    elif args.command == 'verify':
        verify(args.output.resolve());print(json.dumps(gate_status(),indent=2))
    else:run(args.output.resolve())
