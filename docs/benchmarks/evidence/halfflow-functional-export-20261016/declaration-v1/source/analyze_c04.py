#!/usr/bin/env python3
"""Predeclare and diagnose r24 frozen residuals, preserving every boundary sample."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import struct

import numpy as np
from field_oracle import sample,solve,world_nodes,composition_errors


def read(p):return json.loads(Path(p).read_text())
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def write(p,x):p.write_text(json.dumps(x,indent=2,allow_nan=False)+'\n')


def freeze(output,capture,corrected_admission=False):
    if corrected_admission and not read(capture/'protocol.json').get('production_cell_admission_changed'):
        raise ValueError('Corrected-admission capture required')
    output.mkdir(parents=True,exist_ok=False)
    paths=[Path(__file__).resolve(),Path(__file__).with_name('field_oracle.py').resolve(),
        Path(__file__).with_name('test_field_oracle.py').resolve()]
    write(output/'protocol.json',dict(schema='hfq-r24-boundary-root-oracle-v1',created_at=datetime.now(timezone.utc).isoformat(),
        sources={str(p):sha(p) for p in paths},capture=str(capture),capture_protocol_sha256=sha(capture/'protocol.json'),
        target_case='r24-exact-core-affine',target_lane='C4',arms=['fixed','moving'],
        corrected_admission=corrected_admission,
        required_cell_audit=str(output.parent/'c04-cells-v1/results.json') if corrected_admission else None,
        historical_comparison=str(Path(__file__).resolve().parents[3]/'benchmarks/flashalign/diagnostics/halfflow-export-topology-v1/comparison.json'),
        method=dict(tolerance=1e-8,iterations=40,step_cap=4.,backtracks=12),
        variants=['same-inverse-grid','padded-inverse-grid'],
        padding='Same physical basis/spacing; integer voxel bounds enclose original grid and all forward nodal coordinates, plus one exterior cell on every face.',
        scope='At every original target node, solve the current forward boundary-linear trilinear field equation with bounded Newton/backtracking. '
            'Compare storing those roots on the original or padded inverse lattice. Also solve directly at each forward-node query. '
            'Root convergence does not establish unique/global invertibility or cell topology. '+
            ('Use the separately identified complete-cell-admission correction; no additional optimizer or inverse-source changes in this root experiment.'
            if corrected_admission else 'No optimizer or production source changes.'),
        denominator='Both compositions keep every original target grid node; failed/unsupported roots and invalid compositions remain explicit.',
        baseline_requirement=('Corrected capture and independent all48 field audit must complete; both r24 C4 forward fields must pass '
            'the unchanged cell floor/coverage before root analysis. Historical hash agreement is reported without historical attribution.'
            if corrected_admission else 'Both r24 C4 forward residual hashes must match the historical topology diagnosis before attribution. '
            'Current stored inverse hashes are recorded separately because its exporter changed since that diagnosis.'),
        maximum_error_mm=.2,interior_margin=6,qualification_claim=False,runtime_comparison_allowed=False))
    print('FROZEN r24 two-arm, two-grid root diagnosis; corrected admission',corrected_admission,flush=True)


def load(stem):
    metadata=read(stem.with_suffix('.json'));shape=metadata['shape'];affine=np.array(metadata['affine']).reshape(4,4)
    values=np.fromfile(stem.with_suffix('.f64'),dtype='>f8').astype(float).reshape(3,-1).T
    valid=np.fromfile(stem.with_suffix('.valid-u8'),dtype=np.uint8).astype(bool)
    if len(values)!=np.prod(shape) or len(valid)!=len(values):raise ValueError('Incomplete field')
    digest=hashlib.sha256()
    for v,coords in zip(valid,values,strict=True):digest.update(struct.pack('>?ddd',v,*coords))
    return values,valid,shape,affine,digest.hexdigest()


def identify(capture,case,lane):
    current_case=current_lane=None;matches=[]
    for line in (capture/'replay/execution.log').read_text().splitlines():
        if 'DIAG_CASE\t' in line:current_case=line.split('DIAG_CASE\t',1)[1]
        elif 'DIAG_LANE\t' in line:current_lane=line.split('DIAG_LANE\t',1)[1]
        elif 'DIAG_DUMP\t' in line and (current_case,current_lane)==(case,lane):
            matches.append(Path(line.split('DIAG_DUMP\t',1)[1]))
    if len(matches)!=1:raise ValueError('Exactly one declared case/lane capture required')
    return matches[0]


def run(output):
    p=read(output/'protocol.json');capture=Path(p['capture'])
    for name,digest in p['sources'].items():
        if sha(name)!=digest:raise ValueError('Oracle source changed')
    if sha(capture/'protocol.json')!=p['capture_protocol_sha256']:raise ValueError('Capture protocol changed')
    receipt=read(capture/'receipt.json')
    if receipt['exit_code']!=0 or not receipt['source_and_inputs_unchanged']:
        raise ValueError('Capture must have a successful terminal receipt')
    for name,digest in receipt['field_artifact_sha256'].items():
        if sha(capture/name)!=digest:raise ValueError('Frozen field changed')
    directory=identify(capture,p['target_case'],p['target_lane'])
    history=read(p['historical_comparison'])['current'][p['target_case']][p['target_lane']]
    loaded={arm:{kind:load(directory/(arm+'-'+kind)) for kind in ['forward','inverse']} for arm in p['arms']}
    corrected=p.get('corrected_admission',False)
    audit_sha=None
    if corrected:
        if not read(capture/'protocol.json').get('production_cell_admission_changed'):
            raise ValueError('Corrected capture declaration changed')
        audit=read(p['required_cell_audit'])
        if len(audit['rows'])!=48:raise ValueError('Complete independent all48 field audit required')
        for arm in p['arms']:
            matches=[r for r in audit['rows'] if (r['task']['case'],r['task']['lane'],r['task']['arm'],r['task']['kind'])==
                (p['target_case'],p['target_lane'],arm,'forward')]
            if len(matches)!=1 or not matches[0]['cell_diagnostics']['pass_criteria'] or \
                    matches[0]['task']['central_difference']['field_sha256']!=loaded[arm]['forward'][4]:
                raise ValueError('Corrected r24 forward field failed independent cell audit')
        audit_sha=sha(p['required_cell_audit'])
    for arm in p['arms']:
        expected=history['topology'][arm+'-arm']['field_sha256']
        if not corrected and loaded[arm]['forward'][4]!=expected:raise ValueError('Historical '+arm+' residual does not replay')
    write(output/'input-seal.json',dict(capture_receipt_sha256=sha(capture/'receipt.json'),
        historical_comparison_sha256=sha(p['historical_comparison']),protocol_sha256=sha(output/'protocol.json'),
        residual_replay={arm:loaded[arm]['forward'][4] for arm in p['arms']},
        historical_forward_hash_match={arm:loaded[arm]['forward'][4]==history['topology'][arm+'-arm']['field_sha256'] for arm in p['arms']},
        independent_cell_audit_sha256=audit_sha,corrected_admission=corrected))
    outcomes=[]
    for arm in p['arms']:
        f,fvalid,shape,affine,fhash=loaded[arm]['forward']
        g,gvalid,gshape,gaffine,ghash=loaded[arm]['inverse']
        if shape!=gshape or not np.array_equal(affine,gaffine):raise ValueError('Unexpected inverse lattice')
        baseline=composition_errors(f,g,fvalid,gvalid,shape,affine,p['interior_margin'])
        inverse_basis=np.linalg.inv(affine[:3,:3]);f_vox=(f-affine[:3,3])@inverse_basis.T
        low=np.floor(np.minimum(0,f_vox.min(axis=0))).astype(int)-1
        high=np.ceil(np.maximum(np.array(shape)-1,f_vox.max(axis=0))).astype(int)+1
        padded_shape=(high-low+1).tolist();padded_affine=affine.copy()
        padded_affine[:3,3]+=affine[:3,:3]@low
        nodes=world_nodes(shape,affine);variants={}
        for name,inverse_shape,inverse_affine in [('same-inverse-grid',shape,affine),('padded-inverse-grid',padded_shape,padded_affine)]:
            targets=world_nodes(inverse_shape,inverse_affine)
            seed,seed_valid,_=sample(g,gvalid,shape,affine,targets)
            if not seed_valid.all():raise ValueError('Unsupported inverse seed; do not silently substitute')
            roots,root_report=solve(f,fvalid,shape,affine,targets,seed,**p['method'])
            sampled,root_valid,_=sample(f,fvalid,shape,affine,roots)
            root_valid &= np.linalg.norm(sampled-targets,axis=1)<=p['method']['tolerance']
            errors=composition_errors(f,roots,fvalid,root_valid,shape,affine,p['interior_margin'],inverse_shape,inverse_affine)
            seed_queries,_,_=sample(roots,root_valid,inverse_shape,inverse_affine,f)
            direct,direct_report=solve(f,fvalid,shape,affine,f,seed_queries,**p['method'])
            variants[name]=dict(inverse_grid_shape=inverse_shape,inverse_grid_affine=inverse_affine.tolist(),
                inverse_root_solve=root_report,composition_errors=errors,direct_forward_query_solve=direct_report,
                direct_forward_roundtrip_maximum_mm=float(np.max(np.linalg.norm(direct-nodes,axis=1))))
            print(arm,name,'roots unresolved',root_report['unresolved'],
                'cycle max',[v['all']['maximum_mm'] for v in errors.values()],flush=True)
        outcomes.append(dict(arm=arm,shape=shape,affine=affine.tolist(),forward_sha256=fhash,
            stored_inverse_sha256=ghash,stored_inverse_matches_historical=ghash==history['topology'][arm+'-inverse']['field_sha256'],
            historical_inverse=history['inverse'][arm],stored_inverse_errors=baseline,variants=variants))
    write(output/'results.json',dict(protocol_sha256=sha(output/'protocol.json'),input_seal_sha256=sha(output/'input-seal.json'),
        arms=outcomes,qualification_claim=False,runtime_comparison_allowed=False,
        production_source_changed=corrected,global_invertibility_established=False))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['freeze','run']);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--capture',type=Path)
    parser.add_argument('--corrected-admission',action='store_true')
    args=parser.parse_args()
    if args.command=='freeze':
        if args.capture is None:parser.error('freeze requires --capture')
        freeze(args.output.resolve(),args.capture.resolve(),args.corrected_admission)
    else:run(args.output.resolve())
