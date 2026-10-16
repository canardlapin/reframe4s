#!/usr/bin/env python3
"""Prospective admission and application audit of two saved acquired-MRI maps."""
import argparse
from datetime import datetime, timezone
from pathlib import Path
import shutil
import subprocess
import sys
import time
import tempfile

import nibabel as nib
import numpy as np
from scipy import ndimage
from nibabel.processing import resample_from_to
from field_oracle import sample, world_nodes
from query_correction import evaluate
from query_bundle import sha, read, write, archive, bundle_receipt

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT/'benchmarks/flashalign'))
from run_real_mri_recovery import freeze as freeze_engine, verify as verify_engine
from real_image_court import label_scores, sample as image_sample, ncc

DIRECTIONS = [('FixedToMoving','fixed','moving','fixedToMoving','fixed-to-moving'),
              ('MovingToFixed','moving','fixed','movingToFixed','moving-to-fixed')]


def freeze(output, classpath, checks):
    if output.exists():
        raise FileExistsError(output)
    log = (checks/'runner.log').read_text()
    if '[error]' in log or 'Passed: Total 2, Failed 0, Errors 0, Passed 2' not in log:
        raise ValueError('Two passing application tests and runner compilation required')
    if 'SELF CHECKS PASSED: scalar support and independent affine chain' not in (checks/'python.log').read_text():
        raise ValueError('Independent Python checks required')
    if '66 canonical owners verified' not in (checks/'ownership.log').read_text():
        raise ValueError('Pinned provider ownership check required')
    old = ROOT/'benchmarks/flashalign/local-results/hfq-query-bundle-20261016/study-v2'
    prior = read(old/'protocol.json')
    panel = ROOT/'benchmarks/flashalign/local-results/hfq-diagnostics-20261014/offline-panel-oasis13-mmrr11-v1/results.json'
    cases_path = ROOT/'benchmarks/flashalign/local-results/hfq-diagnostics-20261014/trajectories-deferred-v2/engine/protocol.json'
    cases = {c['id']:c for c in read(cases_path)['cases']}
    output.mkdir(parents=True)
    inputs = dict(prior['inputs'])
    for path in [old/'protocol.json',old/'receipt.json',old/'completion.json',panel,cases_path]:
        inputs[str(path)] = sha(path)
    if read(old/'completion.json')['exit_code'] != 0:
        raise ValueError('Prior saved bundle audit must have completed')
    tasks = []
    for original in prior['tasks']:
        case = original['id']
        c = cases[case]
        rows = [r for r in read(panel)['rows'] if r['task']['pair']==case and r['task']['policy']=='selected-control']
        row = max(rows,key=lambda r:r['task']['event'])
        if row['task']['event'] != original['event'] or row['task']['checkpoint'] != original['checkpoint']:
            raise ValueError('Terminal checkpoint selection changed')
        bundle = old/case/'transform.hfqb'
        receipt = bundle_receipt(bundle,original)
        labels = {arm:str(Path(c[arm+'_labels'])) for arm in ['fixed','moving']}
        labelset = [int(v) for v in sorted(set(np.unique(nib.load(labels['fixed']).get_fdata()).astype(int)) &
                          set(np.unique(nib.load(labels['moving']).get_fdata()).astype(int)) - {0})]
        if not labelset:
            raise ValueError('Nonempty original common labelset required')
        dense = Path(row['command'][-1])
        for path in [bundle,Path(c['saved_affine']),Path(c['baseline_report'])] + [Path(v) for v in labels.values()] + \
                [dense/('halfflow-'+d+suffix) for d in ['fixed-to-moving','moving-to-fixed'] for suffix in ['.json','.f64','.valid-u8']]:
            inputs[str(path)] = sha(path)
        counts = {arm:int(np.prod(2*np.array(read(original['grids'][arm])['shape'])-1)) for arm in ['fixed','moving']}
        if any(n>20000000 for n in counts.values()):
            raise ValueError('Default complete-lattice budget exceeded')
        tasks.append(dict(original,bundle=str(bundle),bundle_receipt=receipt,data=c['data'],labels=labels,labelset=labelset,
                          affine=c['saved_affine'],baseline_report=c['baseline_report'],dense=str(dense),half_voxel_counts=counts))
    sources = [Path(__file__).resolve()] + [Path(__file__).with_name(n).resolve() for n in
              ['field_oracle.py','query_correction.py','query_bundle.py']]
    sources += [ROOT/'benchmarks/flashalign/real_image_court.py']
    p = dict(schema='hfq-functional-mri-study-v1',created_at=datetime.now(timezone.utc).isoformat(),cases=[],tasks=tasks,
             inputs=inputs,sources={str(p):sha(p) for p in sources},checks={str(p.resolve()):sha(p) for p in checks.glob('*.log')},
             maximum_cycle_mm=.2,maximum_iterations=8,root_tolerance_mm=1e-8,maximum_step_mm=4.,maximum_backtracks=12,
             coordinate_tolerance_mm=1e-7,jacobian_tolerance=1e-7,intensity_absolute_tolerance=1e-9,
             chunk_size=32768,java_heap='6G',java_active_processors=1,timeout_seconds_per_task=2400,maximum_worker_seconds=14400,
             selection='The same two terminal selected-control LevelEnd checkpoints previously frozen in saved-bundle study-v2; no fitting, tuning, checkpoint replacement or denominator removal.',
             admission='Full unmasked half-voxel endpoint lattices in both directions. Every bounded root and reverse root succeeds, both full-chain endpoint branch Jacobians finite >0, cycle <=0.2mm. Default sample budget unchanged.',
             application='Only admitted bundles: directly evaluate every original native endpoint node through the checked API. Independent full half-grid oracle covers these nodes; compare saved application values/status to identical half-grid locations. Diagnostic nodal coordinates are not admitted dense maps.',
             intensity='Independent scipy order1 interpolation in original endpoint image; snap within1e-10 voxel before inclusive [0,n-1] support test. Outside=0. Report transform-valid and image-supported denominators separately. All intensity voxels, including outside values, compared.',
             anatomy='Evaluation-only original labels; fixed common nonzero original labelset per pair frozen above. Target labels resampled nearest to endpoint lattice; source labels pulled nearest at direct nodal coordinates. Zero invalid/outside samples, retain all target voxels and all frozen labels. Macro Dice and symmetric physical HD95 use existing court. Freshly score same-checkpoint historical dense map and saved affine on the identical lattice/labelset in both directions. No anatomy acceptance threshold or selection.',
             qualification_claim=False,dense_export_admitted=False,runtime_comparison_allowed=False,
             limits='Two previously studied development pairs. Numerical export validity is separate from anatomy and convergence. Branch Jacobians and sampled admission are not continuous/global certificates. No new held-out data or optimizer improvement claim.')
    write(output/'protocol.json',p)
    freeze_engine(output/'engine',output/'protocol.json',classpath)
    for path in sources:
        target = output/'source'/path.name
        target.parent.mkdir(exist_ok=True)
        shutil.copyfile(path,target)
    print('FROZEN',sum(sum(t['half_voxel_counts'].values()) for t in tasks),'half-voxel points; two original MRI bundles, no fits',flush=True)


