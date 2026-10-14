#!/usr/bin/env python3
"""Append a scored-pilot evidence tranche without changing the initial archive."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import gzip
import hashlib
import json
from pathlib import Path
import shutil

def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1048576), b''):
            digest.update(chunk)
    return digest.hexdigest()


def archive(root, output):
    initial = output.parent / 'artifact-manifest.json'
    initial_sha = sha(initial)
    analysis = root / 'checkpoint-analysis-v1'
    pilot = analysis / 'oasis-11-to-12/selected-control'
    receipts = sorted(pilot.glob('event-*.json'))
    rows = [json.loads(p.read_text()) for p in receipts]
    statuses = Counter(row['status'] for row in rows)
    if len(rows) != 30 or statuses != {'scored': 13, 'not_captured': 17}:
        raise ValueError('Expected the completed, independently scored neutrality pilot')
    if output.exists():
        raise FileExistsError(output)
    output.mkdir(parents=True)
    files = list(pilot.rglob('*'))
    files += [analysis / 'protocol.json'] + list(analysis.glob('source-*'))
    files += list((root / 'pilot-figures-v1').glob('*'))
    files += [root / 'pilot-candidate-changes.json', root / 'pilot-candidate-changes.log']
    inventory = []
    for path in sorted(set(files)):
        if not path.is_file():
            continue
        relative = path.relative_to(root)
        item = {'path': str(relative), 'bytes': path.stat().st_size, 'sha256': sha(path)}
        target = output / relative
        if path.is_symlink():
            item['retained_locally'] = True
            item['original_input'] = str(path.resolve())
        elif path.suffix in ['.json', '.txt', '.py', '.png', '.svg']:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
            item['archived_path'] = str(relative)
        elif path.suffix == '.log':
            target = target.with_suffix('.log.gz')
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open('xb') as stream, gzip.GzipFile(fileobj=stream, mode='wb', mtime=0, filename='') as compressed:
                compressed.write(path.read_bytes())
            item['archived_path'] = str(target.relative_to(output))
            item['archived_sha256'] = sha(target)
        else:
            item['retained_locally'] = True
        inventory.append(item)
    for name in ['compare_candidates.py', 'plot_trajectories.py', Path(__file__).name]:
        path = Path(__file__).parent / name
        target = output / 'sources' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
        inventory.append({'path': str(path), 'bytes': path.stat().st_size, 'sha256': sha(path),
                          'archived_path': str(target.relative_to(output))})
    manifest = {
        'schema': 'hfq-diagnostic-pilot-scoring-evidence-v1',
        'created_at': datetime.now(timezone.utc).isoformat(),
        'initial_archive_manifest_sha256': initial_sha,
        'initial_archive_unchanged': True,
        'events': len(rows), 'statuses': dict(statuses),
        'scope': 'One completed diagnostic case/policy; remaining nine replays are not included',
        'qualification_claim': False, 'files': inventory,
    }
    with (output / 'artifact-manifest.json').open('x') as stream:
        json.dump(manifest, stream, indent=2)
        stream.write('\n')
    for item in inventory:
        if 'archived_path' not in item:
            continue
        path = output / item['archived_path']
        if sha(path) != item.get('archived_sha256', item['sha256']):
            raise ValueError('Archive identity mismatch: ' + str(path))
        if 'archived_sha256' in item:
            if hashlib.sha256(gzip.decompress(path.read_bytes())).hexdigest() != item['sha256']:
                raise ValueError('Compressed log mismatch: ' + str(path))
    if sha(initial) != initial_sha:
        raise ValueError('Initial archive changed')
    print(json.dumps({'events': len(rows), 'statuses': dict(statuses), 'files': len(inventory),
                      'archived_files': sum('archived_path' in item for item in inventory),
                      'initial_archive_unchanged': True}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    archive(args.root.resolve(), args.output.resolve())
