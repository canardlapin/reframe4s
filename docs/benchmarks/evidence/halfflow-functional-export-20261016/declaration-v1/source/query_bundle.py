#!/usr/bin/env python3
"""Prospective saved-query-bundle audit on fixed historical MRI checkpoints."""
import argparse
from datetime import datetime, timezone
import hashlib
import gzip
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import time
import zlib

import numpy as np
import nibabel as nib
from field_oracle import sample, world_nodes
from query_correction import evaluate

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'benchmarks/flashalign'))
from run_real_mri_recovery import freeze as freeze_engine, verify as verify_engine


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def read(path):
    return json.loads(Path(path).read_text())


def write(path, value):
    Path(path).write_text(json.dumps(value, indent=2, allow_nan=False) + '\n')


def freeze(output, classpath, checks, runner_checks):
    if output.exists():
        raise FileExistsError(output)
    if checks.read_text().count('Passed: Total 19, Failed 0, Errors 0, Passed 19') != 2:
        raise ValueError('19 focused checks on each platform required')
    if '[error]' in runner_checks.read_text() or '[success]' not in runner_checks.read_text():
        raise ValueError('Passing native runner compilation required')
    prior = ROOT / 'benchmarks/flashalign/local-results/hfq-diagnostics-20261014/offline-panel-oasis13-mmrr11-v1'
    rows = read(prior / 'results.json')['rows']
    output.mkdir(parents=True)
    inputs = {str(prior / n): sha(prior / n) for n in ['protocol.json', 'results.json']}
    tasks = []
    for case in ['oasis-13-to-14', 'mmrr-21-11-to-12']:
        row = max((r for r in rows if r['task']['pair'] == case and r['task']['policy'] == 'selected-control'),
                  key=lambda r: r['task']['event'])
        task = row['task']
        checkpoint = Path(task['checkpoint'])
        captured = read(checkpoint / 'checkpoint.json')
        if captured['info']['kind'] != 'LevelEnd' or captured['info']['levelIndex'] != 1:
            raise ValueError('Declared terminal fine checkpoint required')
        grids = {}
        for name, direction in [('fixed', 'fixed-to-moving'), ('moving', 'moving-to-fixed')]:
            path = Path(task['data']) / (name + '.nii.gz')
            image = nib.load(path)
            original_grid = Path(row['command'][-1]) / ('halfflow-' + direction + '.json')
            metadata = read(original_grid)
            if list(image.shape) != metadata['shape'] or not np.array_equal(image.affine.ravel(), metadata['index_to_ras_mm']):
                raise ValueError('Independent NIfTI lattice differs from original native export')
            target = output / (case + '-' + name + '-grid.json')
            write(target, dict(shape=list(image.shape), affine=image.affine.ravel().tolist()))
            grids[name] = str(target)
            for f in [path, target, original_grid]:
                inputs[str(f)] = sha(f)
        for f in [checkpoint / 'checkpoint.json'] + [checkpoint / ('midpoint-' + arm + suffix)
                for arm in ['fixed', 'moving'] for suffix in
                ['-residual.json', '-residual.f64', '-residual.valid-u8', '-affine.json']]:
            inputs[str(f)] = sha(f)
        tasks.append(dict(id=case, checkpoint=str(checkpoint), grids=grids, event=task['event']))
    sources = [Path(__file__).resolve()] + [Path(__file__).with_name(n).resolve() for n in ['field_oracle.py', 'query_correction.py']]
    p = dict(schema='hfq-query-bundle-study-v1', created_at=datetime.now(timezone.utc).isoformat(), cases=[],
             tasks=tasks, inputs=inputs, sources={str(p): sha(p) for p in sources},
             checks={str(p): sha(p) for p in [checks, runner_checks, checks.parent / 'ownership.log']},
             seed_policy='identity on captured work lattice, no new fitting or inverse optimization',
             selection='Terminal selected-control fine checkpoint in each previously analyzed OASIS13/MMRR11 trajectory; fixed before outcomes.',
             maximum_iterations=8, root_tolerance_mm=1e-8, maximum_step_mm=4., maximum_backtracks=12,
             coordinate_tolerance_mm=1e-7, cycle_limit_mm=.2, chunk_size=32768,
             timeout_seconds_per_native_task=1200, maximum_worker_seconds=3600,
             java_heap='6G', java_active_processors=1,
             environment=dict(python=sys.version, numpy=np.__version__, nibabel=nib.__version__),
             scope='Every original native endpoint voxel, including every boundary voxel, in both directions. '
                   'Require exact in-memory/reloaded value, diagnostic and success-status parity. '
                   'Independent Python uses original captured forward fields and NumPy-inverted original endpoint affines. '
                   'All failures retained; numerical query cycles never replace dense export admission or anatomical accuracy.',
             qualification_claim=False, dense_export_admitted=False, runtime_comparison_allowed=False)
    write(output / 'protocol.json', p)
    freeze_engine(output / 'engine', output / 'protocol.json', classpath)
    for source in sources:
        target = output / 'source' / source.name
        target.parent.mkdir(exist_ok=True)
        shutil.copyfile(source, target)
    print('FROZEN two terminal MRI checkpoints; native full lattices, exact reload parity,1e-7mm oracle/.2mm cycles', flush=True)


