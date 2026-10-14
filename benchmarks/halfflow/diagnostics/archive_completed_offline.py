#!/usr/bin/env python3
"""Verify and archive every event/report in a terminal offline checkpoint batch."""
import argparse
from datetime import datetime, timezone
import gzip
import hashlib
import json
from pathlib import Path
import shutil

from verify_offline_ledger import inspect
from plot_trajectories import plot_case
from trajectory_study import read, write, sha


def archive(analysis, output):
    if output.exists(): raise FileExistsError(output)
    verified = inspect(analysis)
    if not verified['ledger_complete']: raise ValueError('Complete exact event/export ledger required')
    p = read(analysis/'protocol.json');study = Path(p['trajectory_study'])
    exports = Path(p['offline_exports']['directory']);root = analysis.parent
    output.mkdir(parents=True)
    write(output/'ledger-verification.json',verified)
    files = list(analysis.rglob('*')) + list(exports.rglob('*'))
    for fit in verified['source_fit_ledger']:
        base = study/fit['pair']/fit['policy']
        files += [base.parent/(fit['policy']+'-fit-receipt.json'),base/'checkpoints/events.jsonl',base/'plan.txt',base/'mode.txt']
        files += list((base/'checkpoints').glob('*/checkpoint.json'))
        plot_case(study,analysis,fit['pair'],fit['policy'],output/'figures'/fit['pair']/fit['policy'])
    inventory = []
    for path in sorted(set(files)):
        if not path.is_file() or path.is_symlink() or path.suffix not in ['.json','.jsonl','.txt','.py','.log']: continue
        relative = path.relative_to(root);target = output/relative
        if path.suffix in ['.log','.jsonl']: target = target.with_name(target.name+'.gz')
        target.parent.mkdir(parents=True,exist_ok=True)
        if path.suffix in ['.log','.jsonl']:
            with target.open('xb') as stream,gzip.GzipFile(fileobj=stream,mode='wb',filename='',mtime=0) as compressed:
                compressed.write(path.read_bytes())
        else:shutil.copyfile(path,target)
        inventory.append(dict(path=str(relative),bytes=path.stat().st_size,sha256=sha(path),
            archived_path=str(target.relative_to(output)),archived_sha256=sha(target)))
    for name in ['archive_completed_offline.py','verify_offline_ledger.py','plot_trajectories.py']:
        target=output/'sources'/name;target.parent.mkdir(exist_ok=True)
        shutil.copyfile(Path(__file__).with_name(name),target)
    known={item['archived_path'] for item in inventory}
    for target in sorted(output.rglob('*')):
        if target.is_file() and str(target.relative_to(output)) not in known:
            relative=str(target.relative_to(output));inventory.append(dict(path=relative,bytes=target.stat().st_size,
                sha256=sha(target),archived_path=relative))
    for item in inventory:
        target=output/item['archived_path']
        if sha(target)!=item.get('archived_sha256',item['sha256']):raise ValueError('Archive bytes changed')
        if target.suffix=='.gz' and hashlib.sha256(gzip.decompress(target.read_bytes())).hexdigest()!=item['sha256']:
            raise ValueError('Compressed evidence differs')
    write(output/'artifact-manifest.json',dict(schema='hfq-completed-offline-batch-v1',created_at=datetime.now(timezone.utc).isoformat(),
        files=inventory,exact_event_set_complete=True,event_count=verified['recorded_event_count'],
        export_count=verified['expected_export_count'],statuses=verified['statuses'],
        qualification_claim=False,runtime_comparison_allowed=False,mri_or_dense_map_payloads=False,
        scope='Every declared event and export attempt is accounted for. All compact scalar reports and plots retained; '
            'MRI and dense-map payloads remain local, identified and verified by the event receipts. '
            'Completion is separate from convergence and anatomical benefit.'))
    print('ARCHIVED',verified['recorded_event_count'],'events;',verified['statuses'],flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--analysis',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();archive(args.analysis.resolve(),args.output.resolve())
