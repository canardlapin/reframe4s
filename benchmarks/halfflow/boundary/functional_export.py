#!/usr/bin/env python3
"""Frozen functional export admission on original C4 endpoint domains."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

import numpy as np
import nibabel as nib
from analyze_c04 import identify, load
from field_oracle import sample
from query_correction import evaluate
from query_bundle import sha, read, write, archive

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'benchmarks/flashalign'))
from run_real_mri_recovery import freeze as freeze_engine, verify as verify_engine
from real_mri_cell_topology import cell_diagnostics


def freeze(output, classpath, checks):
    if output.exists():
        raise FileExistsError(output)
    if (checks/'focused.log').read_text().count('Passed: Total 27, Failed 0, Errors 0, Passed 27') != 2:
        raise ValueError('27 focused tests per JVM/JS required')
    if '[error]' in (checks/'runner.log').read_text() or '[success]' not in (checks/'runner.log').read_text():
        raise ValueError('Passing runner compile required')
    if '66 canonical owners verified' not in (checks/'ownership.log').read_text():
        raise ValueError('Pinned canonical ownership check required')
    output.mkdir(parents=True)
    case_table = ROOT/'benchmarks/flashalign/fixtures/linear-automatic-release-confirmation-v5.cases.tsv'
    manifest = ROOT/'benchmarks/flashalign/manifests/flashalign-halfflow-affine-transfer-v1.json'
    runner = ROOT/'benchmarks/flashalign/runner/src/main/scala/reframe4s/benchmark/flashalign/FlashalignHalfFlowResidual.scala'
    sources = [Path(__file__).resolve()] + [Path(__file__).with_name(n) for n in
               ['query_bundle.py', 'query_correction.py', 'field_oracle.py', 'analyze_c04.py']]
    sources += [ROOT/'benchmarks/flashalign/real_mri_cell_topology.py', ROOT/'benchmarks/flashalign/real_image_court.py']
    inputs = {str(p):sha(p) for p in [case_table, manifest, runner]}
    if sha(case_table) != read(manifest)['inputs']['case_table_sha256']:
        raise ValueError('Sealed case table changed')
    cases = [r.split(chr(9)) for r in case_table.read_text().splitlines()[1:] if r.strip()]
    cases = [r for r in cases if r[2] == 'exact-core' and r[3] == 'affine']
    if len(cases) != 6:
        raise ValueError('All six sealed exact-core affine cases required')
    # Verify the exact C4 initializer and preservation path before drawing an
    # inference about historical affine factors; this is not an identity proxy.
    code = runner.read_text()
    section = code[code.index('  def runIdentity['):code.index('  def runSupplied[')]
    if '.identity(input.work, input.fixed.frame, input.moving.frame)' not in section:
        raise ValueError('Original C4 initializer changed')
    forward_source = ROOT/'modules/reframe4s-halfflow/shared/src/main/scala/reframe4s/halfflow/ForwardMidpoint.scala'
    if 'increment.thenSelfExtended(residual).flatMap(ForwardMidpointArm.make(_, affine))' not in forward_source.read_text():
        raise ValueError('Affine preservation path changed')
    tasks = []
    captures = [('corrected', ROOT/'benchmarks/flashalign/local-results/hfq-cell-fix-20261015/c04-replay-v1'),
                ('historical-control', ROOT/'benchmarks/flashalign/local-results/hfq-boundary-20261014/c04-capture-v4')]
    for cohort, capture in captures:
        receipt = read(capture/'receipt.json')
        protocol = read(capture/'protocol.json')
        if receipt['exit_code'] != 0 or not receipt['source_and_inputs_unchanged'] or protocol['current_runner_sha256'] != sha(runner):
            raise ValueError('Immutable capture/original lane source required')
        for name in ['protocol.json', 'receipt.json', 'replay/execution.log']:
            inputs[str(capture/name)] = sha(capture/name)
        for case in cases:
            directory = identify(capture, case[0], 'C4')
            grids = {}
            for arm, name, expected in [('fixed', case[5], case[7]), ('moving', case[4], case[6])]:
                path = ROOT/name
                if sha(path) != expected:
                    raise ValueError('Original endpoint image identity changed')
                image = nib.load(path)
                target = output/(cohort+'-'+case[0]+'-'+arm+'-grid.json')
                write(target, dict(shape=list(image.shape), affine=image.affine.ravel().tolist()))
                grids[arm] = str(target)
                inputs[str(path)] = sha(path)
                inputs[str(target)] = sha(target)
            for arm in ['fixed', 'moving']:
                for kind in ['forward', 'inverse']:
                    for suffix in ['.json', '.f64', '.valid-u8']:
                        path = directory/(arm+'-'+kind+suffix)
                        expected = receipt['field_artifact_sha256'][str(path.relative_to(capture))]
                        if sha(path) != expected:
                            raise ValueError('Captured field changed')
                        inputs[str(path)] = expected
            tasks.append(dict(id=cohort+'-'+case[0], cohort=cohort, case=case[0], fields=str(directory), grids=grids,
                              actual_affines='original-C4-identity-factors', identity_provenance_runner_sha256=sha(runner)))
    p = dict(schema='hfq-functional-query-admission-v1', created_at=datetime.now(timezone.utc).isoformat(), cases=[],
             tasks=tasks, inputs=inputs, sources={str(p.resolve()):sha(p) for p in sources},
             checks={str(p.resolve()):sha(p) for p in checks.glob('*.log')},
             sampling='Every point of the full half-voxel endpoint lattice: shape 2*n-1, x-fastest. Includes all original nodes, edges/faces/cell centers and complete boundary.',
             maximum_cycle_mm=.2, maximum_iterations=8, root_tolerance_mm=1e-8, maximum_step_mm=4., maximum_backtracks=12,
             minimum_endpoint_jacobian_exclusive=0., coordinate_tolerance_mm=1e-7, jacobian_tolerance=1e-7,
             chunk_size=32768, java_heap='3G', java_active_processors=1,
             timeout_seconds_per_task=180, maximum_worker_seconds=1800,
             qualification_claim=False, dense_export_admitted=False, runtime_comparison_allowed=False,
             scope='All six corrected original C4 states plus all six pre-cell-correction C4 states as controls. '
                   'Every construction/admission failure retained, no sampling masks or denominator removal. '
                   'Exact original identity affine factors are source-bound, actual original native NIfTI lattices are used. '
                   'No new optimization/refits or C5 affine attribution. Endpoint Jacobians are analytic local samples, not a continuous/global certificate. '
                   'Admitted canonical partial maps recheck both analytic endpoint determinants and the full0.2mm cycle on every call.')
    write(output/'protocol.json', p)
    freeze_engine(output/'engine', output/'protocol.json', classpath)
    for source in sources:
        target = output/'source'/source.name
        target.parent.mkdir(exist_ok=True)
        shutil.copyfile(source, target)
    print('FROZEN12 original-C4 states;6 corrected+6 historical controls, full half-voxel native lattices', flush=True)


def verify(output):
    p, source = verify_engine(output/'engine')
    for group in ['inputs', 'sources', 'checks']:
        for name, digest in p[group].items():
            if sha(name) != digest:
                raise ValueError('Frozen identity changed: '+name)
    return p, source


def oracle(task, directory, protocol):
    fields = {}
    cells = {}
    for arm in ['fixed', 'moving']:
        f, valid, shape, affine, _ = load(Path(task['fields'])/(arm+'-forward'))
        g, gv, gs, ga, _ = load(Path(task['fields'])/(arm+'-inverse'))
        if gs != shape or not np.array_equal(ga, affine):
            raise ValueError('Seed lattice changed')
        fields[arm] = (f, valid, g, gv, shape, affine)
        coords = np.stack([f[:,c].reshape(shape, order='F') for c in range(3)], axis=-1)
        cells[arm] = cell_diagnostics(coords, valid.reshape(shape, order='F'), affine)
    expected_constructed = all(c['pass_criteria'] for c in cells.values())
    native = read(directory/'result.json')
    for index, arm in enumerate(['fixed', 'moving']):
        actual = native['forward_cell_reports'][index]
        expected = cells[arm]
        if actual['evaluated'] != expected['covered_cells'] or actual['eligible'] != expected['total_declared_cells'] or \
                actual['nonPositive'] != expected['folded_cells'] or abs(actual['minimum']-expected['minimum_cell_jacobian']) > 1e-12:
            raise ValueError('Independent native/Python complete-cell geometry differs')
    if native['construction_succeeded'] != expected_constructed:
        raise ValueError('Independent forward-cell admission disagrees with native construction')
    if not expected_constructed:
        return dict(constructed=False, construction_parity=True, cells=cells, declared_samples=native['declared_samples'],
                    evaluated_samples=0, functional_admitted=False, rejected_before_query_scan=True)
    def transform(source, target, points):
        f, valid, g, gv, shape, affine = fields[source]
        roots, supported, _ = evaluate(f, valid, g, gv, shape, affine, points, 8)
        root_values, root_valid, source_jac = sample(f, valid, shape, affine, roots, True)
        source_det = np.linalg.det(source_jac)
        ok = supported & root_valid & (np.linalg.norm(root_values-points, axis=1) <= 1e-8) & (source_det > 0)
        destination, dv, _, _, ds, da = fields[target]
        result, available, destination_jac = sample(destination, dv, ds, da, roots, True)
        det = np.linalg.det(destination_jac)/source_det
        ok &= available & np.isfinite(det) & (det > 0)
        return result, ok, det
    rows = []
    for key, source, target, report_key in [('FixedToMoving','fixed','moving','fixedToMoving'),
                                           ('MovingToFixed','moving','fixed','movingToFixed')]:
        grid = read(task['grids'][source])
        dims = 2*np.array(grid['shape'])-1
        affine = np.array(grid['affine']).reshape(4,4)
        count = int(np.prod(dims))
        status = np.memmap(directory/key/'status.u8',dtype='u1',mode='r',shape=(count,))
        values = np.memmap(directory/key/'samples.f64',dtype='>f8',mode='r',shape=(count,9))
        successful = boundary_count = boundary_success = mismatches = 0
        max_coordinates = max_jacobian = max_cycle = 0.
        min_jacobian = float('inf')
        for start in range(0,count,protocol['chunk_size']):
            end = min(count,start+protocol['chunk_size'])
            indices = np.arange(start,end)
            voxel = np.column_stack([indices%dims[0],indices//dims[0]%dims[1],indices//(dims[0]*dims[1])])
            boundary = np.any((voxel==0)|(voxel==dims-1),axis=1)
            query = (voxel*.5)@affine[:3,:3].T+affine[:3,3]
            first, ok, jf = transform(source,target,query)
            back = np.full_like(query,np.nan)
            reverse_ok = np.zeros(len(query),dtype=bool)
            jb = np.full(len(query),np.nan)
            if ok.any():
                back[ok], reverse_ok[ok], jb[ok] = transform(target,source,first[ok])
            cycle = np.linalg.norm(back-query,axis=1)
            success = ok & reverse_ok & np.isfinite(cycle) & (cycle<=protocol['maximum_cycle_mm'])
            native_ok = status[start:end] != 0
            mismatches += int(np.count_nonzero(success!=native_ok))
            successful += int(success.sum())
            boundary_count += int(boundary.sum())
            boundary_success += int((boundary & success).sum())
            if success.any():
                min_jacobian = min(min_jacobian,float(np.minimum(jf[success],jb[success]).min()))
                max_cycle = max(max_cycle,float(cycle[success].max()))
            common = success & native_ok
            if common.any():
                expected = np.column_stack([first,back,jf,jb,cycle])
                delta = np.abs(expected[common]-values[start:end][common])
                max_coordinates = max(max_coordinates,float(np.max(np.linalg.norm(delta[:,:3],axis=1))),float(np.max(np.linalg.norm(delta[:,3:6],axis=1))))
                max_jacobian = max(max_jacobian,float(delta[:,6:8].max()))
        original = native['report'][report_key]
        counts_match = original['eligible']==count and original['evaluated']==count and original['successful']==successful and \
            original['boundaryEligible']==boundary_count and original['boundarySuccessful']==boundary_success
        row = dict(direction=key,eligible=count,successful=successful,boundary_eligible=boundary_count,boundary_success=boundary_success,
                   status_mismatches=mismatches,counts_match=counts_match,maximum_coordinate_difference_mm=max_coordinates,
                   maximum_jacobian_difference=max_jacobian,minimum_successful_jacobian=min_jacobian if successful else None,
                   maximum_successful_cycle_mm=max_cycle if successful else None,
                   parity_pass=counts_match and mismatches==0 and max_coordinates<=protocol['coordinate_tolerance_mm'] and max_jacobian<=protocol['jacobian_tolerance'],
                   admission_pass=successful==count)
        write(directory/key/'oracle.json',row)
        rows.append(row)
        print(task['id'],key,row,flush=True)
    expected_admitted = all(r['admission_pass'] for r in rows)
    probes = []
    for probe in native['runtime_probes']:
        source, target = ('fixed','moving') if probe['direction']=='FixedToMoving' else ('moving','fixed')
        q = np.array(probe['point']).reshape(1,3)
        first, ok, _ = transform(source,target,q)
        success = False
        difference = None
        if ok.all():
            back, back_ok, _ = transform(target,source,first)
            success = bool(back_ok.all() and np.linalg.norm(back-q,axis=1).max() <= protocol['maximum_cycle_mm'])
        if success and probe['succeeded']:
            difference = float(np.linalg.norm(first[0]-probe['coordinates']))
        probes.append(dict(direction=probe['direction'], success_agreement=success==probe['succeeded'],
                           coordinate_difference_mm=difference, parity_pass=success==probe['succeeded'] and
                           (not success or difference <= protocol['coordinate_tolerance_mm'])))
    return dict(constructed=True,construction_parity=True,cells=cells,rows=rows,functional_admitted=expected_admitted,
                admission_parity=expected_admitted==native['functional_admitted'],all_parity_pass=all(r['parity_pass'] for r in rows) and all(r['parity_pass'] for r in probes),runtime_probes=probes)


def worker(output):
    p, source = verify(output)
    if (output/'launch.json').exists():
        raise FileExistsError('Preserve prior attempts')
    write(output/'launch.json',dict(at=datetime.now(timezone.utc).isoformat()))
    rows = []
    for task in p['tasks']:
        directory = output/task['id']
        log = output/(task['id']+'.log')
        command = [shutil.which('java'),'-Xmx3G','-XX:ActiveProcessorCount=1','-cp',
                   (output/'engine/classpath.txt').read_text().strip(),'reframe4s.halfflow.FunctionalQueryExportAudit',
                   task['fields'],task['grids']['fixed'],task['grids']['moving'],str(directory)]
        with log.open('x') as stream:
            try:
                code = subprocess.run(command,stdout=stream,stderr=subprocess.STDOUT,timeout=p['timeout_seconds_per_task']).returncode
            except subprocess.TimeoutExpired:
                code = 124
        row = dict(task=task,exit_code=code,log_sha256=sha(log))
        if code == 0 and (directory/'result.json').exists():
            row['native'] = read(directory/'result.json')
            try:
                row['oracle'] = oracle(task,directory,p)
            except Exception as error:
                row['oracle_error'] = type(error).__name__+': '+str(error)
            row['payloads'] = {str(f.relative_to(directory)):sha(f) for f in directory.rglob('*') if f.is_file()}
        row['validation_pass'] = code==0 and 'oracle' in row and row['oracle']['construction_parity'] and \
            (not row['oracle']['constructed'] or (row['oracle']['admission_parity'] and row['oracle']['all_parity_pass']))
        write(output/(task['id']+'-receipt.json'),row)
        rows.append(row)
        print(task['id'],'exit',code,'admitted',row.get('native',{}).get('functional_admitted'),
              'verification',row['validation_pass'],'error',row.get('oracle_error'),flush=True)
    verify(output)
    write(output/'receipt.json',dict(source_sha256=source['sha256'],rows=rows,
          all_tasks_complete=len(rows)==12,all_validation_pass=all(r['validation_pass'] for r in rows),
          qualification_claim=False,dense_export_admitted=False))
    if not all(r['validation_pass'] for r in rows):
        raise SystemExit(2)


def run(output):
    p, _ = verify(output)
    if (output/'launch.json').exists() or (output/'completion.json').exists():
        raise FileExistsError('Preserve prior attempts')
    start = time.monotonic()
    with (output/'driver.log').open('x') as stream:
        try:
            code = subprocess.run([sys.executable,str(Path(__file__).resolve()),'worker','--output',str(output)],
                                  stdout=stream,stderr=subprocess.STDOUT,timeout=p['maximum_worker_seconds']).returncode
        except subprocess.TimeoutExpired:
            code = 124
    write(output/'completion.json',dict(exit_code=code,wall_seconds=time.monotonic()-start,
          receipt_present=(output/'receipt.json').exists(),qualification_claim=False))
    print('COMPLETE',code,flush=True)
    if code:
        raise SystemExit(code)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('command',choices=['freeze','run','worker','archive'])
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--classpath',type=Path)
    parser.add_argument('--checks',type=Path)
    parser.add_argument('--destination',type=Path)
    parser.add_argument('--phase',choices=['declaration','outcomes'])
    args = parser.parse_args()
    if args.command == 'freeze':
        freeze(args.output.resolve(),args.classpath.resolve(),args.checks.resolve())
    elif args.command == 'archive':
        archive(args.output.resolve(),args.destination.resolve(),args.phase)
    else:
        globals()[args.command](args.output.resolve())
