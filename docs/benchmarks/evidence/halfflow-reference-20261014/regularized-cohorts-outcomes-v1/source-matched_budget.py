#!/usr/bin/env python3
"""Predeclare a measured comparable-cost lane using prior timing fields only.

Fine-level limits are chosen from already observed iteration-time prefixes.
Anatomy is never consulted. Predicted times select an experiment; only fresh
measured times can establish the pre-existing [0.8, 1.25] comparable-cost gate.
"""
import argparse
import copy
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import re
import shutil

from budget_study import run as run_budget
from real_image_court import sha, write_json


def timed_prefixes(log):
    groups=[]
    for line in log.splitlines():
        if line.startswith('XXDIAGNOSTIC,'):groups.append([])
        elif re.match(r'\s*\d+DIAGNOSTIC,',line):
            fields=line.split(',')
            if not groups:raise ValueError('Missing iteration group header')
            groups[-1].append((int(fields[1]),float(fields[4])))
    if len(groups)!=3 or any(not g for g in groups):
        raise ValueError('Three completed active levels required for timing calibration')
    return groups[-1]


def select_limit(prefixes, reference_seconds, control_seconds):
    # The last prefix excludes final transform export, process overhead and
    # independent geometry. Preserve their measured combined cost as a constant.
    overhead=reference_seconds-prefixes[-1][1]
    if overhead<0:raise ValueError('Iteration clock exceeds complete process/audit time')
    choices=[{'iterations':n,'predicted_seconds':seconds+overhead} for n,seconds in prefixes]
    selected=min(choices,key=lambda r:abs(math.log(r['predicted_seconds']/control_seconds)))
    return dict(selected,control_seconds=control_seconds,measured_noniteration_overhead_seconds=overhead,
                predicted_reference_over_control=selected['predicted_seconds']/control_seconds)


def freeze(source,study):
    if study.exists():raise FileExistsError(study)
    original=json.loads((source/'protocol.json').read_text())
    rows=json.loads((source/'results.json').read_text())['rows']
    if len(rows)!=len(original['cases'])*len(original['lanes']):raise ValueError('Original study must finish first')
    protocol=copy.deepcopy(original)
    protocol['schema']='hfq-p02-timing-calibrated-budget-v1'
    protocol['created_at']=datetime.now(timezone.utc).isoformat()
    protocol['parent_protocol_sha256']=sha(source/'protocol.json')
    protocol['parent_results_sha256']=sha(source/'results.json')
    protocol['selection']='Use only control/reference wall time and fine-iteration time prefixes. No anatomy-based choice. Fresh measured ratio must still lie [0.8,1.25]. No quality interpolation or extrapolation.'
    protocol['sources'][str(Path(__file__).resolve())]=sha(Path(__file__))
    protocol['calibration']={}
    limits={}
    for case in original['cases']:
        control=next(r for r in rows if r['pair']==case['id'] and r['lane']=='halfflow-selected')
        reference=next(r for r in rows if r['pair']==case['id'] and r['lane']=='syn-generous')
        if not control.get('audit_completed') or not reference.get('audit_completed'):
            raise ValueError('Completed timing required: '+case['id'])
        log=source/case['id']/'syn-generous-fit.log'
        chosen=select_limit(timed_prefixes(log.read_text()),reference['nonlinear_and_geometry_seconds'],control['nonlinear_and_geometry_seconds'])
        chosen['iteration_log_sha256']=sha(log)
        protocol['calibration'][case['id']]=chosen
        limits[case['id']]=[100,70,chosen['iterations']]
    protocol['lanes']=[{'id':'syn-matched','method':'ants','iterations_by_case':limits}]
    study.mkdir(parents=True)
    for name,digest in protocol['sources'].items():
        if sha(name)!=digest:raise ValueError('Source identity changed: '+name)
        shutil.copyfile(name,study/('source-'+Path(name).name))
    write_json(study/'protocol.json',protocol)
    for case in protocol['cases']:
        child=copy.deepcopy(protocol)
        child['cases']=[case]
        child['lanes']=[{'id':'syn-matched','method':'ants','iterations':limits[case['id']]}]
        child['parent_matched_protocol_sha256']=sha(study/'protocol.json')
        directory=study/('run-'+case['id']);directory.mkdir()
        write_json(directory/'protocol.json',child)
    print(json.dumps(protocol['calibration'],indent=2))


def run(study):
    protocol=json.loads((study/'protocol.json').read_text())
    rows=[]
    for case in protocol['cases']:
        child=study/('run-'+case['id'])
        run_budget(child)
        rows.extend(json.loads((child/'results.json').read_text())['rows'])
        write_json(study/'results.json',{'rows':rows,'qualification_claim':False})


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['freeze','run'])
    parser.add_argument('--source-study',type=Path)
    parser.add_argument('--study',type=Path,required=True)
    args=parser.parse_args()
    if args.command=='freeze':
        if args.source_study is None:parser.error('--source-study required to freeze')
        freeze(args.source_study.resolve(),args.study.resolve())
    else:run(args.study.resolve())
