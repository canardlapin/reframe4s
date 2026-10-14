#!/usr/bin/env python3
"""Prospective replication of the single geometry-valid reference diagnostic."""
import argparse
from datetime import datetime, timezone
import gzip
import json
from pathlib import Path
import shutil

from budget_study import ROOT, HERE, run
from real_image_court import sha
from summarize_study import summarize


def write(path, value):
    with path.open('x') as stream:
        json.dump(value, stream, indent=2)
        stream.write('\n')


def freeze(study, pilot, baseline):
    if study.exists():
        raise FileExistsError(study)
    prior = summarize(pilot)
    p = json.loads((pilot/'protocol.json').read_text())
    rows = json.loads((pilot/'results.json').read_text())['rows']
    winner = next(r for r in rows if r['lane'] == 'syn-total0.5')
    stop = prior['stopping'][winner['pair']+'/'+winner['lane']]
    if not (winner['audit_completed'] and winner['geometry_pass'] and winner['cells_pass']
            and stop['all_enabled_levels_stop_by_native_rule']):
        raise ValueError('The fixed regularity pilot must converge and pass geometry')
    base = json.loads(baseline.read_text())
    cases = [c for c in base['cases'] if c['id'] in ['oasis-13-to-14', 'oasis-15-to-16']]
    if {c['id'] for c in cases} != {'oasis-13-to-14', 'oasis-15-to-16'}:
        raise ValueError('Missing predeclared observed replication pair')
    sources = list(HERE.glob('*.py')) + [ROOT/'benchmarks/flashalign/real_image_court.py',
                                       ROOT/'benchmarks/flashalign/real_mri_cell_topology.py']
    inputs = {}
    for case in cases:
        paths = [Path(case[k]) for k in ['saved_affine', 'moving_labels', 'fixed_labels']]
        paths += list(Path(case['data']).glob('*.nii.gz'))
        for path in paths:
            inputs[str(path)] = sha(path)
            if inputs[str(path)] != base['inputs'][str(path)]:
                raise ValueError('Observed input identity changed: '+str(path))
    policy = dict(p, schema='hfq-p02-regularized-reference-replication-v1',
        created_at=datetime.now(timezone.utc).isoformat(), cases=cases,
        lanes=[next(l for l in p['lanes'] if l['id'] == 'syn-total0.5')],
        inputs=inputs, sources={str(path):sha(path) for path in sources},
        pilot_protocol_sha256=sha(pilot/'protocol.json'),
        pilot_results_sha256=sha(pilot/'results.json'),
        baseline_protocol_sha256=sha(baseline),
        purpose='Replicate a fixed native total_sigma=0.5 setting on the other two observed budget-study pairs',
        selection='Geometry and convergence motivated the fixed setting. No new parameter variants; both cases and all failures retained. Pilot anatomy was already observed.',
        success_rule='Both new cases must converge at all levels before caps and pass every unchanged common geometry gate; otherwise this replication fails. Three observed cases alone cannot qualify a strong reference.',
        concurrency='May overlap other diagnostic work; all timings excluded from production comparisons',
        follow_up='Retain every result before deciding any wider observed-data or timed reference validation')
    study.mkdir(parents=True)
    for path in sources:
        shutil.copyfile(path, study/('source-'+path.name))
    write(study/'protocol.json', policy)
    print('FROZEN two observed pairs, one unchanged reference setting', flush=True)


def archive(study, output):
    if output.exists():
        raise FileExistsError(output)
    summary = summarize(study)  # Enforces the complete attempt ledger and receipt identities.
    protocol = json.loads((study/'protocol.json').read_text())
    for path, digest in protocol['inputs'].items():
        if sha(Path(path)) != digest:
            raise ValueError('Input changed: '+path)
    for path, digest in protocol['sources'].items():
        if sha(study/('source-'+Path(path).name)) != digest:
            raise ValueError('Frozen source changed: '+path)
    output.mkdir(parents=True)
    inventory = []
    for path in sorted(study.rglob('*')):
        if not path.is_file() or 'matplotlib-cache' in path.parts:
            continue
        relative = path.relative_to(study)
        entry = dict(path=str(relative), bytes=path.stat().st_size, sha256=sha(path))
        destination = output/relative
        if path.suffix in ['.json', '.txt', '.py']:
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, destination)
            entry['archived_path'] = str(relative)
        elif path.suffix == '.log':
            destination = destination.with_suffix('.log.gz')
            destination.parent.mkdir(parents=True, exist_ok=True)
            with destination.open('xb') as raw, gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0) as compressed:
                compressed.write(path.read_bytes())
            entry.update(archived_path=str(destination.relative_to(output)), archived_sha256=sha(destination))
        else:
            entry['retained_locally'] = True
        inventory.append(entry)
    # summarize() includes cost fields for legacy reports; explicitly prevent
    # these concurrent diagnostic measurements becoming a timing comparison.
    summary.update(runtime_comparison_allowed=False, qualification_claim=False,
                   timing_scope='Concurrent diagnostic measurements only')
    write(output/'summary.json', summary)
    write(output/'artifact-manifest.json', dict(schema='hfq-reference-supplement-v1',
        source_root=str(study.resolve()), files=inventory,
        archive_source_sha256=sha(Path(__file__)), summary_sha256=sha(output/'summary.json'),
        qualification_claim=False))
    for entry in inventory:
        if 'archived_path' in entry:
            saved = output/entry['archived_path']
            if sha(saved) != entry.get('archived_sha256', entry['sha256']):
                raise ValueError('Archive copy failed')
            if saved.suffix == '.gz' and gzip.decompress(saved.read_bytes()) != (study/entry['path']).read_bytes():
                raise ValueError('Compressed log changed')
    print(json.dumps(dict(inventoried=len(inventory), archived=sum('archived_path' in r for r in inventory))))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['freeze', 'run', 'archive'])
    parser.add_argument('--study', type=Path, required=True)
    parser.add_argument('--pilot', type=Path)
    parser.add_argument('--baseline', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.command == 'freeze':
        if args.pilot is None or args.baseline is None:
            parser.error('freeze requires --pilot and --baseline')
        freeze(args.study.resolve(), args.pilot.resolve(), args.baseline.resolve())
    elif args.command == 'archive':
        if args.output is None:
            parser.error('archive requires --output')
        archive(args.study.resolve(), args.output.resolve())
    else:
        run(args.study.resolve())