def verify(output):
    p, source = verify_engine(output/'engine')
    for group in ['inputs','sources','checks']:
        for name,digest in p[group].items():
            if sha(name)!=digest:
                raise ValueError('Frozen identity changed: '+name)
    return p,source


def transformer(task):
    cp = Path(task['checkpoint'])
    m = read(cp/'midpoint-fixed-residual.json')
    shape = m['shape']
    affine = np.array(m['index_to_ras_mm']).reshape(4,4)
    count = int(np.prod(shape))
    identity = world_nodes(shape,affine)
    fields = {arm:(np.fromfile(cp/('midpoint-'+arm+'-residual.f64'),dtype='>f8').reshape(count,3).astype(float),
                   np.fromfile(cp/('midpoint-'+arm+'-residual.valid-u8'),dtype='u1').astype(bool)) for arm in ['fixed','moving']}
    operators = {arm:np.array(read(cp/('midpoint-'+arm+'-affine.json'))).reshape(4,4) for arm in fields}
    inverses = {arm:np.linalg.inv(a) for arm,a in operators.items()}
    def transform(source,target,query):
        inverse = inverses[source]
        target_work = query@inverse[:3,:3].T+inverse[:3,3]
        f,valid = fields[source]
        roots,supported,_ = evaluate(f,valid,identity,np.ones(count,dtype=bool),shape,affine,target_work,8)
        root_values,root_valid,js = sample(f,valid,shape,affine,roots,True)
        ds = np.linalg.det(js)
        result,available,jd = sample(*fields[target],shape,affine,roots,True)
        det = np.linalg.det(operators[target][:3,:3])*np.linalg.det(jd)*np.linalg.det(inverse[:3,:3])/ds
        ok = supported & root_valid & (np.linalg.norm(root_values-target_work,axis=1)<=1e-8) & (ds>0) & available & np.isfinite(det) & (det>0)
        return result@operators[target][:3,:3].T+operators[target][:3,3],ok,det
    return transform


