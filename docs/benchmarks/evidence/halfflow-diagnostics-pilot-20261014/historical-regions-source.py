#!/usr/bin/env python3
"""Describe every structure in previously observed fine-refinement failures.

This uses existing reports only. Structure ranking is diagnostic and cannot
select a runtime checkpoint, candidate policy or qualification subject.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import statistics


def read(path):return json.loads(Path(path).read_text())
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def analyze(protocol):
    panel=read(protocol);pairs=[];inputs={str(protocol):sha(protocol)}
    for case in panel['cases']:
        historical=case['historical_outputs']
        if 'step-scaled-fine1.5' not in historical:continue
        reports={}
        for policy,directory in historical.items():
            path=Path(directory)/'halfflow-evaluation.json'
            reports[policy]=read(path);inputs[str(path)]=sha(path)
        baseline=reports['selected-control'];candidate=reports['step-scaled-fine1.5']
        regional={}
        for direction in ['fixed-to-moving','moving-to-fixed']:
            before={r['label']:r for r in baseline['directions'][direction]['anatomy']['labels']}
            after={r['label']:r for r in candidate['directions'][direction]['anatomy']['labels']}
            if before.keys()!=after.keys():raise ValueError('Different reported structure sets; do not silently intersect them')
            for label in sorted(before):
                a,b=before[label],after[label]
                if a['fixed_voxels']!=b['fixed_voxels']:raise ValueError('Reference ROI denominator changed')
                regional.setdefault(label,[]).append({'direction':direction,
                    'target_voxels':a['fixed_voxels'],'baseline_warped_voxels':a['warped_voxels'],
                    'candidate_warped_voxels':b['warped_voxels'],
                    'baseline_dice':a['dice'],'candidate_dice':b['dice'],'dice_change':b['dice']-a['dice'],
                    'baseline_hd95_mm':a['hd95_mm'],'candidate_hd95_mm':b['hd95_mm'],
                    'hd95_change_mm':None if a['hd95_mm'] is None or b['hd95_mm'] is None else b['hd95_mm']-a['hd95_mm']})
        structures=[]
        for label,directions in sorted(regional.items()):
            if len(directions)!=2:raise ValueError('A direction is missing from a structure')
            structures.append({'label':label,'directions':directions,
                'bidirectional_dice_change':statistics.mean(r['dice_change'] for r in directions),
                'bidirectional_hd95_change_mm':statistics.mean(r['hd95_change_mm'] for r in directions)
                    if all(r['hd95_change_mm'] is not None for r in directions) else None})
        def mean_report(report,metric):
            return statistics.mean(report['directions'][direction]['anatomy'][metric]
                                   for direction in ['fixed-to-moving','moving-to-fixed'])
        pairs.append({'pair':case['id'],'structures':structures,
            'mean_dice_change':mean_report(candidate,'macro_dice')-mean_report(baseline,'macro_dice'),
            'bidirectional_median_hd95_change_mm':mean_report(candidate,'median_hd95_mm')-mean_report(baseline,'median_hd95_mm'),
            'structure_count':len(structures),
            'structures_with_lower_bidirectional_dice':sum(r['bidirectional_dice_change']<0 for r in structures),
            'structures_with_higher_bidirectional_hd95':sum(r['bidirectional_hd95_change_mm'] is not None and r['bidirectional_hd95_change_mm']>0 for r in structures),
            'worst_five_dice_labels':[r['label'] for r in sorted(structures,key=lambda r:r['bidirectional_dice_change'])[:5]]})
    return {'schema':'hfq-historical-regions-v1','created_at':datetime.now(timezone.utc).isoformat(),
            'source_sha256':sha(Path(__file__)),'inputs':inputs,'pairs':pairs,
            'scope':'Descriptive existing observed-data comparison, step-scaled-fine1.5 minus selected control; every reported structure retained',
            'runtime_selection':False,'qualification_claim':False}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    if args.output.exists():raise FileExistsError(args.output)
    result=analyze(args.protocol.resolve())
    with args.output.open('x') as stream:json.dump(result,stream,indent=2);stream.write('\n')
    print(json.dumps([{k:v for k,v in row.items() if k!='structures'} for row in result['pairs']],indent=2))
