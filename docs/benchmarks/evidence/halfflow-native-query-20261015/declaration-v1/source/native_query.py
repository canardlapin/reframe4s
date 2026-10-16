#!/usr/bin/env python3
"""Frozen native residual parity and identity-affine endpoint-composition audit."""
import argparse
from datetime import datetime, timezone
import gzip
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

import numpy as np
from analyze_c04 import load, read, write, sha
from field_oracle import sample, world_nodes
from query_correction import evaluate

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'benchmarks/flashalign'))
from run_real_mri_recovery import freeze as freeze_engine, verify as verify_engine


def freeze(output, reference, classpath, checks):
    if output.exists():
        raise FileExistsError(output)
    prior = read(reference / 'results.json')
    rows = [r for r in prior['variants'] if r['budget'] == 8]
    if len(rows) != 24 or not all(r['all_queries_root_converged'] for r in rows):
        raise ValueError('Complete 24-field eight-correction reference required')
    for name in ['checks.log', 'integration-checks.log', 'ownership.log', 'final-checks.log', 'guard-checks-initial.log']:
        if not (checks / name).exists():
            raise ValueError('Missing native verification log: ' + name)
    for name, total in [('checks.log', 47), ('integration-checks.log', 57), ('final-checks.log', 11)]:
        if (checks / name).read_text().count(f'Passed: Total {total}, Failed 0, Errors 0, Passed {total}') != 2:
            raise ValueError('Expected passing JVM and JS checks: ' + name)
    if 'Symbol ownership is unique across ' not in (checks / 'ownership.log').read_text() or \
            '66 canonical owners verified.' not in (checks / 'ownership.log').read_text():
        raise ValueError('Pinned-owner validation required')
    output.mkdir(parents=True)
    inputs = {str(reference / n): sha(reference / n) for n in ['protocol.json', 'results.json', 'completion.json']}
    tasks = []
    for index, row in enumerate(rows):
        f = row['field']
        values, valid, shape, affine, _ = load(Path(f['forward']))
        if not valid.all():
            raise ValueError('The declared complete reference must retain all nodes')
        nodes = world_nodes(shape, affine)
        query = output / f'residual-{index:02d}-queries.f64'
        np.vstack([nodes, values]).astype('>f8').tofile(query)
        inputs[str(query)] = sha(query)
        for stem in [f['forward'], f['inverse']]:
            for suffix in ['.json', '.f64', '.valid-u8']:
                path = Path(stem + suffix)
                inputs[str(path)] = sha(path)
        for payload in row['payloads']:
            path = reference / payload['path']
            if sha(path) != payload['sha256']:
                raise ValueError('Reference coordinates changed')
            inputs[str(path)] = sha(path)
        tasks.append(dict(id=f'residual-{index:02d}', mode='residual', field=f, original_nodes=len(nodes),
                          query=str(query), reference_payloads=[str(reference / a['path']) for a in row['payloads']]))
    grouped = {}
    for row in rows:
        f = row['field']
        grouped.setdefault((f['case'], f['lane']), {})[f['arm']] = f
    if len(grouped) != 12 or not all(set(g) == {'fixed', 'moving'} for g in grouped.values()):
        raise ValueError('All twelve two-arm states required')
    for index, ((case, lane), fields) in enumerate(sorted(grouped.items())):
        _, _, shape, affine, _ = load(Path(fields['fixed']['forward']))
        query = output / f'endpoint-{index:02d}-queries.f64'
        world_nodes(shape, affine).astype('>f8').tofile(query)
        inputs[str(query)] = sha(query)
        for direction in ['FixedToMoving', 'MovingToFixed']:
            tasks.append(dict(id=f'endpoint-{index:02d}-{direction}', mode='endpoint', case=case, lane=lane,
                              fields=fields, direction=direction, query=str(query), original_nodes=int(np.prod(shape))))
    sources = [Path(__file__).resolve()] + [Path(__file__).with_name(n).resolve() for n in
              ['query_correction.py', 'field_oracle.py', 'analyze_c04.py']]
    sources += [ROOT / 'benchmarks/flashalign/run_real_mri_recovery.py']
    logs = {str(checks / n): sha(checks / n) for n in ['checks.log', 'integration-checks.log', 'ownership.log', 'final-checks.log', 'guard-checks-initial.log']}
    p = dict(schema='hfq-native-query-v1', created_at=datetime.now(timezone.utc).isoformat(), cases=[],
             tasks=tasks, inputs=inputs, sources={str(p): sha(p) for p in sources}, verification_logs=logs,
             maximum_iterations=8, root_tolerance_mm=1e-8, maximum_step_mm=4., maximum_backtracks=12,
             coordinate_comparison_tolerance_mm=1e-7, cycle_limit_mm=.2, timeout_seconds_per_task=180,
             maximum_worker_seconds=1800,
             java_heap='2G', java_active_processors=1,
             environment=dict(python=sys.version, numpy=np.__version__,
                 threads={k: os.environ.get(k) for k in ['OPENBLAS_NUM_THREADS', 'OMP_NUM_THREADS', 'MKL_NUM_THREADS', 'VECLIB_MAXIMUM_THREADS']}),
             scope='24 residual parity tasks and24 identity-affine functional endpoint round trips, all original nodes. '
                   'Endpoint affines explicitly identity: no historical endpoint attribution or raster-export qualification. '
                   'No invalid/capped query can become a map value. Failure denominators retained.',
             qualification_claim=False, runtime_comparison_allowed=False)
    write(output / 'declaration.json', p)
    freeze_engine(output / 'engine', output / 'declaration.json', classpath)
    verify_engine(output / 'engine')
    for source in sources:
        target = output / 'source' / source.name
        target.parent.mkdir(exist_ok=True)
        shutil.copyfile(source, target)
    write(output / 'seal.json', dict(driver_sha256=sha(Path(__file__)), engine_freeze_sha256=sha(output / 'engine/freeze.json')))
    print('FROZEN native query:24 residual +24 endpoint tasks;1e-7mm coordinate parity;0.2mm complete cycles', flush=True)


