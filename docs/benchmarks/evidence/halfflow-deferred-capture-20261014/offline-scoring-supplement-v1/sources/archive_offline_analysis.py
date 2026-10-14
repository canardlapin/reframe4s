#!/usr/bin/env python3
"""Archive completed scoring parity and immutable first-batch declarations."""
import argparse
from datetime import datetime, timezone
import gzip
import hashlib
from pathlib import Path
import shutil

from offline_analysis import read, require, sha
from trajectory_study import write


def archive(root, output):
    require(not output.exists(), 'Archive already exists')
    initial = output.parent/'artifact-manifest.json'
    initial_sha = sha(initial)
    pilot = root/'offline-analysis-parity-v1'
    verification = read(pilot/'parity-verification.json')
    require(verification['passed'], 'Completed scoring parity required')
    checks = root/'offline-analysis-checks-v1'
    require('Ran 11 tests' in (checks/'tests.log').read_text() and '\nOK\n' in (checks/'tests.log').read_text(),
            'Expected eleven provenance/failure tests')
    for path, digest in read(pilot/'protocol.json')['sources'].items():
        require(sha(path) == digest, 'Analysis source changed since parity run')
    batch = root/'offline-panel-oasis11-v1'
    protocol = read(batch/'protocol.json')
    require(len(protocol['tasks']) == 29 and len(protocol['selected_fits']) == 2,
            'Expected complete OASIS 11 control and step-scaled export declaration')
    require(protocol['driver_sha256'] == sha(Path(__file__).parent/'offline_checkpoints.py'),
            'Export driver changed after batch declaration')
    files = list(pilot.rglob('*')) + list(checks.iterdir())
    files += [batch/'protocol.json',batch/'source-offline_checkpoints.py']
    analysis = root/'offline-analysis-oasis11-v1'
    files += [analysis/'protocol.json'] + list(analysis.glob('source-*'))
    captures = Path(protocol['captures'])
    for fit in protocol['selected_fits']:
        base = captures/fit['pair']/fit['policy']
        files += [base.parent/(fit['policy']+'-fit-receipt.json'),base/'checkpoints/events.jsonl',
                  base/'mode.txt',base/'plan.txt']
        files += list((base/'checkpoints').glob('*/checkpoint.json'))
    output.mkdir(parents=True)
    inventory = []
    for path in sorted(set(files)):
        if not path.is_file():
            continue
        relative = path.relative_to(root)
        item = dict(path=str(relative),bytes=path.stat().st_size,sha256=sha(path))
        if path.is_symlink():
            item.update(retained_locally=True,original_input=str(path.resolve()))
        elif path.suffix in ['.json','.jsonl','.txt','.py','.log']:
            target = output/relative
            if path.suffix == '.log':
                target = target.with_suffix('.log.gz')
            target.parent.mkdir(parents=True,exist_ok=True)
            if path.suffix == '.log':
                with target.open('xb') as stream, gzip.GzipFile(fileobj=stream,mode='wb',mtime=0,filename='') as compressed:
                    compressed.write(path.read_bytes())
                item['archived_sha256'] = sha(target)
            else:
                shutil.copyfile(path,target)
            item['archived_path'] = str(target.relative_to(output))
        else:
            item['retained_locally'] = True
        inventory.append(item)
    for name in ['checkpoint_analysis.py','offline_analysis.py','test_offline_analysis.py',
                 'offline_checkpoints.py','verify_offline_analysis.py',Path(__file__).name]:
        path = Path(__file__).parent/name
        target = output/'sources'/name
        target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(path,target)
        inventory.append(dict(path=str(path),bytes=path.stat().st_size,sha256=sha(path),
                              archived_path=str(target.relative_to(output))))
    manifest = dict(schema='hfq-offline-scoring-evidence-v1',created_at=datetime.now(timezone.utc).isoformat(),
        initial_archive_manifest_sha256=initial_sha,initial_archive_unchanged=True,
        parity_pass=True,parity_scored_states=2,parity_event_statuses=verification['event_statuses'],
        byte_identical_scientific_reports=6,provenance_tests_passed=11,
        declared_next_export_tasks=29,declared_next_fits=protocol['selected_fits'],
        scope='Completed two-state scoring parity plus the immutable first 29-state export/scoring declarations. No claim that the running batch or ten-fit trajectory panel is complete.',
        qualification_claim=False,runtime_comparison_allowed=False,files=inventory)
    write(output/'artifact-manifest.json',manifest)
    for item in inventory:
        if 'archived_path' in item:
            target = output/item['archived_path']
            require(sha(target) == item.get('archived_sha256',item['sha256']), 'Archive digest mismatch')
            if 'archived_sha256' in item:
                require(hashlib.sha256(gzip.decompress(target.read_bytes())).hexdigest() == item['sha256'],
                        'Compressed evidence differs')
    require(sha(initial) == initial_sha, 'Initial archive changed')
    print('ARCHIVED',len(inventory),'identities;',sum('archived_path' in item for item in inventory),'compact files')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    args = p.parse_args()
    archive(args.root.resolve(),args.output.resolve())
