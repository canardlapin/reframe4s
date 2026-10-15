#!/usr/bin/env python3
"""Replay the declared root solve and record all separated/negative-Jacobian roots."""
import argparse
from datetime import datetime,timezone
from pathlib import Path
import numpy as np
from analyze_c04 import read,write,sha,load,identify
from field_oracle import sample,solve,world_nodes


def freeze(output,analysis):
    if output.exists():raise FileExistsError(output)
    p=read(analysis/'protocol.json')
    if not (analysis/'results.json').exists():raise ValueError('Completed primary diagnosis required')
    sources=[Path(__file__).resolve(),Path(__file__).with_name('field_oracle.py').resolve(),
        Path(__file__).with_name('analyze_c04.py').resolve()]
    output.mkdir(parents=True)
    write(output/'protocol.json',dict(schema='hfq-r24-root-witness-v1',created_at=datetime.now(timezone.utc).isoformat(),
        analysis=str(analysis),primary_protocol_sha256=sha(analysis/'protocol.json'),
        primary_results_sha256=sha(analysis/'results.json'),primary_input_seal_sha256=sha(analysis/'input-seal.json'),
        sources={str(s):sha(s) for s in sources},method=p['method'],arms=p['arms'],
        selection='Every converged direct-query root with nonpositive analytic Jacobian or source separation above0.2mm; no largest-only selection.',
        maximum_separation_mm=.2,finite_difference_steps_mm=[1e-5,1e-6],
        scope='Detached root witness capture only. Same original-grid seeds, solver and parameters; every prior scalar root receipt must replay exactly. '
            'Record original and alternate coordinates, residuals, all derivative matrices and whether the root lies outside the original grid. '
            'Numerical alternate-preimage evidence is not a proof of global topology or a production inverse policy.',
        qualification_claim=False,runtime_comparison_allowed=False))
    print('FROZEN all-root witness follow-up, no solver or threshold changes',flush=True)


def run(output):
    p=read(output/'protocol.json');analysis=Path(p['analysis']);original=read(analysis/'protocol.json')
    for leaf,key in [('protocol.json','primary_protocol_sha256'),('results.json','primary_results_sha256'),('input-seal.json','primary_input_seal_sha256')]:
        if sha(analysis/leaf)!=p[key]:raise ValueError('Primary diagnosis changed')
    for path,digest in p['sources'].items():
        if sha(path)!=digest:raise ValueError('Witness source changed')
    capture=Path(original['capture']);capture_receipt=read(capture/'receipt.json')
    for name,digest in capture_receipt['field_artifact_sha256'].items():
        if sha(capture/name)!=digest:raise ValueError('Frozen field changed')
    directory=identify(capture,original['target_case'],original['target_lane'])
    prior={r['arm']:r for r in read(analysis/'results.json')['arms']};rows=[]
    for arm in p['arms']:
        f,valid,shape,affine,_=load(directory/(arm+'-forward'))
        g,gvalid,_,_,_=load(directory/(arm+'-inverse'));nodes=world_nodes(shape,affine)
        roots,root_report=solve(f,valid,shape,affine,nodes,g,**p['method'])
        root_f,root_valid,_=sample(f,valid,shape,affine,roots)
        root_valid &= np.linalg.norm(root_f-nodes,axis=1)<=p['method']['tolerance']
        seed,_,_=sample(roots,root_valid,shape,affine,f)
        direct,direct_report=solve(f,valid,shape,affine,f,seed,**p['method'])
        expected=prior[arm]['variants']['same-inverse-grid']
        if root_report!=expected['inverse_root_solve'] or direct_report!=expected['direct_forward_query_solve']:
            raise ValueError('Primary scalar root solve did not replay exactly')
        values,available,jac=sample(f,valid,shape,affine,direct,True)
        errors=np.linalg.norm(values-f,axis=1);separation=np.linalg.norm(direct-nodes,axis=1)
        determinant=np.linalg.det(jac)
        selected=np.flatnonzero(available & (errors<=p['method']['tolerance']) &
            ((determinant<=0) | (separation>p['maximum_separation_mm'])))
        witness=[];inverse=np.linalg.inv(affine[:3,:3])
        for index in selected:
            q=direct[index:index+1];voxel=(q[0]-affine[:3,3])@inverse.T
            finite=[]
            for step in p['finite_difference_steps_mm']:
                columns=[]
                for axis in range(3):
                    d=np.eye(3)[axis]*step
                    plus,_,_=sample(f,valid,shape,affine,q+d)
                    minus,_,_=sample(f,valid,shape,affine,q-d)
                    columns.append((plus[0]-minus[0])/(2*step))
                matrix=np.column_stack(columns)
                finite.append(dict(step_mm=step,jacobian=matrix.tolist(),determinant=float(np.linalg.det(matrix)),
                    maximum_abs_difference_from_analytic=float(np.max(np.abs(matrix-jac[index])))))
            witness.append(dict(index=int(index),original_source_mm=nodes[index].tolist(),alternate_source_mm=q[0].tolist(),
                target_mm=f[index].tolist(),alternate_mapped_mm=values[index].tolist(),
                source_separation_mm=float(separation[index]),equation_residual_mm=float(errors[index]),
                alternate_source_voxel=voxel.tolist(),outside_original_grid=bool(np.any((voxel<0)|(voxel>np.array(shape)-1))),
                distance_to_nearest_grid_plane_vox=float(np.min(np.abs(voxel-np.rint(voxel)))),
                analytic_jacobian=jac[index].tolist(),analytic_determinant=float(determinant[index]),finite_differences=finite))
        row=dict(arm=arm,scalar_root_receipts_replayed_exactly=True,selected_witnesses=len(witness),
            nonpositive_root_jacobians=int(np.sum(determinant<=0)),witnesses=witness)
        rows.append(row)
        print(arm,'witnesses',len(witness),'outside',sum(w['outside_original_grid'] for w in witness),
            'negative determinants',sum(w['analytic_determinant']<0 for w in witness),flush=True)
    write(output/'results.json',dict(protocol_sha256=sha(output/'protocol.json'),arms=rows,
        qualification_claim=False,runtime_comparison_allowed=False,production_source_changed=False))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('command',choices=['freeze','run'])
    parser.add_argument('--output',type=Path,required=True);parser.add_argument('--analysis',type=Path)
    args=parser.parse_args()
    if args.command=='freeze':
        if args.analysis is None:parser.error('freeze requires --analysis')
        freeze(args.output.resolve(),args.analysis.resolve())
    else:run(args.output.resolve())