def python_endpoint(task, query):
    fields = task['fields']
    names = ['fixed', 'moving'] if task['direction'] == 'FixedToMoving' else ['moving', 'fixed']
    loaded = {}
    for name in names:
        f, valid, shape, affine, _ = load(Path(fields[name]['forward']))
        g, gv, _, _, _ = load(Path(fields[name]['inverse']))
        loaded[name] = (f, valid, g, gv, shape, affine)
    result = query
    success = np.ones(len(query), dtype=bool)
    for source, target in [names, list(reversed(names))]:
        f, valid, g, gv, shape, affine = loaded[source]
        inverse, supported, _ = evaluate(f, valid, g, gv, shape, affine, result, 8)
        mapped, available, jac = sample(f, valid, shape, affine, inverse, True)
        success &= supported & available & (np.linalg.norm(mapped-result, axis=1) <= 1e-8) & (np.linalg.det(jac) > 0)
        target_f, target_valid, _, _, target_shape, target_affine = loaded[target]
        result, supported, _ = sample(target_f, target_valid, target_shape, target_affine, inverse)
        success &= supported
    return result, success


def worker(output):
    p, source = verify_engine(output / 'engine')
    seal = read(output / 'seal.json')
    if sha(Path(__file__)) != seal['driver_sha256']:
        raise ValueError('Driver changed')
    for name, h in {**p['inputs'], **p['sources'], **p['verification_logs']}.items():
        if sha(name) != h:
            raise ValueError('Frozen input/source changed: ' + name)
    if (output / 'launch.json').exists():
        raise FileExistsError('Preserve prior attempts')
    write(output / 'launch.json', dict(at=datetime.now(timezone.utc).isoformat()))
    results = []
    for task in p['tasks']:
        directory = output / task['id']
        directory.mkdir()
        result, coordinates, log = [directory / n for n in ['native.json', 'coordinates.f64', 'execution.log']]
        command = [shutil.which('java'), '-Xmx2G', '-XX:ActiveProcessorCount=1', '-cp',
                   (output / 'engine/classpath.txt').read_text().strip(), 'reframe4s.halfflow.RealImageInverseQueryAudit']
        if task['mode'] == 'residual':
            f = task['field']
            command += ['residual', f['forward'], f['inverse'], task['query'], str(result), str(coordinates)]
        else:
            a, b = [task['fields'][name] for name in ['fixed', 'moving']]
            command += ['endpoint', a['forward'], a['inverse'], b['forward'], b['inverse'],
                        task['query'], str(result), str(coordinates), task['direction']]
        with log.open('x') as stream:
            try:
                code = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT,
                                      timeout=p['timeout_seconds_per_task']).returncode
            except subprocess.TimeoutExpired:
                code = 124
        query = np.fromfile(task['query'], dtype='>f8').reshape(-1, 3)
        expected_count = task['original_nodes'] * (2 if task['mode'] == 'residual' else 1)
        if len(query) != expected_count:
            raise ValueError('Original query denominator changed')
        if task['mode'] == 'residual':
            by_name = {Path(n).name: n for n in task['reference_payloads']}
            expected = np.vstack([np.load(by_name['original.npy'], allow_pickle=False),
                                  np.load(by_name['forward.npy'], allow_pickle=False)])
            supported = np.ones(len(query), dtype=bool)
        else:
            expected, supported = python_endpoint(task, query)
        actual = read(result) if code == 0 and result.exists() else None
        checks = {}
        maximum_difference = None
        maximum_cycle = None
        if actual is not None and coordinates.exists():
            values = np.fromfile(coordinates, dtype='>f8').reshape(-1, 3)
            if len(values) == len(query):
                native_supported = np.ones(len(query), dtype=bool)
                for failure in actual['failures']:
                    native_supported[failure['index']] = False
                checks['eligible'] = actual['eligible'] == expected_count
                checks['status_agreement'] = bool(np.array_equal(supported, native_supported))
                checks['success_count'] = actual['succeeded'] == int(supported.sum())
                common = supported & native_supported
                maximum_difference = float(np.linalg.norm(values[common]-expected[common], axis=1).max()) if common.any() else None
                checks['coordinates'] = maximum_difference is not None and maximum_difference <= p['coordinate_comparison_tolerance_mm']
                if task['mode'] == 'endpoint':
                    maximum_cycle = float(np.linalg.norm(values[common]-query[common], axis=1).max()) if common.any() else None
        passed = code == 0 and bool(checks) and all(checks.values())
        row = dict(task=task, exit_code=code, native=actual, checks=checks, parity_pass=passed,
                   reference_succeeded=int(supported.sum()), eligible=expected_count,
                   maximum_coordinate_difference_mm=maximum_difference, maximum_endpoint_cycle_mm=maximum_cycle,
                   endpoint_cycle_pass=bool(task['mode'] == 'endpoint' and actual is not None and
                       actual['succeeded'] == expected_count and maximum_cycle is not None and maximum_cycle <= p['cycle_limit_mm']),
                   log_sha256=sha(log), result_sha256=sha(result) if result.exists() else None,
                   coordinates_sha256=sha(coordinates) if coordinates.exists() else None)
        write(directory / 'receipt.json', row)
        results.append(row)
        print(task['id'], 'exit', code, 'parity', passed, 'difference', maximum_difference,
              'endpoint-cycle', maximum_cycle, 'succeeded', actual['succeeded'] if actual else None, flush=True)
    verify_engine(output / 'engine')
    for name, h in {**p['inputs'], **p['sources'], **p['verification_logs']}.items():
        if sha(name) != h:
            raise ValueError('Frozen identity changed during run')
    write(output / 'receipt.json', dict(source_sha256=source['sha256'], rows=results,
          all_tasks_complete=len(results) == 48, all_parity_pass=len(results) == 48 and all(r['parity_pass'] for r in results),
          qualification_claim=False, runtime_comparison_allowed=False, raster_export_qualified=False))