def admission_oracle(task,directory,p):
    transform = transformer(task)
    native = read(directory/'result.json')
    rows = []
    for key,source,target,report_key,_ in DIRECTIONS:
        grid = read(task['grids'][source]); dims = 2*np.array(grid['shape'])-1
        basis = np.array(grid['affine']).reshape(4,4); count = int(np.prod(dims))
        status = np.memmap(directory/key/'status.u8',dtype='u1',mode='r',shape=(count,))
        values = np.memmap(directory/key/'samples.f64',dtype='>f8',mode='r',shape=(count,9))
        successful = boundary_count = boundary_success = mismatches = 0
        max_coordinates = max_jacobian = max_cycle = 0.; min_jacobian = float('inf')
        for start in range(0,count,p['chunk_size']):
            end = min(count,start+p['chunk_size']); index = np.arange(start,end)
            voxel = np.column_stack([index%dims[0],index//dims[0]%dims[1],index//(dims[0]*dims[1])])
            boundary = np.any((voxel==0)|(voxel==dims-1),axis=1)
            query = (voxel*.5)@basis[:3,:3].T+basis[:3,3]
            first,ok,jf = transform(source,target,query)
            back = np.full_like(query,np.nan); reverse_ok = np.zeros(len(query),dtype=bool); jb = np.full(len(query),np.nan)
            if ok.any():
                back[ok],reverse_ok[ok],jb[ok] = transform(target,source,first[ok])
            cycle = np.linalg.norm(back-query,axis=1)
            success = ok & reverse_ok & np.isfinite(cycle) & (cycle<=p['maximum_cycle_mm'])
            native_ok = status[start:end]!=0
            mismatches += int(np.count_nonzero(success!=native_ok)); successful += int(success.sum())
            boundary_count += int(boundary.sum()); boundary_success += int((boundary&success).sum())
            if success.any():
                min_jacobian = min(min_jacobian,float(np.minimum(jf[success],jb[success]).min()))
                max_cycle = max(max_cycle,float(cycle[success].max()))
            common = success & native_ok
            if common.any():
                expected = np.column_stack([first,back,jf,jb,cycle]); delta = np.abs(expected[common]-values[start:end][common])
                max_coordinates = max(max_coordinates,float(np.linalg.norm(delta[:,:3],axis=1).max()),float(np.linalg.norm(delta[:,3:6],axis=1).max()))
                max_jacobian = max(max_jacobian,float(delta[:,6:8].max()))
        original = native['report'][report_key]
        counts_match = original['eligible']==count and original['evaluated']==count and original['successful']==successful and \
            original['boundaryEligible']==boundary_count and original['boundarySuccessful']==boundary_success
        row = dict(direction=key,eligible=count,successful=successful,boundary_eligible=boundary_count,boundary_success=boundary_success,
                   status_mismatches=mismatches,counts_match=counts_match,maximum_coordinate_difference_mm=max_coordinates,
                   maximum_jacobian_difference=max_jacobian,minimum_successful_jacobian=min_jacobian if successful else None,
                   maximum_successful_cycle_mm=max_cycle if successful else None,
                   parity_pass=counts_match and mismatches==0 and max_coordinates<=p['coordinate_tolerance_mm'] and max_jacobian<=p['jacobian_tolerance'],admission_pass=successful==count)
        write(directory/key/'oracle.json',row); rows.append(row)
        print(task['id'],key,'oracle',row,flush=True)
    expected = all(r['admission_pass'] for r in rows)
    return dict(rows=rows,functional_admitted=expected,admission_parity=expected==native['functional_admitted'],all_parity_pass=all(r['parity_pass'] for r in rows))


def scalar_sample(image,world):
    vox = nib.affines.apply_affine(np.linalg.inv(image.affine),world)
    nearest = np.rint(vox); vox = np.where(np.abs(vox-nearest)<=1e-10,nearest,vox)
    good = np.isfinite(vox).all(-1)&(vox>=0).all(-1)&(vox<=np.array(image.shape)-1).all(-1)
    values = ndimage.map_coordinates(image.get_fdata(),vox.T,order=1,mode='constant',cval=0,prefilter=False)
    values[~good] = 0.
    return values,good


def application(task,directory,p):
    native = read(directory/'result.json')
    if not native['functional_admitted']:
        return dict(executed=False,reason='Rejected admission: no application authorized',parity_pass=True)
    affine = np.array(read(task['affine'])).reshape(4,4)
    rows = []
    for key,source,target,_,dense_name in DIRECTIONS:
        dest = directory/key; grid = read(task['grids'][source]); shape = tuple(grid['shape']); dims = np.array(shape); n = int(np.prod(shape))
        basis = np.array(grid['affine']).reshape(4,4); half = 2*dims-1; hn = int(np.prod(half))
        nodes = np.memmap(dest/'nodes.f64',dtype='>f8',mode='r',shape=(n,9))
        valid = np.fromfile(dest/'nodes-status.u8',dtype='u1')!=0
        half_values = np.memmap(dest/'samples.f64',dtype='>f8',mode='r',shape=(hn,9))
        half_status = np.memmap(dest/'status.u8',dtype='u1',mode='r',shape=(hn,))
        coords = np.asarray(nodes[:,:3]); world = world_nodes(shape,basis)
        index = np.arange(n); voxel = np.column_stack([index%dims[0],index//dims[0]%dims[1],index//(dims[0]*dims[1])])
        hindex = 2*voxel[:,0]+half[0]*(2*voxel[:,1]+half[1]*2*voxel[:,2])
        status_mismatches = int(np.count_nonzero(valid!=(half_status[hindex]!=0)))
        delta = np.abs(nodes[valid]-half_values[hindex[valid]])
        max_node_delta = float(delta.max()) if valid.any() else None
        image = nib.load(str(Path(task['data'])/(target+'.nii.gz')))
        expected,supported = scalar_sample(image,coords)
        expected[~valid] = 0.; supported &= valid
        actual = np.fromfile(dest/'warped.f64',dtype='>f8'); actual_support = np.fromfile(dest/'image-status.u8',dtype='u1')!=0
        support_mismatches = int(np.count_nonzero(supported!=actual_support)); max_intensity = float(np.abs(expected-actual).max())
        target_image = nib.load(str(Path(task['data'])/(source+'.nii.gz'))).get_fdata().ravel(order='F')
        target_labels = resample_from_to(nib.load(task['labels'][source]),(shape,basis),order=0).get_fdata().astype(int)
        source_labels = nib.load(task['labels'][target])
        dense = Path(task['dense'])/('halfflow-'+dense_name)
        metadata = read(Path(str(dense)+'.json'))
        if metadata['shape']!=list(shape) or not np.array_equal(metadata['index_to_ras_mm'],basis.ravel()):
            raise ValueError('Matched dense baseline lattice changed')
        dense_coords = np.fromfile(str(dense)+'.f64',dtype='>f8').reshape(n,3)
        dense_valid = np.fromfile(str(dense)+'.valid-u8',dtype='u1')!=0
        operator = np.linalg.inv(affine) if source=='fixed' else affine
        affine_coords = world@operator[:3,:3].T+operator[:3,3]
        scores = {}
        for name,mapped,mask in [('functional',coords,valid),('same_checkpoint_dense',dense_coords,dense_valid),('saved_affine',affine_coords,np.ones(n,dtype=bool))]:
            warped,label_support = image_sample(source_labels.get_fdata(),source_labels.affine,mapped,order=0)
            warped[~mask] = 0
            scores[name] = label_scores(target_labels,warped.reshape(shape,order='F').astype(int),task['labelset'],basis)
            scores[name]['map_valid_voxels'] = int(mask.sum())
            scores[name]['source_label_supported_voxels'] = int((mask&label_support).sum())
        row = dict(direction=key,eligible=n,transform_successful=int(valid.sum()),image_supported=int(supported.sum()),
                   node_status_mismatches=status_mismatches,maximum_node_component_difference=max_node_delta,
                   image_support_mismatches=support_mismatches,maximum_intensity_difference=max_intensity,
                   supported_positive_target_ncc=ncc(target_image,actual,supported&(target_image>0)),anatomy=scores,
                   parity_pass=status_mismatches==0 and max_node_delta is not None and max_node_delta<=p['coordinate_tolerance_mm'] and
                   support_mismatches==0 and max_intensity<=p['intensity_absolute_tolerance'])
        write(dest/'independent-application.json',row); rows.append(row)
        print(task['id'],key,'application parity',row['parity_pass'],'Dice',{k:v['macro_dice'] for k,v in scores.items()},flush=True)
    return dict(executed=True,rows=rows,parity_pass=all(r['parity_pass'] for r in rows))


def check():
    values = np.indices((5,5,5))[0].astype(float)
    image = nib.Nifti1Image(values,np.eye(4))
    coords = np.array([[.5,2,3],[4,2,3],[4+5e-11,2,3],[4+2e-10,2,3],[-.5,2,3]])
    actual,support = scalar_sample(image,coords)
    np.testing.assert_allclose(actual,[.5,4,4,0,0],rtol=0,atol=1e-12)
    np.testing.assert_array_equal(support,[True,True,True,False,False])
    with tempfile.TemporaryDirectory(prefix='hfq-mri-oracle-') as temp:
        cp = Path(temp); shape = [5,5,5]; basis = np.eye(4); nodes = world_nodes(shape,basis)
        write(cp/'midpoint-fixed-residual.json',dict(shape=shape,index_to_ras_mm=basis.ravel().tolist()))
        fixed = np.eye(4); fixed[0,0] = 1.1; fixed[0,3] = 2
        moving = np.eye(4); moving[0,0] = .9; moving[0,3] = -3
        for arm,operator in [('fixed',fixed),('moving',moving)]:
            nodes.astype('>f8').tofile(cp/('midpoint-'+arm+'-residual.f64'))
            np.ones(len(nodes),dtype='u1').tofile(cp/('midpoint-'+arm+'-residual.valid-u8'))
            write(cp/('midpoint-'+arm+'-affine.json'),operator.ravel().tolist())
        transform = transformer(dict(checkpoint=str(cp)))
        query = np.array([[2.,1,2],[3.1,3,4],[12.,-2,1]])
        mapped,ok,j = transform('fixed','moving',query)
        expected = query.copy(); expected[:,0] = .9*(query[:,0]-2)/1.1-3
        np.testing.assert_allclose(mapped,expected,rtol=0,atol=1e-12)
        assert ok.all()
        np.testing.assert_allclose(j,.9/1.1,rtol=0,atol=1e-12)
        back,ok,_ = transform('moving','fixed',mapped)
        assert ok.all()
        np.testing.assert_allclose(back,query,rtol=0,atol=1e-12)
    print('SELF CHECKS PASSED: scalar support and independent affine chain',flush=True)


def worker(output):
    p,source = verify(output)
    if (output/'launch.json').exists():
        raise FileExistsError('Preserve prior attempts')
    write(output/'launch.json',dict(at=datetime.now(timezone.utc).isoformat()))
    rows = []
    for task in p['tasks']:
        directory = output/task['id']; log = output/(task['id']+'.log')
        command = [shutil.which('java'),'-Xmx6G','-XX:ActiveProcessorCount=1','-cp',(output/'engine/classpath.txt').read_text().strip(),
                   'reframe4s.halfflow.RealImageFunctionalExport',task['bundle'],str(Path(task['data'])/'fixed.nii.gz'),str(Path(task['data'])/'moving.nii.gz'),str(directory)]
        with log.open('x') as stream:
            try:
                code = subprocess.run(command,stdout=stream,stderr=subprocess.STDOUT,timeout=p['timeout_seconds_per_task']).returncode
            except subprocess.TimeoutExpired:
                code = 124
        row = dict(id=task['id'],exit_code=code,log_sha256=sha(log))
        if code==0 and (directory/'result.json').exists():
            row['native'] = read(directory/'result.json')
            try:
                row['bundle'] = bundle_receipt(Path(task['bundle']),task)
                row['oracle'] = admission_oracle(task,directory,p)
                row['application'] = application(task,directory,p)
            except Exception as error:
                row['verification_error'] = type(error).__name__+': '+str(error)
        row['validation_pass'] = code==0 and 'oracle' in row and 'application' in row and row['oracle']['admission_parity'] and row['oracle']['all_parity_pass'] and row['application']['parity_pass']
        write(output/(task['id']+'-receipt.json'),row); rows.append(row)
        print(task['id'],'exit',code,'admitted',row.get('native',{}).get('functional_admitted'),'validation',row['validation_pass'],row.get('verification_error'),flush=True)
    verify(output)
    write(output/'receipt.json',dict(source_sha256=source['sha256'],rows=rows,all_tasks_complete=len(rows)==2,
          all_validation_pass=all(r['validation_pass'] for r in rows),qualification_claim=False,dense_export_admitted=False))
    if not all(r['validation_pass'] for r in rows):
        raise SystemExit(2)


def run(output):
    p,_ = verify(output)
    if (output/'launch.json').exists() or (output/'completion.json').exists():
        raise FileExistsError('Preserve prior attempts')
    start = time.monotonic()
    with (output/'driver.log').open('x') as stream:
        try:
            code = subprocess.run([sys.executable,str(Path(__file__).resolve()),'worker','--output',str(output)],stdout=stream,stderr=subprocess.STDOUT,timeout=p['maximum_worker_seconds']).returncode
        except subprocess.TimeoutExpired:
            code = 124
    write(output/'completion.json',dict(exit_code=code,wall_seconds=time.monotonic()-start,receipt_present=(output/'receipt.json').exists(),qualification_claim=False))
    print('COMPLETE',code,flush=True)
    if code:
        raise SystemExit(code)


if __name__=='__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('command',choices=['freeze','run','worker','archive','check'])
    parser.add_argument('--output',type=Path)
    parser.add_argument('--classpath',type=Path)
    parser.add_argument('--checks',type=Path)
    parser.add_argument('--destination',type=Path)
    parser.add_argument('--phase',choices=['declaration','outcomes'])
    args = parser.parse_args()
    if args.command=='check':
        check()
    elif args.command=='freeze':
        freeze(args.output.resolve(),args.classpath.resolve(),args.checks.resolve())
    elif args.command=='archive':
        archive(args.output.resolve(),args.destination.resolve(),args.phase)
    else:
        globals()[args.command](args.output.resolve())
