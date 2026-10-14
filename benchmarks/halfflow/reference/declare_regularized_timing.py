#!/usr/bin/env python3
"""Declare isolated cost curves for the fixed, geometry-admitted reference setting.

Declaration only. Run the existing budget harness after the named diagnostic
workers are terminal and an isolation receipt has been recorded. Concurrent
reference replication times are never used to select the schedules.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import shutil

from budget_study import ROOT, HERE
from real_image_court import sha
from summarize_study import summarize


def freeze(output, baseline, pilot, replication, diagnostics):
    if output.exists(): raise FileExistsError(output)
    base = json.loads((baseline/'protocol.json').read_text())
    if {c['id'] for c in base['cases']} != {'oasis-11-to-12','oasis-13-to-14','oasis-15-to-16'}:
        raise ValueError('Expected the unchanged three-pair timing panel')
    prior = []
    for study in [pilot,replication]:
        summary = summarize(study)
        rows = json.loads((study/'results.json').read_text())['rows']
        for row in rows:
            if row['lane'] != 'syn-total0.5': continue
            stopping = summary['stopping'][row['pair']+'/'+row['lane']]
            if not (row['audit_completed'] and row['geometry_pass'] and row['cells_pass'] and
                    stopping['all_enabled_levels_stop_by_native_rule']):
                raise ValueError('Fixed reference feasibility not established')
            prior.append(dict(pair=row['pair'],protocol_sha256=sha(study/'protocol.json'),
                results_sha256=sha(study/'results.json'),fit_log_sha256=row['fit_log_sha256']))
    if {r['pair'] for r in prior} != {c['id'] for c in base['cases']} or len(prior)!=3:
        raise ValueError('Incomplete fixed-setting feasibility panel')
    for path,digest in base['inputs'].items():
        if sha(Path(path)) != digest: raise ValueError('Observed input changed')
    control = next(l for l in base['lanes'] if l['method']=='halfflow')
    lanes = [control] + [dict(id=name,method='ants',iterations=[100,70,cap],total_sigma=.5)
        for name,cap in [('syn-total0.5-fine10',10),('syn-total0.5-fine25',25),('syn-total0.5-generous',200)]]
    sources = list(HERE.glob('*.py')) + [ROOT/'benchmarks/flashalign/real_image_court.py',
        ROOT/'benchmarks/flashalign/real_mri_cell_topology.py']
    dependencies = [diagnostics/'trajectories-deferred-v2/engine/protocol.json',
        diagnostics/'offline-panel-oasis11-v1/protocol.json',diagnostics/'offline-analysis-oasis11-v1/protocol.json',
        diagnostics/'order-study-v1/declared-protocol.json']
    protocol = dict(base,schema='hfq-p02-regularized-isolated-cost-v1',created_at=datetime.now(timezone.utc).isoformat(),
        lanes=lanes,sources={str(p):sha(p) for p in sources},runtime_comparison_allowed=True,
        baseline_protocol_sha256=sha(baseline/'protocol.json'),fixed_reference_feasibility=prior,
        pending_diagnostic_protocols={str(p):sha(p) for p in dependencies},
        launch_requirements='Before launching: verify all ten deferred source fits are terminal; all 29 first-batch exports and 74 analysis events are exactly accounted for with the ledger verifier; all three role-swap attempts are terminal; poll their live handles to terminal; record an isolation receipt. Do not start the next 61-state export batch, builds or other numerical work during this timed study. A violated isolation condition excludes affected timings, preserving outcomes.',
        schedule_selection='Uniform fine caps 10,25,200 for every pair. The first two sample lower-cost prefixes; the last is the same fixed generous schedule used in the completed regularized feasibility studies. No concurrent walltimes or anatomical scores determine per-case schedules.',
        stopping_rule='Read native iteration logs: only all enabled levels stopping by the native convergence rule before their caps count as converged. Capped lanes and HalfFlow budget/controller stops remain nonconverged even with valid maps.',
        reference_parameters='Retain native total_sigma=0.5, grad_step=0.2, flow_sigma=3, CC radius=2, seed=20261002, same prepared inputs and saved affine. No reference parameter search.',
        interpretation='Same-affine observed-data reference validation and quality/cost curves only. No new HalfFlow optimizer candidate or fresh-cohort qualification. If no reference lane lies within the existing [0.8,1.25] time ratio, report no comparable-budget lane; do not pad runtime, interpolate quality or relabel a capped fit as converged.',
        timing_scope=base['timing_scope']+'; direct child RSS only, converter/evaluator peak not measured',
        qualification_claim=False)
    output.mkdir(parents=True)
    for p in sources: shutil.copyfile(p,output/('source-'+p.name))
    with (output/'protocol.json').open('x') as stream: json.dump(protocol,stream,indent=2); stream.write('\n')
    print('DECLARED 3 observed pairs x 4 lanes; execution awaits a verified isolation window',flush=True)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    for name in ['output','baseline','pilot','replication','diagnostics']: p.add_argument('--'+name,type=Path,required=True)
    args = p.parse_args()
    freeze(args.output.resolve(),args.baseline.resolve(),args.pilot.resolve(),args.replication.resolve(),args.diagnostics.resolve())
