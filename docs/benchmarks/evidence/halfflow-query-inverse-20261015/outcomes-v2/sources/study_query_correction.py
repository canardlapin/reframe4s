#!/usr/bin/env python3
"""Freeze, run and archive compact query inversion versus rasterized export."""
import argparse
from datetime import datetime, timezone
import gzip
import os
from pathlib import Path
import resource
import shutil
import subprocess
import sys
import time

import numpy as np
import scipy
from scipy.interpolate import RegularGridInterpolator
from analyze_c04 import load, read, sha, write
from field_oracle import composition_errors, sample, world_nodes
from inverse_refinement import inverse_cells
from query_correction import evaluate

ROOT = Path(__file__).resolve().parents[3]


def identities(protocol):
    for name, digest in {**protocol['sources'], **protocol['inputs']}.items():
        if sha(name) != digest:
            raise ValueError('Frozen identity changed: ' + name)


def independent_forward(values, shape, affine, points):
    voxel = (points - affine[:3, 3]) @ np.linalg.inv(affine[:3, :3]).T
    # Match the authoritative sampler's pre-existing voxel-knot convention.
    # The independent multilinear evaluation remains SciPy's implementation.
    nearest = np.rint(voxel)
    voxel = np.where(np.abs(voxel-nearest) <= 1e-10, nearest, voxel)
    axes = tuple(np.arange(n, dtype=float) for n in reversed(shape))
    function = RegularGridInterpolator(axes, values.reshape(*reversed(shape), 3),
                                       method='linear', bounds_error=False, fill_value=None)
    return function(voxel[:, ::-1])


