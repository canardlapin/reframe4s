#!/usr/bin/env python3
"""Apply the existing complete-cell evaluator to every captured C04 field."""
import argparse
from datetime import datetime,timezone
from pathlib import Path
import sys
import numpy as np
from analyze_c04 import read,write,sha,load

ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'benchmarks/flashalign'))
from real_mri_cell_topology import cell_diagnostics


def freeze(output,capture):
    if output.exists():raise FileExistsError(output)
    receipt=read(capture/'receipt.json')
    if receipt['exit_code']!=0 or not receipt['source_and_inputs_unchanged']:raise ValueError('Terminal immutable capture required')
    case=lane=None;tasks=[];central={}
    for line in (capture/'replay/execution.log').read_text().splitlines():
        if 'DIAG_CASE\t' in line:case=line.split('DIAG_CASE\t',1)[1]
        elif 'DIAG_LANE\t' in line:lane=line.split('DIAG_LANE\t',1)[1]
        elif 'DIAG_TOPOLOGY\t' in line:
            values=line.split('DIAG_TOPOLOGY\t',1)[1].split('\t')
            central[case,lane,values[0]]=dict(minimum=float(values[1]),nonpositive=int(values[2]),evaluated=int(values[3]),field_sha256=values[4])
        elif 'DIAG_DUMP\t' in line:
            directory=Path(line.split('DIAG_DUMP\t',1)[1])
            for arm in ['fixed','moving']:
                for kind in ['forward','inverse']:
                    tasks.append(dict(case=case,lane=lane,arm=arm,kind=kind,stem=str(directory/(arm+'-'+kind))))
    if len(tasks)!=48 or len({(t['case'],t['lane'],t['arm'],t['kind']) for t in tasks})!=48:
        raise ValueError('All six cases/two lanes/two arms/two field kinds required')
    for task in tasks:
        label=task['arm']+('-arm' if task['kind']=='forward' else '-inverse')
        task['central_difference']=central[task['case'],task['lane'],label]
    sources=[Path(__file__).resolve(),Path(__file__).with_name('analyze_c04.py').resolve(),
        ROOT/'benchmarks/flashalign/real_mri_cell_topology.py',ROOT/'benchmarks/flashalign/real_image_court.py']
    output.mkdir(parents=True)
    write(output/'protocol.json',dict(schema='hfq-c04-all-field-cell-audit-v1',created_at=datetime.now(timezone.utc).isoformat(),
        capture=str(capture),capture_receipt_sha256=sha(capture/'receipt.json'),tasks=tasks,
        sources={str(p):sha(p) for p in sources},scope='All48 captured fields, all complete cells, eight corners and center. '
            'Use the unchanged existing evaluator and thresholds, not a selected witness subset. '
            'This is a field diagnostic on six observed synthetic cases, not endpoint/MRI qualification.',
        qualification_claim=False,runtime_comparison_allowed=False))
    print('FROZEN all48 fields, unchanged complete-cell evaluator',flush=True)


def run(output):
    p=read(output/'protocol.json');capture=Path(p['capture'])
    if sha(capture/'receipt.json')!=p['capture_receipt_sha256']:raise ValueError('Capture receipt changed')
    for name,digest in p['sources'].items():
        if sha(name)!=digest:raise ValueError('Cell evaluator changed')
    for name,digest in read(capture/'receipt.json')['field_artifact_sha256'].items():
        if sha(capture/name)!=digest:raise ValueError('Captured field changed')
    rows=[]
    for task in p['tasks']:
        values,valid,shape,affine,digest=load(Path(task['stem']))
        if digest!=task['central_difference']['field_sha256']:raise ValueError('Central-difference field differs')
        coords=np.stack([values[:,c].reshape(shape,order='F') for c in range(3)],axis=-1)
        result=cell_diagnostics(coords,valid.reshape(shape,order='F'),affine)
        rows.append(dict(task=task,cell_diagnostics=result))
        print(task['case'],task['lane'],task['arm'],task['kind'],'folded cells',result['folded_cells'],
            'minimum',result['minimum_cell_jacobian'],flush=True)
    write(output/'results.json',dict(protocol_sha256=sha(output/'protocol.json'),rows=rows,
        qualification_claim=False,runtime_comparison_allowed=False,production_source_changed=False))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('command',choices=['freeze','run'])
    parser.add_argument('--output',type=Path,required=True);parser.add_argument('--capture',type=Path)
    args=parser.parse_args()
    if args.command=='freeze':
        if args.capture is None:parser.error('freeze requires --capture')
        freeze(args.output.resolve(),args.capture.resolve())
    else:run(args.output.resolve())
