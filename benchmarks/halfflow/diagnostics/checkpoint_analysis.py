#!/usr/bin/env python3
"""Score every saved checkpoint, preserving failed exports and missing states.

Analysis sources are sealed before the first score. Original captured files
are read-only inputs; derived reports live in a separate analysis directory.
"""
import argparse
import contextlib
from datetime import datetime, timezone
import gc
import json
from pathlib import Path
import shutil
import sys

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
sys.path.insert(0,str(ROOT/'benchmarks/halfflow/reference'))
sys.path.insert(0,str(ROOT/'benchmarks/flashalign'))
from audit_study import audit_maps
from real_image_court import read_pull, sha
from fixed_audit import audit as fixed_audit
from regularity import displacement_regularity
from offline_analysis import inspect_plan, resolve_export


def read(path):return json.loads(Path(path).read_text())


def write(path,value):
    with Path(path).open('x') as stream:
        json.dump(value,stream,indent=2);stream.write('\n')


def freeze(study,output,offline_exports=None):
    if output.exists():raise FileExistsError(output)
    offline = None
    if offline_exports is not None:
        _, tasks, ledger = inspect_plan(study, offline_exports)
        offline = dict(directory=str(offline_exports), protocol_sha256=sha(offline_exports/'protocol.json'),
                       fit_ledger=ledger, export_tasks=len(tasks))
    output.mkdir(parents=True)
    paths=[Path(__file__),HERE/'fixed_audit.py',HERE/'regularity.py',HERE/'test_regularity.py',
           HERE/'offline_analysis.py',HERE/'test_offline_analysis.py',
           HERE/'audit-contract-v1.json', ROOT/'benchmarks/halfflow/reference/audit_study.py',
           ROOT/'benchmarks/halfflow/reference/canonical_ants.py',
           ROOT/'benchmarks/flashalign/real_image_court.py',ROOT/'benchmarks/flashalign/real_mri_cell_topology.py']
    sources={str(p):sha(p) for p in paths}
    for path in paths:shutil.copyfile(path,output/('source-'+path.name))
    write(output/'protocol.json',{
        'schema':'hfq-checkpoint-analysis-v2','created_at':datetime.now(timezone.utc).isoformat(),
        'trajectory_study':str(study),'trajectory_protocol_sha256':sha(study/'engine/protocol.json'),
        'diagnostic_seal_sha256':sha(study/'diagnostic-seal.json'),'sources':sources,
        'geometry':'Unchanged independent brain inverse, coverage and complete-cell sample checks; invalid outputs remain invalid',
        'intensity':'Sealed fixed audit contract, both original prepared target grids/masks; invalid windows receive loss 1',
        'regularity':'Physical Frobenius norm of midpoint residual displacement gradient on every valid central-difference stencil; affine arms excluded; denominators retained',
        'anatomy':'Original per-structure bidirectional Dice/HD95; diagnostic-only observed labels, no policy/stopping selection',
        'missing':'Every scalar event retained; uncaptured/unavailable/export-construction-failed states remain explicit; no scores invented',
        'offline_exports':offline,
        'scope':'All terminal source fits by default; with offline exports, exactly the declared export batch. Every selected fit remains in fit_ledger, including failures with no events. Pending exports receive no score or terminal analysis receipt.',
        'qualification_claim':False})