def verify(output):
    p, source = verify_engine(output / 'engine')
    for group in ['inputs', 'sources', 'checks']:
        for name, digest in p[group].items():
            if sha(name) != digest:
                raise ValueError('Frozen identity changed: ' + name)
    return p, source


def bundle_receipt(path, task):
    """Independently parse the shared binary format and bind it to original data."""
    data = path.read_bytes()
    if zlib.adler32(data[:-4]) != struct.unpack('>I', data[-4:])[0]:
        raise ValueError('Independent Adler checksum mismatch')
    offset = 0
    def unpack(fmt):
        nonlocal offset
        result = struct.unpack_from('>' + fmt, data, offset)
        offset += struct.calcsize('>' + fmt)
        return result
    def text():
        nonlocal offset
        length, = unpack('H')
        value = data[offset:offset+length].decode('utf-8') # audit role labels are ASCII
        offset += length
        return value
    if unpack('II') != (0x48465142, 1) or text() != 'halfflow-boundary-linear-newton-v1':
        raise ValueError('Unknown bundle contract')
    if unpack('IddI') != (8, 1e-8, 4., 12):
        raise ValueError('Bounded solver policy changed')
    frames = []
    for _ in range(3):
        owner, = unpack('I')
        domain = text()
        shape = list(unpack('III'))
        affine = np.array(unpack('16d')).reshape(4, 4)
        frames.append(dict(owner=owner, domain=domain, shape=shape, affine=affine))
    if [f['owner'] for f in frames] != [0, 0, 2]:
        raise ValueError('Canonical physical owner aliases changed')
    cp = Path(task['checkpoint'])
    original = read(cp / 'midpoint-fixed-residual.json')
    if frames[0]['shape'] != original['shape'] or not np.array_equal(frames[0]['affine'].ravel(), original['index_to_ras_mm']):
        raise ValueError('Work lattice changed')
    for index, arm in [(1, 'fixed'), (2, 'moving')]:
        grid = read(task['grids'][arm])
        if frames[index]['shape'] != grid['shape'] or not np.array_equal(frames[index]['affine'].ravel(), grid['affine']):
            raise ValueError('Endpoint lattice changed')
    operators = np.array(unpack('64d')).reshape(4, 4, 4)
    for index, arm in [(0, 'fixed'), (2, 'moving')]:
        original_affine = np.array(read(cp / ('midpoint-' + arm + '-affine.json'))).reshape(4, 4)
        if not np.array_equal(operators[index], original_affine):
            raise ValueError('Original endpoint affine changed')
        if np.max(np.abs(operators[index+1] - np.linalg.inv(original_affine))) > 1e-10:
            raise ValueError('Saved inverse affine differs from independent inverse')
    n = int(np.prod(frames[0]['shape']))
    identity = world_nodes(frames[0]['shape'], frames[0]['affine'])
    errors = []
    for arm, seed in [('fixed', False), ('fixed', True), ('moving', False), ('moving', True)]:
        values = np.frombuffer(data, dtype='>f8', count=3*n, offset=offset).reshape(3, n).T
        offset += 24*n
        validity = np.frombuffer(data, dtype='u1', count=n, offset=offset)
        offset += n
        expected = identity if seed else np.fromfile(cp / ('midpoint-' + arm + '-residual.f64'), dtype='>f8').reshape(n, 3)
        valid = np.ones(n, dtype=np.uint8) if seed else np.fromfile(cp / ('midpoint-' + arm + '-residual.valid-u8'), dtype='u1')
        difference = float(np.max(np.abs(values - expected)))
        if difference > (1e-12 if seed else 0.) or not np.array_equal(validity, valid):
            raise ValueError('Saved original field/seed/support changed')
        errors.append(difference)
    if offset != len(data) - 4:
        raise ValueError('Unexpected bundle trailing data')
    return dict(checksum_verified=True, field_and_seed_maximum_differences_mm=errors,
                original_affines_exact=True, owner_aliases=[f['owner'] for f in frames],
                sha256=sha(path), bytes=len(data))


