#!/usr/bin/env python3
"""Compare captured rejected candidates with their exact captured current state.

Missing current states remain unavailable; no interpolation or label selection
is used. Physical state changes include the accumulated regularization step.
"""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np


def read(path):return json.loads(Path(path).read_text())


def sha(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda:stream.read(1048576),b''):h.update(chunk)
    return h.hexdigest()


def compare(base):
    events=base/'checkpoints/events.jsonl'
    rows=[json.loads(line) for line in events.read_text().splitlines()]
    current={};results=[]
    for event in rows:
        info=event['info'];key=(info['levelIndex'],info['acceptedSteps'])
        if info['kind']!='Rejected':
            if event['directory'] is not None:current[key]=event
            continue
        if event['directory'] is None:continue
        row={'serial':event['serial'],'level':info['levelIndex'],'attempt':info['attempts'],
             'accepted_steps':info['acceptedSteps'],'attempted_velocity_maximum_mm':info['trial']['maximumStepMm']}
        if key not in current:
            row['status']='exact_current_state_not_captured';results.append(row);continue
        before=base/'checkpoints'/current[key]['directory'];after=base/'checkpoints'/event['directory']
        row['reference_serial']=current[key]['serial'];row['arms']={};row['inputs']={}
        for arm in ['fixed','moving']:
            name='midpoint-'+arm+'-residual'
            a,b=read(before/(name+'.json')),read(after/(name+'.json'))
            if a!=b:raise ValueError('Different raw state grids or persistence conventions')
            affine_name='midpoint-'+arm+'-affine.json'
            affine=read(before/affine_name)
            if affine!=read(after/affine_name):raise ValueError('Affine arm changed')
            first=np.fromfile(before/(name+'.f64'),dtype='>f8').reshape(-1,3)
            second=np.fromfile(after/(name+'.f64'),dtype='>f8').reshape(-1,3)
            first_valid=np.fromfile(before/(name+'.valid-u8'),dtype=np.uint8)>0
            second_valid=np.fromfile(after/(name+'.valid-u8'),dtype=np.uint8)>0
            finite=np.isfinite(first).all(axis=1)&np.isfinite(second).all(axis=1)
            valid=first_valid&second_valid&finite
            if not valid.any():raise ValueError('No valid comparison samples')
            delta=second[valid]-first[valid]
            residual=np.linalg.norm(delta,axis=1)
            endpoint=np.linalg.norm(delta@np.asarray(affine).reshape(4,4)[:3,:3].T,axis=1)
            row['arms'][arm]={'declared_voxels':len(valid),'evaluated_voxels':int(valid.sum()),
                'nonfinite_voxels':int((~finite).sum()),'validity_unchanged':bool(np.array_equal(first_valid,second_valid)),
                'maximum_residual_coordinate_change_mm':float(residual.max()),
                'rms_residual_coordinate_change_mm':float(np.sqrt(np.mean(residual**2))),
                'maximum_endpoint_coordinate_change_mm':float(endpoint.max()),
                'rms_endpoint_coordinate_change_mm':float(np.sqrt(np.mean(endpoint**2)))}
            for directory in [before,after]:
                for filename in [name+'.json',name+'.f64',name+'.valid-u8',affine_name]:
                    path=directory/filename;row['inputs'][str(path)]=sha(path)
        row['status']='compared';results.append(row)
    return {'schema':'hfq-rejected-candidate-change-v1','source_sha256':sha(Path(__file__)),
            'events_sha256':sha(events),'rows':results,
            'scope':'Declared captured rejections versus exact captured current state; whole raw lattice with explicit validity counts',
            'qualification_claim':False}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    if args.output.exists():raise FileExistsError(args.output)
    result=compare(args.directory.resolve())
    with args.output.open('x') as stream:json.dump(result,stream,indent=2);stream.write('\n')
    print(json.dumps([{k:v for k,v in row.items() if k!='inputs'} for row in result['rows']],indent=2))
