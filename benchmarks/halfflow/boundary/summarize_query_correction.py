#!/usr/bin/env python3
"""Verify the exact attempt set and summarize query versus raster diagnostics."""
import argparse
from pathlib import Path
from analyze_c04 import read, sha, write


def summarize(study):
    p, results = read(study / 'protocol.json'), read(study / 'results.json')
    if read(study / 'completion.json')['exit_code'] != 0 or not results['all_declared_variants_complete']:
        raise ValueError('Complete terminal study required')
    expected = {(f['case'], f['lane'], f['arm'], b) for f in p['fields'] for b in p['correction_budgets']}
    observed = set()
    for row in results['variants']:
        key = tuple(row['field'][k] for k in ['case', 'lane', 'arm']) + (row['budget'],)
        if key not in expected or key in observed:
            raise ValueError('Unexpected or duplicate field/budget outcome')
        observed.add(key)
        for reports in [row['direct_compositions'], row['raster_compositions']]:
            if any(r['all']['eligible'] != 50653 or r['all']['invalid'] != 0 for r in reports.values()):
                raise ValueError('Original query denominator or support changed')
        for payload in row['payloads']:
            if sha(study / payload['path']) != payload['sha256']:
                raise ValueError('Diagnostic coordinate payload changed')
    if observed != expected:
        raise ValueError('Missing declared variants')
    budgets = []
    for budget in p['correction_budgets']:
        rows = [r for r in results['variants'] if r['budget'] == budget]
        reports = [q for r in rows for q in r['query_reports'].values()]
        work = {k: sum(q['work'][k]['total'] for q in reports) for k in reports[0]['work']}
        budgets.append(dict(budget=budget, fields=len(rows), original_queries=sum(q['targets'] for q in reports),
            query_diagnostic_passes=sum(r['query_diagnostic_pass'] for r in rows),
            all_queries_root_converged_fields=sum(r['all_queries_root_converged'] for r in rows),
            unresolved_queries=sum(q['unresolved'] for q in reports),
            raster_diagnostic_passes=sum(r['raster_diagnostic_pass'] for r in rows),
            raster_passes_by_lane={lane: sum(r['raster_diagnostic_pass'] for r in rows if r['field']['lane'] == lane)
                                  for lane in ['C4', 'C5']},
            maximum_direct_cycle_mm=max(r['all']['maximum_mm'] for row in rows for r in row['direct_compositions'].values()),
            maximum_raster_cycle_mm=max(r['all']['maximum_mm'] for row in rows for r in row['raster_compositions'].values()),
            inverse_cell_failures=sum(not r['materialized_inverse_cells']['pass_criteria'] for r in rows),
            minimum_materialized_inverse_cell_jacobian=min(r['materialized_inverse_cells']['minimum_cell_jacobian'] for r in rows),
            seed_samples=sum(q['seed_point_samples'] for q in reports), work=work,
            work_includes_final_residual_and_jacobian_validation=True))
    summary = dict(schema='hfq-query-inverse-summary-v1', source_sha256=sha(Path(__file__)),
        protocol_sha256=sha(study / 'protocol.json'), results_sha256=sha(study / 'results.json'),
        declared_variants=len(expected), recorded_variants=len(observed), budgets=budgets,
        maximum_independent_forward_difference_mm=max(c['maximum_vector_difference_mm'] for r in results['variants']
                                                       for c in r['independent_forward_checks'].values()),
        qualification_claim=False, production_export_qualified=False, runtime_comparison_allowed=False)
    output = study / 'scientific-summary.json'
    if output.exists():
        raise FileExistsError(output)
    write(output, summary)
    print('VERIFIED all120 field/budget variants and full original denominators')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--study', type=Path, required=True)
    args = parser.parse_args()
    summarize(args.study.resolve())