def freeze(study, audit_path, previous, checks, failed):
    if (study / 'protocol.json').exists():
        raise FileExistsError('Fresh declaration required')
    audit = read(audit_path)
    capture = Path(read(audit_path.parent / 'protocol.json')['capture'])
    receipt = read(capture / 'receipt.json')
    rows = [r for r in audit['rows'] if r['task']['kind'] == 'forward']
    if len(audit['rows']) != 48 or len(rows) != 24 or not all(r['cell_diagnostics']['pass_criteria'] for r in rows):
        raise ValueError('All 24 admitted corrected forward fields and complete 48-field audit required')
    if not read(capture / 'protocol.json')['production_cell_admission_changed'] or \
            receipt['exit_code'] != 0 or not receipt['source_and_inputs_unchanged']:
        raise ValueError('Completed immutable corrected capture required')
    if 'Ran 22 tests' not in checks.read_text() or '\nOK\n' not in checks.read_text():
        raise ValueError('Passing 22-test analytic/oracle suite required')
    inputs = [audit_path, audit_path.parent / 'protocol.json', capture / 'protocol.json',
              capture / 'receipt.json', previous / 'protocol.json', previous / 'results.json',
              previous / 'completion.json', checks, checks.with_name('checks-initial.log')]
    prior_failure = read(failed / 'completion.json')
    prior_protocol = read(failed / 'protocol.json')
    if prior_failure['exit_code'] != 1 or prior_failure['completed_variants'] != 0:
        raise ValueError('Expected the preserved zero-result validation failure')
    if prior_protocol['sources'][str(Path(__file__).with_name('query_correction.py').resolve())] != \
            sha(Path(__file__).with_name('query_correction.py')):
        raise ValueError('Primary inverse evaluator must remain byte-identical')
    inputs += [failed / n for n in ['protocol.json', 'completion.json', 'interpolation-validation-diagnosis.json']]
    fields = []
    for row in rows:
        task = row['task']
        forward = Path(task['stem'])
        inverse = forward.with_name(task['arm'] + '-inverse')
        if load(forward)[4] != task['central_difference']['field_sha256']:
            raise ValueError('Admitted field identity changed')
        fields.append(dict(case=task['case'], lane=task['lane'], arm=task['arm'],
                           forward=str(forward), inverse=str(inverse),
                           forward_sha256=task['central_difference']['field_sha256']))
        for stem in [forward, inverse]:
            for suffix in ['.json', '.f64', '.valid-u8']:
                path = stem.with_suffix(suffix)
                if sha(path) != receipt['field_artifact_sha256'][str(path.relative_to(capture))]:
                    raise ValueError('Captured field payload changed')
                inputs.append(path)
    sources = [Path(__file__).resolve()] + [Path(__file__).with_name(n).resolve() for n in
              ['query_correction.py', 'test_query_correction.py', 'field_oracle.py', 'analyze_c04.py',
               'inverse_refinement.py', 'test_field_oracle.py', 'test_inverse_refinement.py',
               'test_representation.py', 'representation.py', 'archive_query_attempt.py']]
    sources += [ROOT / 'benchmarks/flashalign/real_mri_cell_topology.py',
                ROOT / 'benchmarks/flashalign/real_image_court.py']
    protocol = dict(schema='hfq-compact-query-inverse-v1', created_at=datetime.now(timezone.utc).isoformat(),
                    fields=fields, correction_budgets=[0, 1, 2, 4, 8],
                    method=dict(tolerance=1e-8, step_cap=4., backtracks=12),
                    inputs={str(p): sha(p) for p in inputs}, sources={str(p): sha(p) for p in sources},
                    environment=dict(python=sys.version, numpy=np.__version__, scipy=scipy.__version__,
                        numerical_threads={k: os.environ.get(k) for k in
                            ['OPENBLAS_NUM_THREADS', 'OMP_NUM_THREADS', 'MKL_NUM_THREADS', 'VECLIB_MAXIMUM_THREADS']}),
                    maximum_worker_seconds=1800, maximum_error_mm=.2, interior_margin=6,
                    expected_field_variants=120, baseline_tolerance_mm=1e-10,
                    query_sets='All original nodes y and all forward(original nodes) per field. Every composition keeps all original nodes including boundary.',
                    query_diagnostic='Both complete direct compositions <=.2mm; zero unsupported queries; positive forward Jacobians at evaluated preimages; materialized inverse complete-cell criteria pass.',
                    root_convergence='Also report separately whether every query reaches1e-8mm. Iteration-cap outputs remain nonconverged; no tolerance relaxation.',
                    raster_diagnostic='Re-sample the corrected inverse on the original lattice and independently require both full-field raster compositions<=.2mm and unchanged all-cell criteria. Direct query accuracy cannot substitute for export.',
                    topology='All materialized inverse complete cells, eight corners and center, floor .05 and coverage .99. Root-local positive Jacobians do not certify the continuous query evaluator.',
                    counters='Report seed samples, all forward samples, derivative samples, linear solves and backtracking; include final residual/Jacobian validation. Counts are diagnostic work, not production timings.',
                    storage='Keep original forward and original inverse only; diagnostic query arrays are saved for verification but are not extra evaluator coefficients.',
                    independent_confirmation='SciPy multilinear forward sampling at every corrected preimage checks both equation residuals within1e-10mm; zero-budget raster baseline must match original stored inverse cycles.',
                    validation_correction=dict(prior_failed_attempt=str(failed),
                        change='Match the primary sampler pre-existing1e-10-voxel knot snap before independent SciPy evaluation.',
                        primary_evaluator_byte_identical=True, measurement_tolerance_unchanged=True),
                    scope='All six observed synthetic cases, both C4/C5 lanes, both arms. No optimization, production-source, endpoint export, MRI or qualification change.',
                    qualification_claim=False, runtime_comparison_allowed=False)
    for key in ['fields', 'correction_budgets', 'method', 'maximum_error_mm', 'expected_field_variants']:
        if protocol[key] != prior_protocol[key]:
            raise ValueError('Validation correction changed primary design: ' + key)
    write(study / 'protocol.json', protocol)
    print('FROZEN 24 fields x5 budgets; query and raster gates separate; unchanged0.2mm', flush=True)


def cycle_stats(coords, supported, nodes, shape, margin):
    errors = np.linalg.norm(coords - nodes, axis=1)
    valid = supported & np.isfinite(errors)
    z, y, x = np.indices(tuple(reversed(shape)))
    inside = ((x >= margin) & (x < shape[0]-margin) & (y >= margin) &
              (y < shape[1]-margin) & (z >= margin) & (z < shape[2]-margin)).ravel()
    result = {}
    for name, region in [('all', np.ones(len(nodes), dtype=bool)), ('interior', inside), ('boundary', ~inside)]:
        usable = region & valid
        maximum = float(errors[usable].max()) if usable.any() else None
        result[name] = dict(eligible=int(region.sum()), evaluated=int(usable.sum()),
                            invalid=int((region & ~valid).sum()), maximum_mm=maximum,
                            passes_02mm=bool(region.any() and np.all(valid[region]) and maximum <= .2))
    return result