def oracle(task, output, protocol):
    cp = Path(task['checkpoint'])
    metadata = read(cp / 'midpoint-fixed-residual.json')
    shape = metadata['shape']
    affine = np.array(metadata['index_to_ras_mm']).reshape(4, 4)
    n = int(np.prod(shape))
    fields = {}
    for arm in ['fixed', 'moving']:
        fields[arm] = (np.fromfile(cp / ('midpoint-' + arm + '-residual.f64'), dtype='>f8').reshape(n, 3).astype(float),
                       np.fromfile(cp / ('midpoint-' + arm + '-residual.valid-u8'), dtype='u1').astype(bool))
    identity = world_nodes(shape, affine)
    operators = {arm: np.array(read(cp / ('midpoint-' + arm + '-affine.json'))).reshape(4, 4) for arm in fields}
    def apply(operator, points):
        return points @ operator[:3, :3].T + operator[:3, 3]
    def transform(source, target, query):
        target_work = apply(np.linalg.inv(operators[source]), query)
        forward, valid = fields[source]
        roots, supported, _ = evaluate(forward, valid, identity, np.ones(n, dtype=bool), shape, affine, target_work, 8)
        check, available, jac = sample(forward, valid, shape, affine, roots, True)
        ok = supported & available & (np.linalg.norm(check-target_work, axis=1) <= 1e-8) & (np.linalg.det(jac) > 0)
        destination, available, _ = sample(*fields[target], shape, affine, roots)
        return apply(operators[target], destination), ok & available
    rows = []
    for direction, source, target in [('FixedToMoving', 'fixed', 'moving'), ('MovingToFixed', 'moving', 'fixed')]:
        grid = read(task['grids'][source])
        dims = np.array(grid['shape'])
        basis = np.array(grid['affine']).reshape(4, 4)
        count = int(np.prod(dims))
        directory = output / direction
        status = np.memmap(directory / 'status.u8', dtype='u1', mode='r', shape=(count,))
        mapped = np.memmap(directory / 'mapped.f64', dtype='>f8', mode='r', shape=(count, 3))
        cycles = np.memmap(directory / 'cycles.f64', dtype='>f8', mode='r', shape=(count, 3))
        counts = dict(eligible=count, mapped_success=0, cycle_success=0, status_mismatches=0,
                      boundary_eligible=0, boundary_success=0)
        differences = [0., 0.]
        maximum_cycle = 0.
        for start in range(0, count, protocol['chunk_size']):
            end = min(count, start + protocol['chunk_size'])
            indices = np.arange(start, end)
            voxels = np.column_stack([indices % dims[0], indices // dims[0] % dims[1], indices // (dims[0]*dims[1])])
            boundary = np.any((voxels == 0) | (voxels == dims-1), axis=1)
            query = apply(basis, voxels)
            first, ok = transform(source, target, query)
            # Failed estimates are never forwarded into the reverse map.
            back = np.full_like(query, np.nan)
            back_ok = np.zeros(len(query), dtype=bool)
            if ok.any():
                back[ok], back_ok[ok] = transform(target, source, first[ok])
            expected_status = ok.astype(np.uint8) | (back_ok.astype(np.uint8) << 1)
            counts['status_mismatches'] += int(np.count_nonzero(expected_status != status[start:end]))
            counts['mapped_success'] += int(ok.sum())
            counts['cycle_success'] += int(back_ok.sum())
            counts['boundary_eligible'] += int(boundary.sum())
            counts['boundary_success'] += int((boundary & back_ok).sum())
            for slot, expected, actual, valid in [(0, first, mapped[start:end], ok & ((status[start:end] & 1) != 0)),
                                                   (1, back, cycles[start:end], back_ok & ((status[start:end] & 2) != 0))]:
                if valid.any():
                    differences[slot] = max(differences[slot], float(np.linalg.norm(expected[valid]-actual[valid], axis=1).max()))
            if back_ok.any():
                maximum_cycle = max(maximum_cycle, float(np.linalg.norm(back[back_ok]-query[back_ok], axis=1).max()))
        native = read(directory / 'result.json')
        counts_match = all(native[k] == counts[k] for k in ['eligible', 'mapped_success', 'cycle_success', 'boundary_eligible', 'boundary_success'])
        row = dict(direction=direction, **counts, maximum_coordinate_differences_mm=differences,
                   maximum_successful_cycle_mm=maximum_cycle, native_counts_match=counts_match,
                   oracle_parity_pass=counts_match and counts['status_mismatches'] == 0 and max(differences) <= protocol['coordinate_tolerance_mm'],
                   full_cycle_pass=counts['cycle_success'] == count and maximum_cycle <= protocol['cycle_limit_mm'])
        write(directory / 'oracle.json', row)
        rows.append(row)
        print(task['id'], direction, row, flush=True)
    return rows


def worker(output):
    p, source = verify(output)
    if (output / 'launch.json').exists():
        raise FileExistsError('Preserve prior attempt')
    write(output / 'launch.json', dict(at=datetime.now(timezone.utc).isoformat()))
    rows = []
    for task in p['tasks']:
        directory = output / task['id']
        log = output / (task['id'] + '.log')
        command = [shutil.which('java'), '-Xmx6G', '-XX:ActiveProcessorCount=1', '-cp',
                   (output / 'engine/classpath.txt').read_text().strip(), 'reframe4s.halfflow.RealImageQueryBundleAudit',
                   task['checkpoint'], task['grids']['fixed'], task['grids']['moving'], str(directory)]
        with log.open('x') as stream:
            try:
                code = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT,
                                      timeout=p['timeout_seconds_per_native_task']).returncode
            except subprocess.TimeoutExpired:
                code = 124
        row = dict(task=task, exit_code=code, log_sha256=sha(log))
        if code == 0 and (directory / 'result.json').exists():
            row['native'] = read(directory / 'result.json')
            row['bundle'] = bundle_receipt(directory / 'transform.hfqb', task)
            row['oracle'] = oracle(task, directory, p)
            row['payloads'] = {str(f.relative_to(directory)): sha(f) for f in directory.rglob('*') if f.is_file()}
        write(output / (task['id'] + '-receipt.json'), row)
        rows.append(row)
        print('COMPLETE', task['id'], 'exit', code, flush=True)
    verify(output)
    write(output / 'receipt.json', dict(rows=rows, source_sha256=source['sha256'],
          all_native_complete=all(r['exit_code'] == 0 for r in rows), qualification_claim=False, dense_export_admitted=False))


def run(output):
    p, _ = verify(output)
    if (output / 'launch.json').exists() or (output / 'completion.json').exists():
        raise FileExistsError('Preserve prior attempts')
    start = time.monotonic()
    with (output / 'driver.log').open('x') as stream:
        try:
            code = subprocess.run([sys.executable, str(Path(__file__).resolve()), 'worker', '--output', str(output)],
                                  stdout=stream, stderr=subprocess.STDOUT, timeout=p['maximum_worker_seconds']).returncode
        except subprocess.TimeoutExpired:
            code = 124
    write(output / 'completion.json', dict(exit_code=code, wall_seconds=time.monotonic()-start,
          receipt_present=(output / 'receipt.json').exists(), qualification_claim=False))
    print('COMPLETE', code, flush=True)
    if code:
        raise SystemExit(code)


def archive(study, destination, phase):
    if destination.exists():
        raise FileExistsError(destination)
    p, _ = verify_engine(study / 'engine')
    if phase == 'declaration' and (study / 'launch.json').exists():
        raise ValueError('Declaration must be archived before launch')
    if phase == 'outcomes' and not (study / 'completion.json').exists():
        raise ValueError('Terminal receipt required')
    files = {}
    for path in study.rglob('*'):
        rel = path.relative_to(study)
        if not path.is_file() or 'frozen-classpath' in rel.parts:
            continue
        if 'frozen-source' in rel.parts or path.suffix in ['.json', '.log', '.jsonl', '.py', '.txt']:
            files[path] = str(rel)
    local = []
    for number, (name, digest) in enumerate(sorted(p['inputs'].items())):
        path = Path(name)
        if sha(path) != digest:
            raise ValueError('Input changed')
        if path.suffix == '.json':
            files[path] = f'inputs/{number:03d}-' + path.name
        else:
            local.append(dict(path=name, sha256=digest, bytes=path.stat().st_size))
    for name, digest in p['checks'].items():
        path = Path(name)
        if sha(path) != digest:
            raise ValueError('Check log changed')
        files[path] = 'checks/' + path.name
    for name, digest in p['sources'].items():
        path = study / 'source' / Path(name).name
        if sha(path) != digest:
            raise ValueError('Frozen Python changed')
    if phase == 'outcomes':
        for task in p['tasks']:
            directory = study / task['id']
            for path in directory.rglob('*'):
                if path.is_file() and path.suffix in ['.hfqb', '.f64', '.u8']:
                    local.append(dict(path=str(path), sha256=sha(path), bytes=path.stat().st_size))
    destination.mkdir(parents=True)
    inventory = []
    for source, name in sorted(files.items()):
        target = destination / name
        original = source.read_bytes()
        if source.suffix in ['.log', '.jsonl']:
            target = target.with_name(target.name + '.gz')
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open('xb') as stream, gzip.GzipFile(fileobj=stream, filename='', mtime=0, mode='wb') as compressed:
                compressed.write(original)
            assert gzip.decompress(target.read_bytes()) == original
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            assert target.read_bytes() == original
        inventory.append(dict(path=str(target.relative_to(destination)), sha256=sha(target), original_sha256=sha(source)))
    write(destination / 'artifact-manifest.json', dict(schema='hfq-query-bundle-archive-v1', phase=phase,
          protocol_sha256=sha(study / 'protocol.json'), files=inventory, local_payloads=local,
          launched=(study / 'launch.json').exists(), qualification_claim=False, dense_export_admitted=False))
    print('ARCHIVED', phase, len(inventory), 'compact files', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['freeze', 'run', 'worker', 'archive'])
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--classpath', type=Path)
    parser.add_argument('--checks', type=Path)
    parser.add_argument('--runner-checks', type=Path)
    parser.add_argument('--destination', type=Path)
    parser.add_argument('--phase', choices=['declaration', 'outcomes'])
    args = parser.parse_args()
    if args.command == 'freeze':
        freeze(args.output.resolve(), args.classpath.resolve(), args.checks.resolve(), args.runner_checks.resolve())
    elif args.command == 'archive':
        archive(args.output.resolve(), args.destination.resolve(), args.phase)
    else:
        globals()[args.command](args.output.resolve())
