#!/usr/bin/env python3
"""Independent exact piecewise-linear oracle for boundary inverse representation.

The one-dimensional maps embed as (F(x), y, z) in three dimensions. This tests
representation limits, not the production refiner or the C04 r24 mechanism.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path

import numpy as np


def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def linear_sample(knots, values, queries):
    knots = np.asarray(knots,dtype=float);values = np.asarray(values,dtype=float)
    queries = np.asarray(queries,dtype=float)
    if knots.ndim != 1 or len(knots) < 2 or values.shape != knots.shape:
        raise ValueError('At least two scalar knot/value pairs required')
    if not all(np.isfinite(a).all() for a in [knots,values,queries]) or np.any(np.diff(knots) <= 0):
        raise ValueError('Finite values and strictly ordered knots required')
    index = np.clip(np.searchsorted(knots,queries,side='right')-1,0,len(knots)-2)
    fraction = (queries-knots[index])/(knots[index+1]-knots[index])
    return values[index]+fraction*(values[index+1]-values[index])


def inspect_case(x, forward, inverse_grid):
    x = np.asarray(x,dtype=float);forward = np.asarray(forward,dtype=float)
    if np.any(np.diff(forward) <= 0):raise ValueError('Monotone forward map required')
    # Swapping the knots and values gives the exact inverse of a strictly
    # monotone piecewise-linear map, including its two exterior linear pieces.
    inverse = linear_sample(forward,x,inverse_grid)
    exact_inverse_at_targets = linear_sample(forward,x,x)
    first = linear_sample(x,forward,linear_sample(inverse_grid,inverse,x))-x
    second = linear_sample(inverse_grid,inverse,forward)-x
    exact_first = linear_sample(x,forward,exact_inverse_at_targets)-x
    exact_second = linear_sample(forward,x,forward)-x
    return dict(eligible_forward_then_inverse=len(x),eligible_inverse_then_forward=len(x),
        evaluated_forward_then_inverse=len(x),evaluated_inverse_then_forward=len(x),
        inverse_grid=[float(v) for v in inverse_grid],inverse_samples=[float(v) for v in inverse],
        forward_then_inverse_error_mm=second.tolist(),inverse_then_forward_error_mm=first.tolist(),
        maximum_forward_then_inverse_mm=float(np.max(np.abs(second))),
        maximum_inverse_then_forward_mm=float(np.max(np.abs(first))),
        maximum_pointwise_oracle_error_mm=float(max(np.max(np.abs(exact_first)),np.max(np.abs(exact_second)))),
        forward_queries_outside_inverse_grid=int(np.sum((forward<inverse_grid[0]) | (forward>inverse_grid[-1]))),
        minimum_forward_cell_jacobian=float(np.min(np.diff(forward)/np.diff(x))),
        minimum_inverse_cell_jacobian=float(np.min(np.diff(inverse)/np.diff(inverse_grid))),
        all_samples_finite=bool(np.isfinite(first).all() and np.isfinite(second).all()),
        maximum_error_mm=float(max(np.max(np.abs(first)),np.max(np.abs(second)))))


def freeze(output):
    output.mkdir(parents=True,exist_ok=False)
    protocol = dict(schema='hfq-boundary-representation-oracle-v1',created_at=datetime.now(timezone.utc).isoformat(),
        sources={str(Path(__file__).resolve()):sha(__file__),
            str(Path(__file__).with_name('test_representation.py').resolve()):sha(Path(__file__).with_name('test_representation.py'))},
        grid=dict(first_mm=0.,spacing_mm=1.,count=25),
        cases=[dict(id='translation',scale=1.,shift_mm=3.,quadratic_per_mm=0.),
            dict(id='affine',scale=1.05,shift_mm=3.,quadratic_per_mm=0.),
            dict(id='monotone-nonlinear',scale=1.,shift_mm=3.,quadratic_per_mm=.04)],
        representation='Sample the prescribed polynomial at the 25 knots; the authoritative map is its piecewise-linear interpolation with linear exterior continuation. Invert this map exactly by swapping its strictly increasing knot/value arrays.',
        inverse_grids=['same-target-grid','padded-to-forward-image-plus-one-cell'],
        padding_rule='Retain 1 mm spacing; extend the inverse grid to floor(min(target,forward))-1 through ceil(max(target,forward))+1. Evaluate only the original 25 target/forward nodes in both compositions.',
        maximum_error_mm=.2,oracle_tolerance_mm=1e-12,
        scope='Independent representation counterexample. All samples retained, no numerical solver, fit or MRI. No attribution to actual C04 r24 without a separate frozen-field probe.',
        qualification_claim=False,runtime_comparison_allowed=False)
    (output/'protocol.json').write_text(json.dumps(protocol,indent=2)+'\n')
    print('FROZEN three maps, two inverse grids, unchanged 25-node denominators')


def run(output):
    protocol_path=output/'protocol.json';p=json.loads(protocol_path.read_text())
    for path,digest in p['sources'].items():
        if sha(path)!=digest:raise ValueError('Oracle source changed')
    if (output/'results.json').exists():raise FileExistsError('Prior outcome retained')
    g=p['grid'];x=g['first_mm']+g['spacing_mm']*np.arange(g['count'])
    cases=[]
    for case in p['cases']:
        f=case['scale']*x+case['shift_mm']+case['quadratic_per_mm']*x*x
        low=np.floor(min(x.min(),f.min()))-1;high=np.ceil(max(x.max(),f.max()))+1
        padded=np.arange(low,high+1,g['spacing_mm'])
        variants={name:inspect_case(x,f,grid) for name,grid in zip(p['inverse_grids'],[x,padded])}
        for row in variants.values():
            row['within_declared_error_bound']=row['maximum_error_mm']<=p['maximum_error_mm']
            if row['maximum_pointwise_oracle_error_mm']>p['oracle_tolerance_mm']:
                raise ValueError('Exact pointwise inverse oracle failed')
        cases.append(dict(case=case,x_mm=x.tolist(),forward_samples_mm=f.tolist(),variants=variants))
    result=dict(schema='hfq-boundary-representation-outcomes-v1',protocol_sha256=sha(protocol_path),
        cases=cases,qualification_claim=False,runtime_comparison_allowed=False,
        production_source_changed=False,actual_r24_mechanism_established=False)
    (output/'results.json').write_text(json.dumps(result,indent=2,allow_nan=False)+'\n')
    for row in cases:
        print(row['case']['id'],{k:v['maximum_error_mm'] for k,v in row['variants'].items()})


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['freeze','run']);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();(freeze if args.command=='freeze' else run)(args.output.resolve())
