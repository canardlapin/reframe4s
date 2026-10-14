#!/usr/bin/env python3
"""Require exact real-data scoring parity, excluding only paths and audit times."""
import argparse
from collections import Counter
from pathlib import Path

from offline_analysis import read, require, sha
from trajectory_study import write


def intensity_identity(row):
    # Relocated map inputs have different absolute paths. Preserve every input
    # digest, basename and all numerical/coverage/contract fields.
    result = dict(row, directions={})
    for direction, values in row['directions'].items():
        inputs = {Path(path).name: digest for path, digest in values['inputs'].items()}
        require(len(inputs) == len(values['inputs']), 'Ambiguous intensity input basenames')
        result['directions'][direction] = dict(values, inputs=inputs)
    return result


def verify(original, analysis, output):
    protocol = read(analysis/'protocol.json')
    original_protocol = read(original/'protocol.json')
    require(protocol['trajectory_protocol_sha256'] == original_protocol['trajectory_protocol_sha256'],
            'Scoring parity requires the same original capture study')
    offline = protocol['offline_exports']
    export_protocol = read(Path(offline['directory'])/'protocol.json')
    require(export_protocol['pilot'] and len(export_protocol['tasks']) == 2,
            'Expected the declared coarse/fine two-state parity pilot')
    rows = [read(path) for path in sorted(analysis.glob('*/*/event-*.json'))]
    require(Counter(row['status'] for row in rows) ==
            {'scored':2, 'not_captured':17, 'export_not_requested':11},
            'Pilot analysis event denominator is incomplete or unexpected')
    comparisons = []
    for task in export_protocol['tasks']:
        relative = Path(task['pair'])/task['policy']/f"event-{task['event']:04d}.json"
        before, after = read(original/relative), read(analysis/relative)
        checks = {key: before[key] == after[key] for key in
                  ['event','fit_receipt_sha256','regularity','map_inputs','native_export_accepted']}
        checks['fixed_intensity'] = intensity_identity(before['fixed_intensity']) == intensity_identity(after['fixed_intensity'])
        def geometry(row):
            return {key:value for key,value in row['geometry_and_anatomy'].items()
                    if key not in ['geometry_audit_seconds','total_audit_seconds']}
        checks['geometry_and_anatomy'] = geometry(before) == geometry(after)
        lane = 'halfflow' if after['native_export_accepted'] else 'halfflow-rejected'
        files = {}
        for suffix in ['-evaluation.json','-anatomy-evaluation.json','-cell-topology.json']:
            report = relative.parent/after['event']['directory']/(lane+suffix)
            checks[lane+suffix] = sha(original/report) == sha(analysis/report)
            files[str(report)] = sha(analysis/report)
        comparisons.append(dict(pair=task['pair'],policy=task['policy'],event=task['event'],
            original_receipt_sha256=sha(original/relative), offline_receipt_sha256=sha(analysis/relative),
            checks=checks, scientific_reports=files, passed=all(checks.values())))
    result = dict(schema='hfq-offline-scoring-parity-v1',
        source_sha256=sha(Path(__file__)), original_protocol_sha256=sha(original/'protocol.json'),
        offline_analysis_protocol_sha256=sha(analysis/'protocol.json'),
        excluded='Only geometry_audit_seconds, total_audit_seconds and relocation of identical hashed intensity inputs. All per-structure, geometry, cell, coverage, intensity and regularity numbers must agree exactly.',
        event_statuses=dict(Counter(row['status'] for row in rows)),comparisons=comparisons,
        passed=all(row['passed'] for row in comparisons), qualification_claim=False)
    write(output,result)
    require(result['passed'],'Offline scoring differs from original synchronous scoring')
    print('PASS: both real checkpoints reproduce all scientific scores; six full reports are byte-identical')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--original',type=Path,required=True)
    p.add_argument('--analysis',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    args = p.parse_args()
    verify(args.original.resolve(),args.analysis.resolve(),args.output.resolve())
