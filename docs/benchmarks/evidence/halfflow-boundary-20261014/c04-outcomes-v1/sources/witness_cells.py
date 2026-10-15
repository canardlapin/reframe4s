#!/usr/bin/env python3
"""Resolve knot-derivative ambiguity using declared smooth-cell witness probes."""
import argparse
from datetime import datetime,timezone
from pathlib import Path
import numpy as np
from analyze_c04 import read,write,sha,load,identify
from field_oracle import sample


def freeze(output,witness):
    if output.exists():raise FileExistsError(output)
    if not (witness/'results.json').exists():raise ValueError('Completed root witnesses required')
    output.mkdir(parents=True)
    sources=[Path(__file__).resolve(),Path(__file__).with_name('field_oracle.py').resolve(),Path(__file__).with_name('analyze_c04.py').resolve()]
    write(output/'protocol.json',dict(schema='hfq-r24-smooth-cell-witness-v1',created_at=datetime.now(timezone.utc).isoformat(),
        witness=str(witness),witness_protocol_sha256=sha(witness/'protocol.json'),witness_results_sha256=sha(witness/'results.json'),
        sources={str(s):sha(s) for s in sources},fractions_toward_cell_center=[.001,.0001],
        finite_difference_step_mm=1e-5,maximum_derivative_abs_difference=1e-7,
        selection='Every previously recorded nonpositive root Jacobian; no outcome-based subset.',
        probe_rule='Use the same1e-10 voxel snap as the sampler to select its clamped interpolation cell; move the recorded root toward that cell center by each declared fraction. Retain whether the resulting point is inside the original grid.',
        scope='Distinguish derivatives at trilinear knots from negative Jacobians in smooth cell interiors. '
            'No fit, map or inverse-solver change. Every selected witness and probe retained.',
        qualification_claim=False,runtime_comparison_allowed=False))


def run(output):
    p=read(output/'protocol.json');witness=Path(p['witness'])
    for name,digest in p['sources'].items():
        if sha(name)!=digest:raise ValueError('Cell witness source changed')
    if sha(witness/'protocol.json')!=p['witness_protocol_sha256'] or sha(witness/'results.json')!=p['witness_results_sha256']:
        raise ValueError('Root witnesses changed')
    primary=read(Path(read(witness/'protocol.json')['analysis'])/'protocol.json')
    capture=Path(primary['capture']);receipt=read(capture/'receipt.json')
    for name,digest in receipt['field_artifact_sha256'].items():
        if sha(capture/name)!=digest:raise ValueError('Frozen field changed')
    directory=identify(capture,primary['target_case'],primary['target_lane']);rows=[]
    for arm in read(witness/'results.json')['arms']:
        f,valid,shape,affine,_=load(directory/(arm['arm']+'-forward'))
        probes=[]
        for root in arm['witnesses']:
            if root['analytic_determinant']>0:continue
            voxel=np.array(root['alternate_source_voxel'])
            nearest=np.rint(voxel);snapped=np.where(np.abs(voxel-nearest)<=1e-10,nearest,voxel)
            lower=np.clip(np.floor(snapped).astype(int),0,np.array(shape)-2)
            for fraction in p['fractions_toward_cell_center']:
                point_voxel=voxel+fraction*(lower+.5-voxel)
                q=(point_voxel@affine[:3,:3].T+affine[:3,3])[None,:]
                _,available,jac=sample(f,valid,shape,affine,q,True)
                columns=[];step=p['finite_difference_step_mm']
                for axis in range(3):
                    d=np.eye(3)[axis]*step
                    plus,_,_=sample(f,valid,shape,affine,q+d)
                    minus,_,_=sample(f,valid,shape,affine,q-d)
                    columns.append((plus[0]-minus[0])/(2*step))
                finite=np.column_stack(columns);difference=float(np.max(np.abs(jac[0]-finite)))
                probes.append(dict(root_index=root['index'],fraction_toward_center=fraction,voxel=point_voxel.tolist(),
                    world_mm=q[0].tolist(),cell_lower_voxel=lower.tolist(),valid=bool(available[0]),
                    inside_original_grid=bool(np.all((point_voxel>=0)&(point_voxel<=np.array(shape)-1))),
                    analytic_jacobian=jac[0].tolist(),analytic_determinant=float(np.linalg.det(jac[0])),
                    finite_difference_jacobian=finite.tolist(),finite_difference_determinant=float(np.linalg.det(finite)),
                    derivative_maximum_abs_difference=difference,derivative_agrees=difference<=p['maximum_derivative_abs_difference']))
        rows.append(dict(arm=arm['arm'],probes=probes))
        print(arm['arm'],'probes',len(probes),'negative',sum(v['analytic_determinant']<0 for v in probes),
            'inside',sum(v['inside_original_grid'] for v in probes),'finite-difference agreement',sum(v['derivative_agrees'] for v in probes),flush=True)
    write(output/'results.json',dict(protocol_sha256=sha(output/'protocol.json'),arms=rows,
        qualification_claim=False,runtime_comparison_allowed=False,production_source_changed=False))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('command',choices=['freeze','run'])
    parser.add_argument('--output',type=Path,required=True);parser.add_argument('--witness',type=Path)
    args=parser.parse_args()
    if args.command=='freeze':
        if args.witness is None:parser.error('freeze requires --witness')
        freeze(args.output.resolve(),args.witness.resolve())
    else:run(args.output.resolve())
