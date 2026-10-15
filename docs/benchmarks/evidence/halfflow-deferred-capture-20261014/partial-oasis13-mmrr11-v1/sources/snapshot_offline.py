#!/usr/bin/env python3
"""Archive a verified partial offline ledger without treating it as completed."""
import argparse
from datetime import datetime,timezone
import gzip
from pathlib import Path
import shutil

from verify_offline_ledger import inspect
from plot_trajectories import plot_case
from trajectory_study import read,write,sha


def snapshot(analysis,output):
    if output.exists():raise FileExistsError('Fresh snapshot directory required')
    ledger=inspect(analysis)
    if ledger['ledger_complete']:raise ValueError('Use the completed-batch archiver for a completed batch')
    protocol=read(analysis/'protocol.json');study=Path(protocol['trajectory_study'])
    exports=Path(protocol['offline_exports']['directory'])
    files={analysis/'protocol.json':'analysis/protocol.json',exports/'protocol.json':'exports/protocol.json'}
    states=[]
    for name,digest in ledger['analysis_receipt_sha256'].items():
        path=analysis/name
        if sha(path)!=digest:raise ValueError('Verified event changed before snapshot')
        files[path]='analysis/'+name;row=read(path)
        if row['event']['directory'] is not None:
            export=exports/row['pair']/row['policy']/f"event-{row['event']['serial']:04d}-receipt.json"
            files[export]='exports/'+str(export.relative_to(exports))
            directory=path.parent/row['event']['directory']
            if directory.exists():
                for report in directory.iterdir():
                    if report.is_file() and not report.is_symlink() and report.suffix in ['.json','.txt']:
                        files[report]='analysis/'+str(report.relative_to(analysis))
        if row['status']=='scored':
            a=row['geometry_and_anatomy'];e=row['event']['info']
            states.append(dict(pair=row['pair'],policy=row['policy'],event=row['event']['serial'],
                kind=e['kind'],level=e['levelIndex'],accepted_steps=e['acceptedSteps'],
                rebuilt_loss=e['after']['loss'],fixed_intensity_loss=row['fixed_intensity']['mean_bidirectional_loss'],
                mean_dice=a['mean_dice'],mean_hd95_mm=a['mean_hd95_mm'],
                native_export_accepted=row['native_export_accepted'],geometry_pass=a['geometry_pass'],cells_pass=a['cells_pass']))
    for fit in ledger['source_fit_ledger']:
        base=study/fit['pair']/fit['policy']
        for path in [base.parent/(fit['policy']+'-fit-receipt.json'),base/'checkpoints/events.jsonl']:
            files[path]='captures/'+str(path.relative_to(study))
    output.mkdir(parents=True)
    write(output/'ledger-verification.json',ledger)
    for path,name in sorted(files.items()):
        target=output/name
        if path.suffix=='.jsonl':target=target.with_suffix('.jsonl.gz')
        target.parent.mkdir(parents=True,exist_ok=True)
        if path.suffix=='.jsonl':
            with target.open('xb') as stream,gzip.GzipFile(fileobj=stream,filename='',mtime=0,mode='wb') as compressed:
                compressed.write(path.read_bytes())
        else:shutil.copyfile(path,target)
    for state in sorted({(s['pair'],s['policy']) for s in states}):
        plot_case(study,output/'analysis',*state,output/'figures'/state[0]/state[1])
    for name in ['snapshot_offline.py','verify_offline_ledger.py','plot_trajectories.py']:
        path=Path(__file__).with_name(name);target=output/'sources'/name
        target.parent.mkdir(exist_ok=True);shutil.copyfile(path,target)
    write(output/'scientific-summary.json',dict(states=sorted(states,key=lambda s:(s['pair'],s['policy'],s['event'])),
        partial=True,complete_batch_claim=False,qualification_claim=False,runtime_checkpoint_selected=False))
    inventory=[dict(path=str(p.relative_to(output)),bytes=p.stat().st_size,sha256=sha(p))
        for p in sorted(output.rglob('*')) if p.is_file()]
    write(output/'artifact-manifest.json',dict(schema='hfq-partial-offline-snapshot-v1',created_at=datetime.now(timezone.utc).isoformat(),
        files=inventory,recorded_events=ledger['recorded_event_count'],expected_events=ledger['expected_event_count'],
        statuses=ledger['statuses'],complete_batch_claim=False,qualification_claim=False,runtime_comparison_allowed=False,
        mri_or_dense_map_payloads=False,scope='All events verified in this partial snapshot, every scored scientific report and per-structure plot. '
            'Missing events remain explicit. Running worker logs/maps are not copied; no claim that the batch is complete.'))
    for item in inventory:
        if sha(output/item['path'])!=item['sha256']:raise ValueError('Snapshot archive changed')
    print('SNAPSHOT',ledger['recorded_event_count'],'of',ledger['expected_event_count'],'events;',ledger['statuses'],flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--analysis',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True);args=parser.parse_args();snapshot(args.analysis.resolve(),args.output.resolve())
