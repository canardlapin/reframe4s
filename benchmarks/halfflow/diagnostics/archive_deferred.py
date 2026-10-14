#!/usr/bin/env python3
"""Archive deferred-capture neutrality and the committed partial trajectory."""
import argparse
from datetime import datetime, timezone
import gzip
import hashlib
from pathlib import Path
import shutil
from archive_objective_probe import read, write, sha, source_delta


def archive(root, output):
    if output.exists():
        raise FileExistsError(output)
    replay=root/'trajectories-deferred-v2'
    verdict=read(replay/'neutrality-verification.json')
    if not verdict['pass_all']:
        raise ValueError('Neutrality and offline byte parity must pass before publication')
    output.mkdir(parents=True)
    sources={}
    files=[]
    for name in ['trajectories-deferred-v2','offline-export-engine-v2']:
        study=root/name;target=output/name;target.mkdir()
        sources[name]=source_delta(root/'trajectories-v1/engine',study/'engine',target)
        files += [p for p in study.iterdir() if p.is_file() and p.name != 'panel-driver.log']
        files += [p for p in (study/'engine').iterdir() if p.is_file()]
    pilot=replay/'oasis-11-to-12'
    files += [p for p in (pilot/'selected-control').rglob('*') if p.is_file()]
    files += [p for p in pilot.glob('selected-control-*') if p.is_file()]
    for folder in ['deferred-export-build-v1','trajectories-deferred-v1','offline-parity-v1',
                   'offline-parity-v2','partial-step-scaled-figures-v2']:
        files += [p for p in (root/folder).rglob('*') if p.is_file()]
    files.append(root/'partial-step-scaled-figures-v2.log')
    analysis=root/'checkpoint-analysis-v1'
    files += [p for p in analysis.iterdir() if p.is_file()]
    partial=analysis/'oasis-11-to-12/step-scaled-fine1.5'
    receipts=list(partial.glob('event-*.json'))
    if len(receipts)!=42 or sum(read(p)['status']=='scored' for p in receipts)!=14:
        raise ValueError('Expected the complete committed partial OASIS step-scaled trajectory')
    files += [p for p in partial.rglob('*') if p.is_file() and not p.is_symlink()]
    inventory=[]
    for path in sorted(set(files)):
        relative=path.relative_to(root);target=output/relative
        entry=dict(path=str(relative),bytes=path.stat().st_size,sha256=sha(path))
        if path.suffix in ['.json','.jsonl','.txt','.py','.png','.svg']:
            target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(path,target)
            entry['archived_path']=str(relative)
        elif path.suffix=='.log':
            target=target.with_suffix('.log.gz');target.parent.mkdir(parents=True,exist_ok=True)
            with target.open('xb') as raw, gzip.GzipFile(filename='',fileobj=raw,mode='wb',mtime=0) as compressed:
                compressed.write(path.read_bytes())
            entry.update(archived_path=str(target.relative_to(output)),archived_sha256=sha(target))
        else:
            entry['retained_locally']=True
        inventory.append(entry)
    for name in ['archive_deferred.py','verify_deferred.py','trajectory_study.py','offline_checkpoints.py','export_engine.py','plot_trajectories.py']:
        path=Path(__file__).parent/name;target=output/name;shutil.copyfile(path,target)
        inventory.append(dict(path=str(path),bytes=path.stat().st_size,sha256=sha(path),archived_path=name))
    write(output/'artifact-manifest.json',dict(schema='hfq-deferred-diagnostic-evidence-v1',
        created_at=datetime.now(timezone.utc).isoformat(),source_root=str(root),files=inventory,
        source_delta_file_counts=sources,neutrality_pass=True,
        original_export_parity_attempt='Failed exact parity; retained without changing its verdict',
        corrected_export_parity_attempt='Both checkpoints pass exact equality after restoring cached affine pair',
        partial_trajectory='Fourteen committed scored states; source fit timed out and remains failed',
        full_panel_complete=False,qualification_claim=False,runtime_comparison_allowed=False))
    for entry in inventory:
        if 'archived_path' not in entry:continue
        path=output/entry['archived_path']
        if sha(path)!=entry.get('archived_sha256',entry['sha256']):raise ValueError('Archive hash mismatch')
        if 'archived_sha256' in entry and hashlib.sha256(gzip.decompress(path.read_bytes())).hexdigest()!=entry['sha256']:
            raise ValueError('Compressed log changed')
    print(dict(inventoried=len(inventory),archived=sum('archived_path' in r for r in inventory),source_deltas=sources),flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();archive(a.root.resolve(),a.output.resolve())
