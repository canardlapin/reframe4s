#!/usr/bin/env python3
"""Preserve compact P02 evidence and identities of retained large artifacts."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import shutil


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def archive(source, output):
    if output.exists():
        raise FileExistsError(output)
    studies = ['historical-audit', 'historical-audit-v2',
               'budget-study', 'budget-study-v2', 'budget-study-v3', 'inverse-cleanup-v1', 'matched-budget-v1']
    # Verify historical originals, not just the newly generated reports.
    historical = json.loads((source / 'historical-audit-v2/protocol.json').read_text())
    for name, digest in historical['inputs'].items():
        if sha(Path(name)) != digest:
            raise ValueError('Historical input changed: ' + name)
    budget = json.loads((source / 'budget-study-v3/protocol.json').read_text())
    for group in ['sources', 'inputs']:
        for name, digest in budget[group].items():
            if sha(Path(name)) != digest:
                raise ValueError('Budget study identity changed: ' + name)
    rows = json.loads((source / 'budget-study-v3/results.json').read_text())['rows']
    expected = {(c['id'], lane['id']) for c in budget['cases'] for lane in budget['lanes']}
    actual = {(r['pair'], r['lane']) for r in rows}
    if actual != expected or len(rows) != len(expected):
        raise ValueError('Every declared attempt must have exactly one receipt')
    if any(row['protocol_sha256'] != sha(source/'budget-study-v3/protocol.json') for row in rows):
        raise ValueError('Budget receipt identity mismatch')
    if not (source/'inverse-cleanup-v1/receipt.json').exists():
        raise ValueError('The declared inverse diagnostic has not finished')
    matched=json.loads((source/'matched-budget-v1/results.json').read_text())['rows']
    expected_matched={(c['id'],'syn-matched') for c in budget['cases']}
    if len(matched)!=len(expected_matched) or {(r['pair'],r['lane']) for r in matched}!=expected_matched:
        raise ValueError('The comparable-budget attempt ledger is incomplete')
    extra_path = source/'matched-budget-v1/protocol.json'
    extra = json.loads(extra_path.read_text())
    if extra['inputs'] != budget['inputs'] or extra['cases'] != budget['cases']:
        raise ValueError('Matched-budget inputs or cases changed')
    for name, digest in extra['sources'].items():
        if sha(Path(name)) != digest:
            raise ValueError('Matched-budget source identity changed: ' + name)
    for row in matched:
        child_path = source/'matched-budget-v1'/('run-'+row['pair'])/'protocol.json'
        child = json.loads(child_path.read_text())
        if sha(child_path) != row['protocol_sha256'] or child['parent_matched_protocol_sha256'] != sha(extra_path):
            raise ValueError('Matched-budget receipt identity mismatch')
    inverse_path = source/'inverse-cleanup-v1/protocol.json'
    inverse = json.loads(inverse_path.read_text())
    cleanup = json.loads((source/'inverse-cleanup-v1/receipt.json').read_text())
    if cleanup['protocol_sha256'] != sha(inverse_path) or cleanup['driver_seal_sha256'] != sha(source/'inverse-cleanup-v1/driver-seal.json'):
        raise ValueError('Inverse diagnostic receipt identity mismatch')
    for name, digest in inverse['sources'].items():
        if sha(Path(name)) != digest:
            raise ValueError('Inverse diagnostic source identity changed: ' + name)
    output.mkdir(parents=True)
    files = [p for study in studies for p in (source / study).rglob('*')
             if p.is_file() and 'matplotlib-cache' not in p.parts]
    files += list(source.glob('native-inverse-probe*.json'))
    files += list(source.glob('verification*'))
    files += list(source.glob('analysis-*'))
    inventory = []
    for path in sorted(files):
        relative = path.relative_to(source)
        entry = {'path': str(relative), 'bytes': path.stat().st_size, 'sha256': sha(path)}
        destination = output / relative
        if path.suffix in ['.json', '.txt', '.py', '.png', '.svg']:
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, destination)
            entry['archived_path'] = str(relative)
        elif path.suffix == '.log':
            destination = destination.with_suffix('.log.gz')
            destination.parent.mkdir(parents=True, exist_ok=True)
            with destination.open('xb') as raw, gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0) as compressed:
                compressed.write(path.read_bytes())
            entry['archived_path'] = str(destination.relative_to(output))
            entry['archived_sha256'] = sha(destination)
        else:
            entry['retained_locally'] = True
        inventory.append(entry)
    manifest = {
        'schema': 'hfq-reference-evidence-v1', 'source_root': str(source.resolve()),
        'archive_source_sha256': sha(Path(__file__)),
        'historical_inputs_verified_unchanged': len(historical['inputs']),
        'budget_sources_and_inputs_verified_unchanged': sum(len(budget[k]) for k in ['sources','inputs']),
        'budget_attempt_count': len(rows), 'files': inventory,
        'additional_matched_budget_attempt_count':len(matched),
        'qualification_claim': False,
    }
    (output / 'artifact-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps({'files': len(inventory), 'archived': sum('archived_path' in f for f in inventory),
                      'budget_attempts': len(rows), 'historical_inputs_unchanged': len(historical['inputs'])}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    archive(args.source, args.output)