def run(output):
    if (output / 'launch.json').exists() or (output / 'completion.json').exists():
        raise FileExistsError('Preserve existing attempts')
    p, _ = verify_engine(output / 'engine')
    start = time.monotonic()
    with (output / 'driver.log').open('x') as log:
        try:
            code = subprocess.run([sys.executable, str(Path(__file__).resolve()), 'worker', '--output', str(output)],
                                  stdout=log, stderr=subprocess.STDOUT, timeout=p['maximum_worker_seconds']).returncode
        except subprocess.TimeoutExpired:
            code = 124
    if not (output / 'receipt.json').exists():
        rows = [read(output / t['id'] / 'receipt.json') for t in p['tasks'] if (output / t['id'] / 'receipt.json').exists()]
        write(output / 'receipt.json', dict(rows=rows, all_tasks_complete=False, all_parity_pass=False,
            incomplete_task_ids=[t['id'] for t in p['tasks'] if not (output / t['id'] / 'receipt.json').exists()],
            qualification_claim=False, runtime_comparison_allowed=False, raster_export_qualified=False))
    write(output / 'completion.json', dict(at=datetime.now(timezone.utc).isoformat(), exit_code=code,
          elapsed_diagnostic_seconds=time.monotonic()-start, driver_log_sha256=sha(output / 'driver.log'),
          runtime_comparison_allowed=False))
    if code:
        raise SystemExit(code)


