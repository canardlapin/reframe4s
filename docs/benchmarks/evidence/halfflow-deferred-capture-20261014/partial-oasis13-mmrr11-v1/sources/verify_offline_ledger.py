#!/usr/bin/env python3
"""Verify exact event accounting; completion is separate from scientific success."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path

from offline_analysis import inspect_plan, read, require, resolve_export, sha


def match_events(expected, records):
    """Reject duplicate, substituted or mutated events before comparing counts."""
    actual = {}
    for record in records:
        key = record['pair'], record['policy'], record['event']['serial']
        require(key not in actual, 'Duplicate analysis event')
        require(key in expected, 'Unexpected analysis event')
        require(record['event'] == expected[key], 'Analysis event differs from committed capture')
        actual[key] = record
    return actual, sorted(set(expected)-set(actual))


def inspect(analysis):
    protocol = read(analysis/'protocol.json')
    study = Path(protocol['trajectory_study'])
    exports = Path(protocol['offline_exports']['directory'])
    export_protocol, tasks, fits = inspect_plan(study, exports)
    require(fits == protocol['offline_exports']['fit_ledger'], 'Source fit ledger changed')
    require(protocol['offline_exports']['protocol_sha256'] == sha(exports/'protocol.json'), 'Export protocol changed')
    for path, digest in protocol['sources'].items():
        require(sha(analysis/('source-'+Path(path).name)) == digest, 'Archived analysis source changed')
    validator = Path(__file__).with_name('offline_analysis.py')
    require(protocol['sources'][str(validator)] == sha(validator), 'Offline provenance validator changed')
    expected = {}
    fit_map = {(fit['pair'], fit['policy']): fit for fit in fits}
    for fit in fits:
        path = study/fit['pair']/fit['policy']/'checkpoints/events.jsonl'
        events = [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []
        for event in events:
            key = fit['pair'], fit['policy'], event['serial']
            require(key not in expected, 'Duplicate source event')
            expected[key] = event
    paths = sorted(analysis.glob('*/*/event-*.json'))
    records = [read(path) for path in paths]
    actual, missing = match_events(expected, records)
    for path, row in zip(paths, records, strict=True):
        key = row['pair'], row['policy'], row['event']['serial']
        require(path == analysis/key[0]/key[1]/f'event-{key[2]:04d}.json', 'Mislabeled event file')
        fit = fit_map[key[:2]]
        require(row['protocol_sha256'] == sha(analysis/'protocol.json'), 'Analysis protocol changed')
        require(row['fit_receipt_sha256'] == fit['fit_receipt_sha256'] and row['fit_exit_code'] == fit['exit_code'],
                'Analysis changed source fit outcome')
        if row['event']['directory'] is None:
            require(row['status'] == 'not_captured', 'Uncaptured event promoted to scored')
            continue
        if key not in tasks:
            require(export_protocol['pilot'] and row['status'] == 'export_not_requested', 'Captured state omitted')
            continue
        resolved = resolve_export(exports, export_protocol, tasks[key])
        require(resolved['status'] != 'export_pending', 'Pending export has terminal analysis')
        require(row['export_receipt_sha256'] == resolved['export_receipt_sha256'] and
                row['export_exit_code'] == resolved['export_exit_code'], 'Analysis changed export outcome')
        if resolved['status'] == 'available':
            require(row['status'] in ['scored', 'analysis_failed'], 'Export with maps has invalid analysis status')
            if row['status'] == 'scored':
                require(row['native_export_accepted'] == resolved['native_export_accepted'], 'Admission changed during scoring')
                require(row['offline_export_protocol_sha256'] == sha(exports/'protocol.json'), 'Scored export protocol differs')
                expected_maps = {resolved['lane']+'-'+direction+suffix
                    for direction in ['fixed-to-moving', 'moving-to-fixed']
                    for suffix in ['.f64', '.json', '.valid-u8', '-warped.f64']}
                require(set(row['map_inputs']) == expected_maps, 'Scored event has incomplete map identities')
                for name, digest in row['map_inputs'].items():
                    require(Path(name).name == name and sha(resolved['directory']/name) == digest, 'Scored map differs from export')
                logfile = analysis/key[0]/key[1]/(row['event']['directory']+'.log')
                require(row['log_sha256'] == sha(logfile), 'Analysis log changed')
        else:
            require(row['status'] == resolved['status'], 'Export failure or rejection status changed')
    complete_exports = False
    result_path = exports/'results.json'
    if result_path.exists():
        result = read(result_path); export_rows = result['rows']
        expected_tasks = {key: task for key, task in tasks.items()}
        seen = set()
        for row in export_rows:
            task = row['task']; key = task['pair'], task['policy'], task['event']
            require(key not in seen and expected_tasks.get(key) == task, 'Duplicate or substituted export result')
            seen.add(key)
            receipt = exports/key[0]/key[1]/f'event-{key[2]:04d}-receipt.json'
            require(row == read(receipt), 'Final export ledger differs from attempt receipt')
        require(seen == set(expected_tasks), 'Final export ledger omits attempts')
        complete_exports = True
    return dict(schema='hfq-offline-ledger-verification-v1',
        verified_at=datetime.now(timezone.utc).isoformat(),
        verification_sources={str(Path(__file__).resolve()): sha(Path(__file__)), str(validator): sha(validator)},
        analysis_protocol_sha256=sha(analysis/'protocol.json'),
        export_protocol_sha256=sha(exports/'protocol.json'),
        expected_event_count=len(expected), recorded_event_count=len(actual),
        expected_export_count=len(tasks), missing_event_keys=[list(key) for key in missing],
        expected_event_keys=[list(key) for key in sorted(expected)],
        analysis_receipt_sha256={str(path.relative_to(analysis)): sha(path) for path in paths},
        exact_event_set_complete=not missing, terminal_export_ledger_complete=complete_exports,
        ledger_complete=not missing and complete_exports,
        statuses=dict(Counter(row['status'] for row in records)), source_fit_ledger=fits,
        qualification_claim=False, runtime_comparison_allowed=False,
        interpretation='Exact attempt accounting only; failed exports, rejected maps, nonconverged fits and analysis failures are not scientific successes.')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--analysis', type=Path, required=True)
    p.add_argument('--require-complete', action='store_true')
    args = p.parse_args(); result = inspect(args.analysis.resolve())
    print(json.dumps(result, indent=2))
    if args.require_complete and not result['ledger_complete']: raise SystemExit(2)