def worker(study):
    p = read(study / 'protocol.json')
    identities(p)
    outcomes = []
    for number, field in enumerate(p['fields']):
        forward, valid, shape, affine, digest = load(Path(field['forward']))
        inverse, inverse_valid, inverse_shape, inverse_affine, _ = load(Path(field['inverse']))
        if shape != inverse_shape or not np.array_equal(affine, inverse_affine) or digest != field['forward_sha256']:
            raise ValueError('Unexpected field identity/lattice')
        nodes = world_nodes(shape, affine)
        baseline = composition_errors(forward, inverse, valid, inverse_valid, shape, affine, p['interior_margin'])
        for budget in p['correction_budgets']:
            original, original_valid, original_report = evaluate(forward, valid, inverse, inverse_valid,
                                                                  shape, affine, nodes, budget, **p['method'])
            at_forward, at_forward_valid, forward_report = evaluate(forward, valid, inverse, inverse_valid,
                                                                     shape, affine, forward, budget, **p['method'])
            f_g, f_g_valid, _ = sample(forward, valid, shape, affine, original)
            direct = dict(forward_then_inverse=cycle_stats(at_forward, valid & at_forward_valid, nodes, shape, p['interior_margin']),
                          inverse_then_forward=cycle_stats(f_g, original_valid & f_g_valid, nodes, shape, p['interior_margin']))
            checks = {}
            for name, corrected, targets in [('original_queries', original, nodes), ('forward_queries', at_forward, forward)]:
                scipy_values = independent_forward(forward, shape, affine, corrected)
                primary_values, _, _ = sample(forward, valid, shape, affine, corrected)
                difference = float(np.linalg.norm(scipy_values - primary_values, axis=1).max())
                if difference > 1e-10:
                    raise ValueError('Independent forward evaluator differs')
                checks[name] = dict(maximum_vector_difference_mm=difference,
                                    maximum_equation_residual_mm=float(np.linalg.norm(scipy_values-targets, axis=1).max()))
            raster = composition_errors(forward, original, valid, original_valid, shape, affine, p['interior_margin'])
            cells = inverse_cells(original, original_valid, shape, affine)
            if budget == 0:
                for direction in baseline:
                    if abs(raster[direction]['all']['maximum_mm'] - baseline[direction]['all']['maximum_mm']) > p['baseline_tolerance_mm']:
                        raise ValueError('Zero-budget stored-inverse baseline changed')
            if any(r['all']['eligible'] != len(nodes) for r in [*direct.values(), *raster.values()]):
                raise ValueError('Composition denominator changed')
            directory = study / f'field-{number:02d}-budget{budget}'
            directory.mkdir()
            np.save(directory / 'original.npy', original, allow_pickle=False)
            np.save(directory / 'forward.npy', at_forward, allow_pickle=False)
            query_pass = bool(all(r['all']['passes_02mm'] for r in direct.values()) and
                              original_report['unsupported'] == 0 and forward_report['unsupported'] == 0 and
                              original_report['nonpositive_root_jacobians'] == 0 and
                              forward_report['nonpositive_root_jacobians'] == 0 and cells['pass_criteria'])
            row = dict(field=field, budget=budget, original_nodes=len(nodes), direct_compositions=direct,
                       raster_compositions=raster, materialized_inverse_cells=cells,
                       query_reports=dict(original=original_report, forward=forward_report),
                       independent_forward_checks=checks, original_inverse_baseline=baseline,
                       query_diagnostic_pass=query_pass,
                       all_queries_root_converged=original_report['all_converged'] and forward_report['all_converged'],
                       raster_diagnostic_pass=bool(all(r['all']['passes_02mm'] for r in raster.values()) and cells['pass_criteria']),
                       persistent_inverse_nodes=len(inverse), persistent_forward_nodes=len(forward),
                       maximum_change_from_stored_inverse_nodes_mm=float(np.linalg.norm(original-inverse, axis=1).max()),
                       payloads=[dict(path=str(f.relative_to(study)), sha256=sha(f), bytes=f.stat().st_size)
                                 for f in directory.glob('*.npy')])
            write(study / f'field-{number:02d}-budget{budget}.json', row)
            outcomes.append(row)
            print(field['case'], field['lane'], field['arm'], budget,
                  'direct', [r['all']['maximum_mm'] for r in direct.values()],
                  'raster', [r['all']['maximum_mm'] for r in raster.values()],
                  'query-pass', query_pass, 'raster-pass', row['raster_diagnostic_pass'], flush=True)
    identities(p)
    write(study / 'results.json', dict(protocol_sha256=sha(study / 'protocol.json'), variants=outcomes,
          all_declared_variants_complete=len(outcomes) == p['expected_field_variants'], source_and_inputs_unchanged=True,
          worker_peak_rss=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
          worker_peak_rss_units='bytes' if sys.platform == 'darwin' else 'KiB',
          qualification_claim=False, runtime_comparison_allowed=False, production_source_changed=False))


