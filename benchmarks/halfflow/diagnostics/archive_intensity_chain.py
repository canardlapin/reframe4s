#!/usr/bin/env python3
"""Archive the acquired-image derivative-chain isolation with source recovery."""
import argparse
from datetime import datetime, timezone
import gzip
import hashlib
from pathlib import Path
import shutil
from archive_objective_probe import read, write, sha, source_delta


def archive(root, output):
    if output.exists():raise FileExistsError(output)
    study=root/'intensity-chain-pilot-v1';receipt=read(study/'receipt.json')
    if receipt['exit_code']!=0:raise ValueError('Probe must be terminal and complete')
    # Retain the measured verdicts without requiring a positive scientific result.
    output.mkdir(parents=True)
    changed=source_delta(root/'trajectories-v1/engine',study/'engine',output)
    files=[p for p in study.iterdir() if p.is_file()]
    files += [p for p in (study/'engine').iterdir() if p.is_file()]
    files += [p for p in (study/'audit').iterdir() if p.is_file()]
    for folder in ['intensity-chain-build-v1','intensity-chain-figures-v1']:
        files += [p for p in (root/folder).iterdir() if p.is_file()]
    files.append(root/'intensity-chain-plot.log')
    inventory=[]
    for path in sorted(set(files)):
        relative=path.relative_to(root);target=output/relative
        entry=dict(path=str(relative),bytes=path.stat().st_size,sha256=sha(path))
        if path.suffix in ['.json','.jsonl','.txt','.py','.png','.svg']:
            target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(path,target)
            entry['archived_path']=str(relative)
        elif path.suffix=='.log':
            target=target.with_suffix('.log.gz');target.parent.mkdir(parents=True,exist_ok=True)
            with target.open('xb') as raw,gzip.GzipFile(filename='',fileobj=raw,mode='wb',mtime=0) as compressed:
                compressed.write(path.read_bytes())
            entry.update(archived_path=str(target.relative_to(output)),archived_sha256=sha(target))
        else:entry['retained_locally']=True
        inventory.append(entry)
    for name in ['archive_intensity_chain.py','objective_probe.py','plot_intensity_chain.py']:
        path=Path(__file__).parent/name;target=output/name;shutil.copyfile(path,target)
        inventory.append(dict(path=str(path),bytes=path.stat().st_size,sha256=sha(path),archived_path=name))
    write(output/'artifact-manifest.json',dict(schema='hfq-intensity-chain-evidence-v1',
        created_at=datetime.now(timezone.utc).isoformat(),source_root=str(root),files=inventory,
        changed_source_files=changed,source_reconstruction_verified=True,
        gradient_criterion_pass=receipt['gradient_criterion_pass'],
        intensity_chain_criterion_pass=receipt['intensity_chain_criterion_pass'],
        baseline_numerics_reproduced_exactly=receipt['baseline_numerics_reproduced_exactly'],
        qualification_claim=False,runtime_comparison_allowed=False))
    for entry in inventory:
        if 'archived_path' not in entry:continue
        data=(output/entry['archived_path']).read_bytes()
        if hashlib.sha256(data).hexdigest()!=entry.get('archived_sha256',entry['sha256']):raise ValueError('Archive identity changed')
        if 'archived_sha256' in entry and hashlib.sha256(gzip.decompress(data)).hexdigest()!=entry['sha256']:raise ValueError('Compressed log changed')
    print(dict(inventoried=len(inventory),archived=sum('archived_path' in r for r in inventory),changed_source_files=changed),flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();archive(a.root.resolve(),a.output.resolve())
