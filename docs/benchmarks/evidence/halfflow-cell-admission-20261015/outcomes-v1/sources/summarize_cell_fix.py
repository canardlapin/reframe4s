#!/usr/bin/env python3
"""Compare every declared field and lane after the cell admission correction."""
import argparse
from collections import Counter
import json
from pathlib import Path
from analyze_c04 import read, write, sha


def summarize(root, baseline):
    output = root / 'comparison.json'
    if output.exists():
        raise FileExistsError(output)
    before = read(baseline / 'c04-cells-v1/results.json')['rows']
    after = read(root / 'c04-cells-v1/results.json')['rows']
    def keyed(rows):
        result = {(r['task']['case'], r['task']['lane'], r['task']['arm'], r['task']['kind']): r for r in rows}
        if len(rows) != 48 or len(result) != 48:
            raise ValueError('Exactly 48 unique fields required')
        return result
    old, new = keyed(before), keyed(after)
    if set(old) != set(new):
        raise ValueError('Field denominator changed')
    comparisons = []
    for key in sorted(old):
        a, b = old[key], new[key]
        comparisons.append(dict(case=key[0], lane=key[1], arm=key[2], kind=key[3],
            before=a['cell_diagnostics'], after=b['cell_diagnostics'],
            field_hash_unchanged=a['task']['central_difference']['field_sha256'] == b['task']['central_difference']['field_sha256']))
    captures = {'before': baseline / 'c04-capture-v4', 'after': root / 'c04-replay-v1'}
    lanes = {}
    inputs = {str(p): sha(p) for p in [baseline / 'c04-cells-v1/results.json', root / 'c04-cells-v1/results.json']}
    for label, capture in captures.items():
        receipt = read(capture / 'receipt.json')
        if receipt['exit_code'] != 0 or not receipt['source_and_inputs_unchanged']:
            raise ValueError('Both captures must be terminal and immutable')
        paths = [capture / 'receipt.json', capture / 'replay/rows.jsonl', capture / 'replay/execution.log']
        inputs.update({str(p): sha(p) for p in paths})
        rows = [json.loads(line) for line in paths[1].read_text().splitlines()]
        lanes[label] = {(r['case_id'], v['lane']): v for r in rows for v in r['rows'] if v['lane'] in ['C4', 'C5']}
        if len(rows) != 6 or len(lanes[label]) != 12:
            raise ValueError('All six cases and both lanes required')
    if set(lanes['before']) != set(lanes['after']):
        raise ValueError('Case/lane denominator changed')
    lane_changes = [dict(case=k[0], lane=k[1], before=lanes['before'][k], after=lanes['after'][k]) for k in sorted(lanes['before'])]
    groups = []
    for lane in ['C4', 'C5']:
        for kind in ['forward', 'inverse']:
            values = [v for v in comparisons if v['lane'] == lane and v['kind'] == kind]
            groups.append(dict(lane=lane, kind=kind, fields=len(values),
                before_folded_fields=sum(v['before']['folded_cells'] > 0 for v in values),
                after_folded_fields=sum(v['after']['folded_cells'] > 0 for v in values),
                before_cell_passes=sum(v['before']['pass_criteria'] for v in values),
                after_cell_passes=sum(v['after']['pass_criteria'] for v in values),
                before_minimum=min(v['before']['minimum_cell_jacobian'] for v in values),
                after_minimum=min(v['after']['minimum_cell_jacobian'] for v in values),
                unchanged_field_hashes=sum(v['field_hash_unchanged'] for v in values)))
    forward_pass = all(v['after']['pass_criteria'] for v in comparisons if v['kind'] == 'forward')
    write(output, dict(schema='hfq-cell-admission-comparison-v1', inputs=inputs, fields=comparisons,
        groups=groups, lanes=lane_changes, all_24_forward_fields_pass=forward_pass,
        statuses={label: dict(Counter(v['status'] for v in values.values())) for label, values in lanes.items()},
        qualification_claim=False, acquired_mri_improvement_claim=False, runtime_comparison_allowed=False,
        scope='All six observed synthetic cases and all 48 fields; no case or failure omitted. '
            'Stored inverse topology, endpoint inverse admission and convergence remain separate outcomes.'))
    print('ALL 24 FORWARD FIELDS PASS:', forward_pass, flush=True)
    for group in groups:
        print(group, flush=True)
    if not forward_pass:
        raise SystemExit(1)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--baseline', type=Path, required=True)
    args = parser.parse_args()
    summarize(args.root.resolve(), args.baseline.resolve())
