#!/usr/bin/env python3
"""Prospective inverse-lattice resolution study; immutable corrected forward fields."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
import time

import numpy as np
from analyze_c04 import identify, load, read, sha, write
from field_oracle import composition_errors, sample, solve, world_nodes

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'benchmarks/flashalign'))
from real_mri_cell_topology import cell_diagnostics


def refined_grid(shape, affine, factor):
    if not isinstance(factor, int) or factor < 1:
        raise ValueError('Positive integer refinement factor required')
    refined = ((np.asarray(shape) - 1) * factor + 1).tolist()
    basis = np.array(affine, dtype=float, copy=True)
    basis[:3, :3] /= factor
    return refined, basis


def solve_lattice(forward, valid, shape, affine, stored, stored_valid,
                  inverse_shape, inverse_affine, method, chunk_size):
    targets = world_nodes(inverse_shape, inverse_affine)
    roots = np.empty_like(targets)
    available = np.zeros(len(targets), dtype=bool)
    chunks = []
    for start in range(0, len(targets), chunk_size):
        end = min(start + chunk_size, len(targets))
        query = targets[start:end]
        seed, seed_valid, _ = sample(stored, stored_valid, shape, affine, query)
        if not seed_valid.all():
            raise ValueError('Unsupported inverse seed; no substitute seed permitted')
        solved, report = solve(forward, valid, shape, affine, query, seed, **method)
        sampled, supported, _ = sample(forward, valid, shape, affine, solved)
        roots[start:end] = solved
        available[start:end] = supported & (np.linalg.norm(sampled - query, axis=1) <= method['tolerance'])
        chunks.append(dict(start=start, end=end, **report))
    totals = {key: sum(r[key] for r in chunks) for key in
              ['targets', 'converged', 'unresolved', 'invalid', 'blocked', 'nonpositive_root_jacobians']}
    totals.update(iterations_used=max(r['iterations_used'] for r in chunks),
                  maximum_equation_residual_mm=max(r['maximum_equation_residual_mm'] for r in chunks),
                  minimum_root_jacobian=min(r['minimum_root_jacobian'] for r in chunks),
                  all_converged=all(r['all_converged'] for r in chunks), chunks=chunks)
    return roots, available, totals


def inverse_cells(roots, valid, shape, affine):
    if not valid.any():
        return dict(pass_criteria=False, reason='No converged supported inverse nodes')
    coords = np.stack([roots[:, c].reshape(shape, order='F') for c in range(3)], axis=-1)
    # The existing evaluator requires at least one supported complete cell.
    support = valid.reshape(shape, order='F')
    covered = np.ones(tuple(np.asarray(shape) - 1), dtype=bool)
    for x in range(2):
        for y in range(2):
            for z in range(2):
                covered &= support[x:shape[0]-1+x, y:shape[1]-1+y, z:shape[2]-1+z]
    if not covered.any():
        return dict(pass_criteria=False, reason='No supported complete inverse cells',
                    total_declared_cells=int(covered.size), covered_cells=0)
    return cell_diagnostics(coords, support, affine)


def query_partition(forward, roots, valid, shape, affine, inverse_shape, inverse_affine):
    nodes = world_nodes(shape, affine)
    voxel = (forward - inverse_affine[:3, 3]) @ np.linalg.inv(inverse_affine[:3, :3]).T
    outside = np.any((voxel < -1e-10) | (voxel > np.asarray(inverse_shape) - 1 + 1e-10), axis=1)
    evaluated, available, _ = sample(roots, valid, inverse_shape, inverse_affine, forward)
    errors = np.linalg.norm(evaluated - nodes, axis=1)
    result = {}
    for name, region in [('inside_inverse_box', ~outside), ('extrapolated_inverse_queries', outside)]:
        usable = region & available
        result[name] = dict(eligible=int(region.sum()), evaluated=int(usable.sum()),
                            invalid=int((region & ~available).sum()),
                            maximum_mm=float(errors[usable].max()) if usable.any() else None)
    return result


def freeze(output, previous, checks):
    if output.exists():
        raise FileExistsError(output)
    prior = read(previous / 'protocol.json')
    if not prior.get('corrected_admission'):
        raise ValueError('Corrected-admission root study required')
    if 'OK' not in checks.read_text():
        raise ValueError('Passing analytic checks required')
    capture = Path(prior['capture'])
    receipt = read(capture / 'receipt.json')
    if receipt['exit_code'] != 0 or not receipt['source_and_inputs_unchanged']:
        raise ValueError('Terminal immutable capture required')
    audit_path = Path(prior['required_cell_audit'])
    audit = read(audit_path)
    if len(audit['rows']) != 48:
        raise ValueError('All 48 corrected fields must have independent cell audits')
    directory = identify(capture, prior['target_case'], prior['target_lane'])
    inputs = [previous / 'protocol.json', previous / 'results.json', previous / 'input-seal.json',
              capture / 'protocol.json', capture / 'receipt.json', audit_path, checks]
    fields = {}
    for arm in prior['arms']:
        fields[arm] = {}
        for kind in ['forward', 'inverse']:
            stem = directory / (arm + '-' + kind)
            fields[arm][kind] = str(stem)
            inputs += [stem.with_suffix(s) for s in ['.json', '.f64', '.valid-u8']]
            if kind == 'forward':
                digest = load(stem)[4]
                matches = [r for r in audit['rows'] if
                           (r['task']['case'], r['task']['lane'], r['task']['arm'], r['task']['kind']) ==
                           (prior['target_case'], prior['target_lane'], arm, kind)]
                if len(matches) != 1 or not matches[0]['cell_diagnostics']['pass_criteria'] or \
                        matches[0]['task']['central_difference']['field_sha256'] != digest:
                    raise ValueError('The exact forward field must pass independent cell admission')
    sources = [Path(__file__).resolve(), Path(__file__).with_name('test_inverse_refinement.py').resolve(),
               Path(__file__).with_name('field_oracle.py').resolve(),
               Path(__file__).with_name('analyze_c04.py').resolve(),
               ROOT / 'benchmarks/flashalign/real_mri_cell_topology.py',
               ROOT / 'benchmarks/flashalign/real_image_court.py']
    output.mkdir(parents=True)
    protocol = dict(schema='hfq-inverse-lattice-refinement-v1', created_at=datetime.now(timezone.utc).isoformat(),
                    environment=dict(python=sys.version, numpy=np.__version__, executable=sys.executable,
                        numerical_threads={k: os.environ.get(k) for k in
                            ['OPENBLAS_NUM_THREADS', 'OMP_NUM_THREADS', 'MKL_NUM_THREADS', 'VECLIB_MAXIMUM_THREADS']}),
                    previous=str(previous), fields=fields, inputs={str(p): sha(p) for p in inputs},
                    sources={str(p): sha(p) for p in sources}, factors=[1, 2, 4],
                    inverse_domain='Original inverse physical box; basis divided by factor; no padding or crop.',
                    method=prior['method'], chunk_size=32768, interior_margin=6,
                    maximum_error_mm=0.2, baseline_tolerance_mm=1e-10, maximum_worker_seconds=1800,
                    order='fixed factors 1/2/4, then moving factors 1/2/4; all declared variants attempted unless resource deadline.',
                    denominator='Both compositions retain all 50,653 original nodes per arm. No root, query or boundary point may be dropped.',
                    seed='Existing stored inverse evaluated at each target; no restarts or substitute seeds.',
                    topology='Unchanged complete-cell evaluator, all cells/eight corners and center, floor .05, coverage .99.',
                    success='All roots converge with positive root Jacobians; both complete original-domain cycles <=.2mm with no invalid query; inverse cell criteria pass.',
                    partition='Also report original forward queries inside/outside the inverse physical box; diagnostic only, never an acceptance crop.',
                    scope='Synthetic corrected r24 inverse representation only. No optimizer, forward-field, production source or export-contract change. '
                          'No endpoint, anatomical or global-invertibility qualification; grid refinement alone cannot certify continuous invertibility.',
                    qualification_claim=False, runtime_comparison_allowed=False)
    write(output / 'protocol.json', protocol)
    print('FROZEN: both r24 arms; factors 1,2,4; original denominators and unchanged root budget', flush=True)


def worker(output):
    p = read(output / 'protocol.json')
    for name, digest in {**p['inputs'], **p['sources']}.items():
        if sha(name) != digest:
            raise ValueError('Frozen identity changed: ' + name)
    previous = read(Path(p['previous']) / 'results.json')
    results = []
    for arm, fields in p['fields'].items():
        forward, valid, shape, affine, digest = load(Path(fields['forward']))
        stored, stored_valid, stored_shape, stored_affine, _ = load(Path(fields['inverse']))
        if shape != stored_shape or not np.array_equal(affine, stored_affine):
            raise ValueError('Unexpected stored inverse grid')
        for factor in p['factors']:
            inverse_shape, inverse_affine = refined_grid(shape, affine, factor)
            roots, available, report = solve_lattice(forward, valid, shape, affine, stored, stored_valid,
                                                     inverse_shape, inverse_affine, p['method'], p['chunk_size'])
            errors = composition_errors(forward, roots, valid, available, shape, affine, p['interior_margin'],
                                        inverse_shape, inverse_affine)
            if any(e['all']['eligible'] != int(np.prod(shape)) for e in errors.values()):
                raise ValueError('Original query denominator changed')
            if factor == 1:
                old = next(a for a in previous['arms'] if a['arm'] == arm)['variants']['same-inverse-grid']
                for direction, e in errors.items():
                    expected = old['composition_errors'][direction]['all']
                    if e['all']['evaluated'] != expected['evaluated'] or \
                            abs(e['all']['maximum_mm'] - expected['maximum_mm']) > p['baseline_tolerance_mm']:
                        raise ValueError('Same-grid root baseline did not reproduce')
            cells = inverse_cells(roots, available, inverse_shape, inverse_affine)
            directory = output / (arm + '-factor' + str(factor))
            directory.mkdir()
            np.save(directory / 'inverse.npy', roots, allow_pickle=False)
            np.save(directory / 'valid.npy', available, allow_pickle=False)
            row = dict(arm=arm, factor=factor, forward_sha256=digest, original_shape=shape,
                       inverse_shape=inverse_shape, inverse_affine=inverse_affine.tolist(),
                       original_evaluation_nodes=int(np.prod(shape)), root_report=report,
                       composition_errors=errors, inverse_cells=cells,
                       forward_query_partition=query_partition(forward, roots, available, shape, affine,
                                                               inverse_shape, inverse_affine),
                       payload_sha256={str(x.relative_to(output)): sha(x) for x in directory.glob('*.npy')},
                       passes_declared_diagnostic=bool(report['all_converged'] and report['nonpositive_root_jacobians'] == 0 and
                           all(e['all']['passes_02mm'] for e in errors.values()) and cells['pass_criteria']))
            write(directory / 'result.json', row)
            results.append(row)
            print(arm, 'factor', factor, 'unresolved', report['unresolved'], 'cycles',
                  [e['all']['maximum_mm'] for e in errors.values()], 'cells', cells['pass_criteria'],
                  'pass', row['passes_declared_diagnostic'], flush=True)
    for name, digest in {**p['inputs'], **p['sources']}.items():
        if sha(name) != digest:
            raise ValueError('Frozen identity changed during run: ' + name)
    write(output / 'results.json', dict(protocol_sha256=sha(output / 'protocol.json'), variants=results,
          all_six_variants_complete=len(results) == 6, source_and_inputs_unchanged=True,
          qualification_claim=False, runtime_comparison_allowed=False, production_source_changed=False))


def run(output):
    if (output / 'launch.json').exists():
        raise FileExistsError('Study already launched; preserve the original attempt')
    p = read(output / 'protocol.json')
    write(output / 'launch.json', dict(started_at=datetime.now(timezone.utc).isoformat(),
          protocol_sha256=sha(output / 'protocol.json')))
    start = time.monotonic()
    failure = None
    try:
        with (output / 'execution.log').open('x') as log:
            result = subprocess.run([sys.executable, str(Path(__file__).resolve()), 'worker', '--output', str(output)],
                                    stdout=log, stderr=subprocess.STDOUT, timeout=p['maximum_worker_seconds'])
        code = result.returncode
    except subprocess.TimeoutExpired:
        code, failure = 124, 'Declared 1800-second worker deadline exceeded; incomplete variants remain explicit.'
    write(output / 'completion.json', dict(ended_at=datetime.now(timezone.utc).isoformat(), exit_code=code,
          failure=failure, diagnostic_elapsed_seconds=time.monotonic()-start, execution_log_sha256=sha(output / 'execution.log'),
          completed_variants=len(list(output.glob('*-factor*/result.json'))),
          qualification_claim=False, runtime_comparison_allowed=False))
    if code:
        raise SystemExit(code)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['freeze', 'run', 'worker'])
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--previous', type=Path)
    parser.add_argument('--checks', type=Path)
    args = parser.parse_args()
    if args.command == 'freeze':
        if args.previous is None or args.checks is None:
            parser.error('freeze requires --previous and --checks')
        freeze(args.output.resolve(), args.previous.resolve(), args.checks.resolve())
    elif args.command == 'worker':
        worker(args.output.resolve())
    else:
        run(args.output.resolve())
