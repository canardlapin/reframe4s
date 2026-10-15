#!/usr/bin/env python3
"""Freeze and compare native cell admission against all 48 independent fields."""
import argparse
from datetime import datetime, timezone
import math
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'benchmarks/flashalign'))
from run_real_mri_recovery import freeze as freeze_engine, verify as verify_engine
from analyze_c04 import read, write, sha


def freeze(output, reference, classpath):
    if output.exists():
        raise FileExistsError(output)
    rows = read(reference / 'results.json')['rows']
    if len(rows) != 48:
        raise ValueError('The complete 48-field independent audit is required')
    inputs = {str(reference / name): sha(reference / name) for name in ['protocol.json', 'results.json']}
    for row in rows:
        for suffix in ['.json', '.f64', '.valid-u8']:
            path = Path(row['task']['stem'] + suffix)
            inputs[str(path)] = sha(path)
    output.mkdir(parents=True)
    protocol = dict(schema='hfq-native-cell-parity-v1', created_at=datetime.now(timezone.utc).isoformat(),
        cases=[], tasks=rows, inputs=inputs, absolute_tolerance=1e-10, relative_tolerance=1e-10,
        integer_and_boolean_comparison='Exact', timeout_seconds_per_field=60,
        minimum_cell_jacobian=0.05, minimum_cell_coverage=0.99,
        scope='All 48 previously captured fields, all complete cells, eight corners and center. '
            'Every independently failing field must also fail the shared forward geometry gate. '
            'This read-only diagnostic establishes evaluator parity, not registration qualification.',
        qualification_claim=False, runtime_comparison_allowed=False)
    write(output / 'declaration.json', protocol)
    freeze_engine(output / 'engine', output / 'declaration.json', classpath)
    verify_engine(output / 'engine')
    shutil.copyfile(__file__, output / 'source-native_cells.py')
    write(output / 'driver-seal.json', dict(driver_sha256=sha(Path(__file__)),
        engine_freeze_sha256=sha(output / 'engine/freeze.json')))
    print('FROZEN native parity: all 48 fields, exact counts, 1e-10 absolute/relative tolerance', flush=True)


def run(output):
    seal = read(output / 'driver-seal.json')
    if sha(Path(__file__)) != seal['driver_sha256'] or sha(output / 'engine/freeze.json') != seal['engine_freeze_sha256']:
        raise ValueError('Driver or engine identity changed')
    protocol, source = verify_engine(output / 'engine')
    for name, digest in protocol['inputs'].items():
        if sha(name) != digest:
            raise ValueError('Independent field/reference changed: ' + name)
    target = output / 'native'
    if target.exists() or (output / 'receipt.json').exists():
        raise FileExistsError('Prior attempt retained')
    target.mkdir()
    rows = []
    for index, task in enumerate(protocol['tasks']):
        result = target / f'field-{index:02d}.json'
        log = target / f'field-{index:02d}.log'
        command = [shutil.which('java'), '-Xmx512M', '-XX:ActiveProcessorCount=1', '-cp',
            (output / 'engine/classpath.txt').read_text().strip(),
            'reframe4s.halfflow.RealImageCellGeometryAudit', task['task']['stem'], str(result)]
        with log.open('x') as stream:
            try:
                code = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                    timeout=protocol['timeout_seconds_per_field'], check=False).returncode
            except subprocess.TimeoutExpired:
                code = 124
        checks = {}
        actual = read(result) if code == 0 and result.exists() else None
        expected = task['cell_diagnostics']
        if actual is not None:
            for key in ['minimum_cell_jacobian', 'maximum_cell_jacobian', 'covered_fraction']:
                checks[key] = math.isclose(actual[key], expected[key],
                    rel_tol=protocol['relative_tolerance'], abs_tol=protocol['absolute_tolerance'])
            for key in ['covered_cells', 'total_declared_cells', 'folded_cells', 'samples_per_cell']:
                checks[key] = actual[key] == expected[key]
            checks['nonfinite_cells'] = actual['nonfinite_cells'] == 0
            checks['cell_pass'] = actual['cell_pass'] == expected['pass_criteria']
            checks['shared_gate_rejects_cell_failure'] = expected['pass_criteria'] or not actual['shared_forward_gate_accepted']
            checks['shared_gate_accepts_positive_control'] = task['task']['lane'] != 'C5' or actual['shared_forward_gate_accepted']
        passed = code == 0 and bool(checks) and all(checks.values())
        rows.append(dict(task=task['task'], exit_code=code, checks=checks, passed=passed,
            expected=expected, actual=actual, log_sha256=sha(log), result_sha256=sha(result) if result.exists() else None))
        print(index, task['task']['case'], task['task']['lane'], task['task']['arm'], task['task']['kind'], passed, flush=True)
    write(output / 'receipt.json', dict(schema='hfq-native-cell-parity-receipt-v1',
        source_sha256=source['sha256'], protocol_sha256=sha(output / 'engine/protocol.json'),
        rows=rows, passed=len(rows) == 48 and all(r['passed'] for r in rows),
        qualification_claim=False, runtime_comparison_allowed=False))
    if not all(r['passed'] for r in rows):
        raise SystemExit(1)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['freeze', 'run'])
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--reference', type=Path)
    parser.add_argument('--classpath', type=Path)
    args = parser.parse_args()
    if args.command == 'freeze':
        if args.reference is None or args.classpath is None:
            parser.error('freeze requires --reference and --classpath')
        freeze(args.output.resolve(), args.reference.resolve(), args.classpath.resolve())
    else:
        run(args.output.resolve())
