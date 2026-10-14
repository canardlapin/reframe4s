#!/usr/bin/env python3
"""Archive the derivative pilot and the complete failed/cancelled panel ledger."""
import argparse
from datetime import datetime, timezone
import difflib
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1048576), b''):
            h.update(chunk)
    return h.hexdigest()


def read(path):
    return json.loads(path.read_text())


def write(path, value):
    with path.open('x') as stream:
        json.dump(value, stream, indent=2)
        stream.write('\n')


def source_delta(base, variant, output):
    old, new = read(base/'source.json'), read(variant/'source.json')
    changed = [p for p in sorted(set(old['files']) | set(new['files']))
               if old['files'].get(p) != new['files'].get(p)]
    lines = []
    for name in changed:
        before, after = base/'frozen-source'/name, variant/'frozen-source'/name
        if not after.exists():
            raise ValueError('Unexpected source removal')
        a = before.read_text().splitlines(keepends=True) if before.exists() else []
        lines.extend(difflib.unified_diff(a, after.read_text().splitlines(keepends=True),
            fromfile='a/'+name if before.exists() else '/dev/null', tofile='b/'+name))
    patch = output/'source.patch'
    patch.write_text(''.join(lines))
    with tempfile.TemporaryDirectory(prefix='hfq-objective-patch-', dir='/private/tmp') as directory:
        scratch = Path(directory)
        for name in changed:
            before = base/'frozen-source'/name
            if before.exists():
                target = scratch/name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(before, target)
        subprocess.run(['git', 'apply', str(patch)], cwd=scratch, check=True, capture_output=True)
        for name in changed:
            if sha(scratch/name) != new['files'][name]:
                raise ValueError('Patch reconstruction failed: '+name)
    write(output/'source-delta.json', {'base_source_sha256': old['sha256'],
        'source_sha256': new['sha256'], 'patch_sha256': sha(patch),
        'base_reproduction': 'P01 control-source.tar.gz plus halfflow-diagnostics-pilot-20261014/instrumentation.patch',
        'changed_files': {p: new['files'][p] for p in changed}, 'scratch_reconstruction_verified': True})
    return len(changed)


def archive(root, output):
    if output.exists():
        raise FileExistsError(output)
    panel = root/'trajectories-v1'
    receipts = sorted(panel.glob('*/*-fit-receipt.json'))
    ledger = [read(p) for p in receipts]
    counts = {str(code): sum(r['exit_code'] == code for r in ledger) for code in [0, 124, -15]}
    if len(ledger) != 10 or counts != {'0': 1, '124': 2, '-15': 7}:
        raise ValueError('Expected the explicitly closed ten-attempt diagnostic panel')
    if read(root/'objective-probe-pilot-v1/receipt.json')['exit_code'] != 1:
        raise ValueError('Missing preserved loader failure')
    final = read(root/'objective-probe-pilot-v2/receipt.json')
    if final['exit_code'] != 0 or final['gradient_criterion_pass']:
        raise ValueError('Unexpected completed derivative-pilot outcome')
    output.mkdir(parents=True)
    files = []
    deltas = {}
    for name in ['objective-probe-pilot-v1', 'objective-probe-pilot-v2']:
        variant = root/name
        target = output/name
        target.mkdir()
        deltas[name] = source_delta(panel/'engine', variant/'engine', target)
        for p in variant.iterdir():
            if p.is_file(): files.append(p)
        files += [p for p in (variant/'engine').iterdir() if p.is_file()]
        files += [p for p in (variant/'audit').glob('*') if p.is_file()]
    for folder in ['objective-probe-build-v1', 'objective-probe-figures-v2']:
        files += [p for p in (root/folder).iterdir() if p.is_file()]
    for name in ['cancel_remaining.py', 'cancellation-plan-v1.json', 'cancellation-receipt-v1.json', 'driver.log']:
        files.append(panel/'execution-parallel-v1'/name)
    files += receipts
    files += list(panel.glob('*/*-measurement.json')) + list(panel.glob('*/*-fit.log'))
    for base in panel.glob('*/*/checkpoints'):
        if base.parent.parent.name == 'oasis-11-to-12' and base.parent.name == 'selected-control':
            continue  # Its complete immutable capture archive is already published.
        files += [p for p in base.rglob('*') if p.is_file()]
    inventory = []
    for path in sorted(set(files)):
        relative = path.relative_to(root)
        target = output/relative
        item = {'path': str(relative), 'bytes': path.stat().st_size, 'sha256': sha(path)}
        if path.suffix in ['.json', '.jsonl', '.txt', '.py', '.png', '.svg']:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
            item['archived_path'] = str(relative)
        elif path.suffix == '.log':
            target = target.with_suffix('.log.gz')
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open('xb') as stream, gzip.GzipFile(fileobj=stream, mode='wb', mtime=0, filename='') as compressed:
                compressed.write(path.read_bytes())
            item.update(archived_path=str(target.relative_to(output)), archived_sha256=sha(target))
        else:
            item['retained_locally'] = True
        inventory.append(item)
    for name in [Path(__file__).name, 'plot_objective_probe.py']:
        path = Path(__file__).parent/name
        target = output/name
        shutil.copyfile(path, target)
        inventory.append({'path': str(path), 'bytes': path.stat().st_size,
                          'sha256': sha(path), 'archived_path': name})
    write(output/'artifact-manifest.json', {'schema': 'hfq-objective-probe-evidence-v1',
        'created_at': datetime.now(timezone.utc).isoformat(), 'source_root': str(root),
        'panel_attempts': 10, 'panel_exit_counts': counts, 'source_delta_file_counts': deltas,
        'gradient_probe_complete': True, 'gradient_criterion_pass': False,
        'partial_panel_scoring': 'Running separately; this archive does not claim those analyses complete',
        'qualification_claim': False, 'files': inventory})
    for item in inventory:
        if 'archived_path' not in item: continue
        path = output/item['archived_path']
        if sha(path) != item.get('archived_sha256', item['sha256']):
            raise ValueError('Archive copy mismatch')
        if 'archived_sha256' in item:
            if hashlib.sha256(gzip.decompress(path.read_bytes())).hexdigest() != item['sha256']:
                raise ValueError('Compressed log mismatch')
    print(json.dumps({'inventoried': len(inventory), 'archived': sum('archived_path' in r for r in inventory),
        'source_deltas_verified': deltas, 'panel_exit_counts': counts, 'gradient_criterion_pass': False}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    archive(args.root.resolve(), args.output.resolve())
