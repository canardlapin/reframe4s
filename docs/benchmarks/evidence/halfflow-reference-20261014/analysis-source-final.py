#!/usr/bin/env python3
"""Summarize raw observed-data quality/cost without making an admission claim."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import statistics


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def syn_stopping(text, limits):
    groups = []
    for line in text.splitlines():
        if line.startswith('XXDIAGNOSTIC,'):
            groups.append([])
        elif re.match(r'\s*\d+DIAGNOSTIC,', line):
            fields = line.split(',')
            if not groups:
                raise ValueError('Missing diagnostic group header')
            groups[-1].append((int(fields[1]), float(fields[3])))
    active = [limit for limit in limits if limit > 0]
    if len(groups) != len(active) or any(not group for group in groups):
        return {'interpretable': False, 'reason': 'Unexpected iteration log groups'}
    rows = []
    iterator = iter(groups)
    for level, limit in enumerate(limits):
        if limit == 0:
            rows.append({'level': level, 'limit': 0, 'iterations': 0, 'status': 'disabled'})
            continue
        used, convergence = next(iterator)[-1]
        status = ('native_convergence_rule_before_cap' if used < limit and convergence <= 1e-7
                  else 'iteration_cap' if used >= limit else 'unresolved_early_stop')
        rows.append({'level': level, 'limit': limit, 'iterations': used,
                     'last_convergence_value': convergence if math.isfinite(convergence) else None,
                     'status': status})
    return {'interpretable': True, 'levels': rows,
            'all_enabled_levels_stop_by_native_rule': all(r['status'] in ['disabled', 'native_convergence_rule_before_cap'] for r in rows),
            'full_resolution_enabled': limits[-1] > 0}


def load_studies(study, additional=None):
    rows = json.loads((study / 'results.json').read_text())['rows']
    protocol = json.loads((study / 'protocol.json').read_text())
    expected = {(c['id'], lane['id']) for c in protocol['cases'] for lane in protocol['lanes']}
    if len(rows) != len(expected) or {(r['pair'], r['lane']) for r in rows} != expected:
        raise ValueError('A complete attempt ledger is required')
    if any(r['protocol_sha256']!=digest(study/'protocol.json') for r in rows):
        raise ValueError('Primary receipt identity mismatch')
    origins = {(r['pair'],r['lane']):study/r['pair'] for r in rows}
    provenance = [{'protocol_sha256':digest(study/'protocol.json'), 'results_sha256':digest(study/'results.json')}]
    if additional:
        extra = json.loads((additional/'protocol.json').read_text())
        more = json.loads((additional/'results.json').read_text())['rows']
        if extra['inputs'] != protocol['inputs'] or extra['cases'] != protocol['cases']:
            raise ValueError('Comparable-cost cases or inputs differ')
        expected_extra = {(c['id'],lane['id']) for c in extra['cases'] for lane in extra['lanes']}
        if len(more)!=len(expected_extra) or {(r['pair'],r['lane']) for r in more}!=expected_extra or expected & expected_extra:
            raise ValueError('Invalid additional attempt ledger')
        for row in more:
            child=additional/('run-'+row['pair'])
            child_protocol=json.loads((child/'protocol.json').read_text())
            if digest(child/'protocol.json')!=row['protocol_sha256'] or child_protocol['parent_matched_protocol_sha256']!=digest(additional/'protocol.json'):
                raise ValueError('Additional receipt identity mismatch')
            origins[row['pair'],row['lane']]=child/row['pair']
        rows += more;protocol['lanes'] += extra['lanes']
        provenance.append({'protocol_sha256':digest(additional/'protocol.json'), 'results_sha256':digest(additional/'results.json')})
    return rows, protocol, origins, provenance


def summarize(study, additional=None):
    rows, protocol, origins, provenance = load_studies(study, additional)
    groups = {}
    stopping = {}
    for lane in protocol['lanes']:
        selected = [r for r in rows if r['lane'] == lane['id']]
        complete = [r for r in selected if r.get('audit_completed') and r.get('anatomy_complete')]
        valid = [r for r in complete if r['geometry_pass'] and r['cells_pass']]
        groups[lane['id']] = {'attempts': len(selected), 'complete_audits': len(complete), 'common_geometry_valid': len(valid)}
        if complete:
            for metric in ['mean_dice', 'mean_hd95_mm', 'nonlinear_and_geometry_seconds', 'fit_peak_rss_bytes']:
                values = [r[metric] for r in complete]
                groups[lane['id']][metric] = {'mean': statistics.mean(values), 'median': statistics.median(values),
                                             'minimum': min(values), 'maximum': max(values)}
        for row in selected:
            directory = origins[row['pair'],row['lane']]
            key = row['pair'] + '/' + lane['id']
            if lane['method'] == 'ants':
                limits=lane['iterations'] if 'iterations' in lane else lane['iterations_by_case'][row['pair']]
                stopping[key] = syn_stopping((directory / (lane['id'] + '-fit.log')).read_text(), limits)
            else:
                path = directory / lane['id'] / 'halfflow-diagnostics.txt'
                levels = re.findall(r'HalfFlowCcLevelDiagnostics\(([^,]+),([^,]+),([^,]+),(\d+),(\d+),([^,]+),', path.read_text()) if path.exists() else []
                stopping[key] = {'levels': [{'shrink': int(x[0]), 'accepted_steps': int(x[3]), 'attempts': int(x[4]), 'status': x[5]} for x in levels],
                                 'qualifying_convergence_established': False}
    matched = []
    for case in protocol['cases']:
        selected = [r for r in rows if r['pair'] == case['id'] and r.get('audit_completed')]
        controls = [r for r in selected if r['method'] == 'halfflow']
        refs = [r for r in selected if r['method'] == 'ants']
        if not controls or not refs:
            matched.append({'pair': case['id'], 'comparable': False, 'reason': 'Missing completed timing'})
            continue
        control = controls[0]['nonlinear_and_geometry_seconds']
        closest = min(refs, key=lambda r: abs(math.log(r['nonlinear_and_geometry_seconds']/control)))
        ratio = closest['nonlinear_and_geometry_seconds']/control
        matched.append({'pair': case['id'], 'nearest_lane': closest['lane'], 'reference_over_halfflow_runtime': ratio,
                        'comparable': .8 <= ratio <= 1.25,
                        'reference_common_geometry_valid': closest['geometry_pass'] and closest['cells_pass']})
    return {'schema': 'hfq-budget-summary-v1', 'protocol_sha256': digest(study/'protocol.json'),
            'results_sha256': digest(study/'results.json'), 'source_sha256': digest(Path(__file__)),
            'studies':provenance,
            'estimand': 'Raw anatomy on observed development outputs, including geometry failures; not failure-penalized qualification scores',
            'lanes': groups, 'matched_budget': matched, 'stopping': stopping, 'qualification_claim': False}


def plot(study, output, additional=None):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    from matplotlib.lines import Line2D
    from matplotlib.ticker import FixedLocator, NullLocator, ScalarFormatter
    rows,protocol,_,_=load_studies(study,additional)
    cases = [c['id'] for c in protocol['cases']]
    figure, axes = plt.subplots(2, len(cases), figsize=(12, 6.8), sharey='row', layout='constrained')
    marks = {'halfflow-selected': 'o', 'syn-short': '^', 'syn-standard': 'D', 'syn-generous': 's','syn-matched':'P'}
    for column, case in enumerate(cases):
        valid = [r for r in rows if r['pair'] == case and r.get('audit_completed') and r.get('anatomy_complete')]
        for index, metric in enumerate(['mean_dice', 'mean_hd95_mm']):
            ax = axes[index,column]
            for row in valid:
                passed = row['geometry_pass'] and row['cells_pass']
                ax.scatter(row['nonlinear_and_geometry_seconds'], row[metric], marker=marks[row['lane']], s=80,
                           facecolors='#2563eb' if passed else 'white', edgecolors='#2563eb' if passed else '#b91c1c', linewidths=1.8, zorder=2)
            ax.set_xscale('log'); ax.set_xlim(20, 650)
            ax.xaxis.set_major_locator(FixedLocator([30, 100, 300]))
            ax.xaxis.set_major_formatter(ScalarFormatter())
            ax.xaxis.set_minor_locator(NullLocator())
            ax.grid(alpha=.18); ax.set_axisbelow(True)
            if index == 0: ax.set_title(case.replace('oasis-', 'OASIS ').replace('-to-', ' → '))
            else: ax.set_xlabel('Nonlinear stage + geometry (seconds)')
        axes[0,0].set_ylabel('Mean bidirectional Dice ↑')
        axes[1,0].set_ylabel('Bidirectional median-label HD95 (mm) ↓')
    handles = [Line2D([],[],color='#555',marker=marker,linestyle='',label=name) for name,marker in
               [('HalfFlow','o'),('SyN 20/10/0','^'),('SyN 40/20/0','D'),('SyN 100/70/40','s')]]
    if additional:handles.append(Line2D([],[],color='#555',marker='P',linestyle='',label='SyN timing-calibrated'))
    figure.legend(handles=handles, loc='outside lower center', ncol=len(handles), frameon=False)
    figure.suptitle('Observed development data · blue: shared geometry passes · red outline: geometry fails', fontsize=12)
    figure.savefig(output.with_suffix('.png'), dpi=170)
    figure.savefig(output.with_suffix('.svg'))
    plt.close(figure)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--study', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--additional-study', type=Path)
    parser.add_argument('--plot', type=Path)
    args = parser.parse_args()
    with args.output.open('x') as stream:
        json.dump(summarize(args.study,args.additional_study), stream, indent=2)
        stream.write('\n')
    if args.plot:
        plot(args.study, args.plot,args.additional_study)
