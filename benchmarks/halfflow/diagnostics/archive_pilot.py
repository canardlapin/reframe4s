#!/usr/bin/env python3
"""Archive the completed neutrality pilot without implying panel completion.

The six-file numerical source delta is verified against P01's frozen source.
Large image/map/class binaries remain local and are represented by identities.
"""
import argparse
from datetime import datetime, timezone
import difflib
import gzip
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

from trajectory_study import ROOT, read, write, sha, verify, require_control_replay


def archive(study,output):
    if output.exists():raise FileExistsError(output)
    protocol,source=verify(study)
    root=study.parent
    pilot=study/'oasis-11-to-12/selected-control'
    receipt=read(pilot.parent/'selected-control-fit-receipt.json')
    require_control_replay(receipt)
    artifact_check=read(pilot.parent/'selected-control-checkpoint-verification.json')
    if not artifact_check['pass']:raise ValueError('Artifact verification failed')
    baseline=ROOT/'benchmarks/flashalign/local-results/hfq-control-20261014'
    old=read(baseline/'source.json')
    changed=[p for p in sorted(set(old['files'])|set(source['files'])) if old['files'].get(p)!=source['files'].get(p)]
    if len(changed)!=6:raise ValueError('Expected the reviewed six-file instrumentation delta')
    output.mkdir(parents=True)
    patch=[]
    for name in changed:
        before=baseline/'frozen-source'/name;after=study/'engine/frozen-source'/name
        if not after.exists():raise ValueError('Unexpected removed source: '+name)
        a=before.read_text().splitlines(keepends=True) if before.exists() else []
        b=after.read_text().splitlines(keepends=True)
        patch.extend(difflib.unified_diff(a,b,fromfile='a/'+name if before.exists() else '/dev/null',tofile='b/'+name))
    patch_path=output/'instrumentation.patch';patch_path.write_text(''.join(patch))
    # Apply the delta to a scratch copy, leaving both frozen sources and the live worktree untouched.
    with tempfile.TemporaryDirectory(prefix='hfq-pilot-patch-',dir='/private/tmp') as directory:
        scratch=Path(directory)
        for name in changed:
            before=baseline/'frozen-source'/name
            if before.exists():
                target=scratch/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(before,target)
        subprocess.run(['git','apply',str(patch_path.resolve())],cwd=scratch,check=True,capture_output=True)
        for name in changed:
            if sha(scratch/name)!=source['files'][name]:raise ValueError('Source patch reconstruction failed: '+name)
    write(output/'source-delta.json',{
        'base_numerical_source_sha256':old['sha256'],'numerical_source_sha256':source['sha256'],
        'base_archive':'benchmarks/halfflow/qualification/control-source.tar.gz',
        'base_archive_sha256':sha(ROOT/'benchmarks/halfflow/qualification/control-source.tar.gz'),
        'patch_sha256':sha(patch_path),'changed_files':{p:source['files'][p] for p in changed},
        'scratch_reconstruction_verified':True,
        'scope':'Diagnostic API, runner capture and tests; existing optimizer arithmetic and policy preserved'})
    files=[]
    for name in ['protocol.json','source.json','inputs.json','freeze.json','compiled-classpath.json','classpath.txt']:
        files.append(study/'engine'/name)
    for name in ['diagnostic-seal.json','checkpoint-manifest.json','resume-guard-seal.json']:
        files.append(study/name)
    files += list((study/'execution-parallel-v1').glob('source-*'))+[study/'execution-parallel-v1/protocol.json']
    for suffix in ['fit-receipt.json','measurement.json','checkpoint-verification.json','fit.log']:
        files.append(pilot.parent/('selected-control-'+suffix))
    files += [p for p in pilot.rglob('*') if p.is_file()]
    files += [p for p in (root/'verification').rglob('*') if p.is_file()]
    for name in ['historical-regions-v1.json','historical-regions-source.py','region-labels.json']:
        files.append(root/name)
    files += [p for p in (root/'historical-region-figures').rglob('*') if p.is_file()]
    files.append(root/'checkpoint-analysis-v1/protocol.json')
    files += list((root/'checkpoint-analysis-v1').glob('source-*'))
    inventory=[]
    for path in sorted(set(files)):
        relative=path.relative_to(root);destination=output/relative
        item={'path':str(relative),'bytes':path.stat().st_size,'sha256':sha(path)}
        if path.suffix in ['.json','.jsonl','.txt','.sha256','.py','.png','.svg']:
            destination.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(path,destination)
            item['archived_path']=str(relative)
        elif path.suffix=='.log':
            destination=destination.with_suffix('.log.gz');destination.parent.mkdir(parents=True,exist_ok=True)
            with destination.open('xb') as raw,gzip.GzipFile(fileobj=raw,mode='wb',mtime=0,filename='') as compressed:
                compressed.write(path.read_bytes())
            item['archived_path']=str(destination.relative_to(output));item['archived_sha256']=sha(destination)
        else:item['retained_locally']=True
        inventory.append(item)
    write(output/'artifact-manifest.json',{
        'schema':'hfq-diagnostic-pilot-evidence-v1','created_at':datetime.now(timezone.utc).isoformat(),
        'source_root':str(root),'archive_source_sha256':sha(Path(__file__)),
        'pilot_fit_complete':True,'pilot_exact_replay':True,'pilot_artifact_verification_passed':True,
        'pilot_events':receipt['events'],'pilot_captured_states':receipt['saved_states'],
        'declared_case_policy_combinations':10,'completed_combinations_in_this_archive':1,
        'remaining_panel':'In progress under the separately sealed two-worker diagnostic amendment',
        'checkpoint_scoring':'Protocol and sources archived; scored trajectories are a later evidence tranche',
        'files':inventory,'qualification_claim':False})
    # Independently verify copied contents and round-trip compressed log bytes.
    for item in inventory:
        if 'archived_path' not in item:continue
        path=output/item['archived_path']
        if sha(path)!=item.get('archived_sha256',item['sha256']):raise ValueError('Archive copy mismatch')
        if 'archived_sha256' in item:
            import hashlib
            if hashlib.sha256(gzip.decompress(path.read_bytes())).hexdigest()!=item['sha256']:raise ValueError('Compressed log mismatch')
    print(json.dumps({'files':len(inventory),'archived_files':sum('archived_path' in r for r in inventory),
                      'source_delta_files':len(changed),'pilot_passed':True,'qualification_claim':False}))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--study',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();archive(args.study.resolve(),args.output.resolve())