def run(study):
    if (study / 'launch.json').exists():
        raise FileExistsError('Preserve existing attempts')
    p = read(study / 'protocol.json')
    identities(p)
    write(study / 'launch.json', dict(at=datetime.now(timezone.utc).isoformat(), protocol_sha256=sha(study / 'protocol.json')))
    start = time.monotonic()
    failure = None
    try:
        with (study / 'execution.log').open('x') as log:
            result = subprocess.run([sys.executable, str(Path(__file__).resolve()), 'worker', '--study', str(study)],
                                    stdout=log, stderr=subprocess.STDOUT, timeout=p['maximum_worker_seconds'])
        code = result.returncode
    except subprocess.TimeoutExpired:
        code, failure = 124, 'Declared 1800-second deadline exceeded; all incomplete variants retained.'
    write(study / 'completion.json', dict(at=datetime.now(timezone.utc).isoformat(), exit_code=code, failure=failure,
          diagnostic_elapsed_seconds=time.monotonic()-start, execution_log_sha256=sha(study / 'execution.log'),
          completed_variants=len(list(study.glob('field-*-budget*.json'))), runtime_comparison_allowed=False))
    if code:
        raise SystemExit(code)


def archive(study, output, phase):
    if output.exists():
        raise FileExistsError(output)
    p = read(study / 'protocol.json')
    identities(p)
    if phase == 'outcomes' and not (study / 'completion.json').exists():
        raise ValueError('Terminal attempt required')
    files = {study / 'protocol.json': 'protocol.json'}
    payloads = []
    for path in p['sources']:
        files[Path(path)] = 'sources/' + Path(path).name
    for i, path in enumerate(sorted(p['inputs'])):
        source = Path(path)
        if source.suffix in ['.json', '.log']:
            files[source] = f'inputs/{i:03d}-' + source.name
        else:
            payloads.append(dict(path=path, sha256=p['inputs'][path], bytes=source.stat().st_size, retained_locally=True))
    if phase == 'outcomes':
        files.update({path: path.name for path in study.glob('*.json') if path.name != 'preexisting-files.json'})
        files[study / 'execution.log'] = 'execution.log'
        for path in study.glob('field-*-budget*/*.npy'):
            payloads.append(dict(path=str(path), sha256=sha(path), bytes=path.stat().st_size, retained_locally=True))
    output.mkdir(parents=True)
    inventory = []
    for source, name in sorted(files.items()):
        target = output / name
        if source.suffix == '.log':
            target = target.with_name(target.name + '.gz')
        target.parent.mkdir(parents=True, exist_ok=True)
        if source.suffix == '.log':
            with target.open('xb') as stream, gzip.GzipFile(fileobj=stream, filename='', mtime=0, mode='wb') as compressed:
                compressed.write(source.read_bytes())
            if gzip.decompress(target.read_bytes()) != source.read_bytes():
                raise ValueError('Compressed evidence differs')
        else:
            shutil.copyfile(source, target)
            if sha(source) != sha(target):
                raise ValueError('Archived bytes differ')
        inventory.append(dict(path=str(target.relative_to(output)), sha256=sha(target), original_sha256=sha(source)))
    write(output / 'artifact-manifest.json', dict(schema='hfq-query-inverse-archive-v1', phase=phase,
          protocol_sha256=sha(study / 'protocol.json'), files=inventory, local_payloads=payloads,
          qualification_claim=False, runtime_comparison_allowed=False, mri_or_dense_map_payloads=False))
    print('ARCHIVED', phase, len(inventory), 'compact files;', len(payloads), 'local payloads')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['freeze', 'run', 'worker', 'archive'])
    parser.add_argument('--study', type=Path, required=True)
    parser.add_argument('--audit', type=Path)
    parser.add_argument('--previous', type=Path)
    parser.add_argument('--checks', type=Path)
    parser.add_argument('--failed', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--phase', choices=['declaration', 'outcomes'])
    args = parser.parse_args()
    study = args.study.resolve()
    if args.command == 'freeze':
        if None in [args.audit, args.previous, args.checks, args.failed]:
            parser.error('freeze requires --audit, --previous, --checks and --failed')
        freeze(study, args.audit.resolve(), args.previous.resolve(), args.checks.resolve(), args.failed.resolve())
    elif args.command == 'archive':
        if args.output is None or args.phase is None:
            parser.error('archive requires --output and --phase')
        archive(study, args.output.resolve(), args.phase)
    elif args.command == 'worker':
        worker(study)
    else:
        run(study)