def run(output,limit=None):
    protocol=read(output/'protocol.json');study=Path(protocol['trajectory_study'])
    for path,digest in protocol['sources'].items():
        if sha(path)!=digest:raise ValueError('Analysis source changed: '+path)
    if sha(study/'engine/protocol.json')!=protocol['trajectory_protocol_sha256'] or sha(study/'diagnostic-seal.json')!=protocol['diagnostic_seal_sha256']:
        raise ValueError('Trajectory identities changed')
    # Check images, masks, labels and saved affines against the pre-fit seal.
    for path,digest in read(study/'engine/inputs.json').items():
        if sha(path)!=digest:raise ValueError('Trajectory input changed: '+path)
    offline=protocol.get('offline_exports');tasks={};selected=None
    if offline is not None:
        exports=Path(offline['directory'])
        if sha(exports/'protocol.json')!=offline['protocol_sha256']:raise ValueError('Export plan changed')
        export_protocol,tasks,ledger=inspect_plan(study,exports)
        if ledger!=offline['fit_ledger'] or len(tasks)!=offline['export_tasks']:raise ValueError('Export scope changed')
        selected={(fit['pair'],fit['policy']) for fit in ledger}
    panel=read(study/'engine/protocol.json');completed=0
    for case in panel['cases']:
        for policy in panel['lanes']:
            if selected is not None and (case['id'],policy['id']) not in selected:continue
            base=study/case['id']/policy['id'];events=base/'checkpoints/events.jsonl'
            fit=base.parent/(policy['id']+'-fit-receipt.json')
            if not fit.exists():continue  # Never score a still-mutating registration run.
            if not events.exists():continue # The failed fit receipt remains in the panel denominator.
            if sha(events)!=read(fit)['events_sha256']:raise ValueError('Checkpoint event stream changed')
            for event in [json.loads(line) for line in events.read_text().splitlines()]:
                parent=output/case['id']/policy['id'];parent.mkdir(parents=True,exist_ok=True)
                receipt=parent/(f"event-{event['serial']:04d}.json")
                if receipt.exists():continue
                row={'pair':case['id'],'policy':policy['id'],'event':event,
                     'protocol_sha256':sha(output/'protocol.json'),'fit_receipt_sha256':sha(fit),
                     'fit_exit_code':read(fit)['exit_code'],
                     'status':'not_captured','qualification_claim':False}
                resolved=None
                if offline is not None and event['directory'] is not None:
                    task=tasks.get((case['id'],policy['id'],event['serial']))
                    resolved=resolve_export(exports,export_protocol,task) if task else {'status':'export_not_requested'}
                    if resolved['status']=='export_pending':continue
                    row.update({key:value for key,value in resolved.items() if key not in ['directory','lane']})
                    row['offline_export_protocol_sha256']=offline['protocol_sha256']
                    if resolved['status']!='available':
                        write(receipt,row)
                        print(case['id'],policy['id'],event['serial'],row['status'],flush=True)
                        completed+=1
                        if limit is not None and completed>=limit:return
                        continue
                if event['directory'] is not None:
                    original=base/'checkpoints'/event['directory']
                    exported=resolved['directory'] if resolved else original
                    target=parent/event['directory'];target.mkdir()
                    logfile=parent/(event['directory']+'.log')
                    try:
                        with logfile.open('x') as log,contextlib.redirect_stdout(log),contextlib.redirect_stderr(log):
                            row['regularity']={}
                            for arm in ['fixed','moving']:
                                name='midpoint-'+arm+'-residual'
                                coords,valid,_,grid=read_pull(original,name)
                                row['regularity'][arm]=displacement_regularity(coords,valid,grid)
                            lane=resolved['lane'] if resolved else ('halfflow' if (original/'halfflow-fixed-to-moving.json').exists() else 'halfflow-rejected')
                            if not (exported/(lane+'-fixed-to-moving.json')).exists():
                                row['status']='export_unavailable'
                            else:
                                names=[lane+'-'+direction+suffix for direction in ['fixed-to-moving','moving-to-fixed']
                                       for suffix in ['.f64','.json','.valid-u8','-warped.f64']]
                                row['map_inputs']={name:sha(exported/name) for name in names}
                                for name in names:(target/name).symlink_to((exported/name).resolve())
                                for name in ['mode.txt','plan.txt']:(target/name).symlink_to((base/name).resolve())
                                status=resolved['export_status'] if resolved else event['export_status']
                                accepted=status=='native export gate accepted'
                                # The evaluator's native-admission header is reconstructed from the
                                # explicit captured status, without modifying native output records.
                                with (target/'export-diagnostics.txt').open('x') as stream:
                                    stream.write(('accepted' if accepted else status)+'\n')
                                    stream.write((exported/'export-diagnostics.txt').read_text())
                                row['fixed_intensity']=fixed_audit(Path(case['data']),target,lane)
                                row['geometry_and_anatomy']=audit_maps(case,target,lane)
                                row['native_export_accepted']=accepted
                                row['status']='scored'
                    except Exception as error:
                        row['status']='analysis_failed';row['failure']=type(error).__name__+': '+str(error)
                    row['log_sha256']=sha(logfile)
                write(receipt,row)
                print(case['id'],policy['id'],event['serial'],row['status'],flush=True)
                completed+=1;gc.collect()
                if limit is not None and completed>=limit:return


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['freeze','run'])
    parser.add_argument('--study',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--limit',type=int)
    parser.add_argument('--offline-exports',type=Path,help='Sealed offline export batch to score')
    args=parser.parse_args()
    if args.command=='freeze':
        if args.study is None:parser.error('--study required')
        freeze(args.study.resolve(),args.output.resolve(),args.offline_exports.resolve() if args.offline_exports else None)
    else:run(args.output.resolve(),args.limit)