def archive(output, destination, phase):
    if destination.exists():
        raise FileExistsError(destination)
    p, _ = verify_engine(output / 'engine')
    files = [output / n for n in ['declaration.json', 'seal.json']]
    files += list((output / 'source').glob('*'))
    files += [output / 'engine' / n for n in ['source.json', 'freeze.json', 'protocol.json', 'compiled-classpath.json', 'classpath.txt', 'inputs.json']]
    files += list((output / 'engine/frozen-source').rglob('*'))
    if phase == 'outcomes':
        if not (output / 'receipt.json').exists():
            raise ValueError('Terminal complete attempt required')
        files += [output / n for n in ['receipt.json', 'launch.json', 'completion.json', 'driver.log']]
        files += [f for t in p['tasks'] for f in (output / t['id']).glob('*') if f.suffix in ['.json', '.log']]
    destination.mkdir(parents=True)
    inventory = []
    for path in sorted(set(files)):
        if not path.is_file():
            continue
        target = destination / path.relative_to(output)
        if path.suffix == '.log':
            target = target.with_name(target.name + '.gz')
        target.parent.mkdir(parents=True, exist_ok=True)
        if path.suffix == '.log':
            with target.open('xb') as stream, gzip.GzipFile(fileobj=stream, filename='', mtime=0, mode='wb') as compressed:
                compressed.write(path.read_bytes())
        else:
            shutil.copyfile(path, target)
        inventory.append(dict(path=str(target.relative_to(destination)), sha256=sha(target), original_sha256=sha(path)))
    for name, digest in p['verification_logs'].items():
        path = Path(name)
        target = destination / 'checks' / path.name
        target.parent.mkdir(exist_ok=True)
        shutil.copyfile(path, target)
        if sha(target) != digest:
            raise ValueError('Check log changed')
        inventory.append(dict(path=str(target.relative_to(destination)), sha256=digest, original_sha256=digest))
    write(destination / 'artifact-manifest.json', dict(schema='hfq-native-query-archive-v1', phase=phase, files=inventory,
          identified_local_inputs=p['inputs'], qualification_claim=False, runtime_comparison_allowed=False,
          mri_or_dense_map_payloads=False, raster_export_qualified=False))
    print('ARCHIVED native query', phase, len(inventory), 'compact entries')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['freeze', 'run', 'worker', 'archive'])
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--reference', type=Path)
    parser.add_argument('--classpath', type=Path)
    parser.add_argument('--checks', type=Path)
    parser.add_argument('--archive', type=Path)
    parser.add_argument('--phase', choices=['declaration', 'outcomes'])
    args = parser.parse_args()
    if args.command == 'freeze':
        if None in [args.reference, args.classpath, args.checks]:
            parser.error('freeze requires reference, classpath, checks')
        freeze(args.output.resolve(), args.reference.resolve(), args.classpath.resolve(), args.checks.resolve())
    elif args.command == 'run':
        run(args.output.resolve())
    elif args.command == 'worker':
        worker(args.output.resolve())
    else:
        if args.archive is None or args.phase is None:
            parser.error('archive requires archive and phase')
        archive(args.output.resolve(), args.archive.resolve(), args.phase)
