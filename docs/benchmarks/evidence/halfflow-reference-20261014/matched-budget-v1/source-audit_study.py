#!/usr/bin/env python3
"""Freeze and audit existing five-pair same-affine HalfFlow/SyN results.

This is observed-data development evidence. It performs no registration tuning
and does not classify fixed-budget historical runs as converged qualification.
"""
import argparse
import contextlib
from datetime import datetime, timezone
import gc
import json
from pathlib import Path
import shutil
import sys
import time
from types import SimpleNamespace

import nibabel as nib
import numpy as np

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'benchmarks/flashalign'))
from real_image_court import anatomy, evaluate, read_pull, sha, write_json
from real_mri_cell_topology import cell_diagnostics
from canonical_ants import convert


def freeze(study):
    if (study / 'protocol.json').exists():
        raise ValueError('Existing protocol is immutable')
    study.mkdir(parents=True, exist_ok=True)
    cases = json.loads((ROOT / 'benchmarks/halfflow/qualification/control-cases.json').read_text())
    inputs = {}
    for c in cases:
        for key in ['data', 'saved_affine', 'moving_labels', 'fixed_labels']:
            c[key] = str(ROOT / c[key])
        for f in Path(c['data']).glob('*.nii.gz'):
            inputs[str(f)] = sha(f)
        for key in ['saved_affine', 'moving_labels', 'fixed_labels']:
            inputs[c[key]] = sha(c[key])
        c['reference'] = str(ROOT / 'benchmarks/flashalign/local-results/real-mri-calibration-20261011' / c['id'] / 'ants-same-affine')
        for phase in ['sentinels', 'development']:
            p = ROOT / 'benchmarks/flashalign/local-results/real-mri-recovery-20261011' / phase / c['id'] / 'hf40-total1.5-boundary6'
            if p.exists():
                c['halfflow'] = str(p)
                c['halfflow_source_sha256'] = json.loads((p.parents[1] / 'source.json').read_text())['sha256']
                break
        if 'halfflow' not in c:
            raise ValueError('Missing selected HalfFlow result: ' + c['id'])
        for directory in [Path(c['reference']), Path(c['halfflow'])]:
            for f in directory.iterdir():
                if f.is_file() and f.suffix in ['.json', '.f64', '.valid-u8', '.txt', '.mat', '.gz']:
                    inputs[str(f)] = sha(f)
    sources = [Path(__file__), Path(__file__).with_name('canonical_ants.py'),
               Path(__file__).with_name('test_canonical_ants.py'),
               ROOT / 'benchmarks/flashalign/real_image_court.py',
               ROOT / 'benchmarks/flashalign/real_mri_cell_topology.py']
    source_hashes = {str(p): sha(p) for p in sources}
    for p in sources:
        shutil.copyfile(p, study / ('source-' + p.name))
    protocol = {'schema': 'hfq-p02-historical-map-audit-v1', 'created_at': datetime.now(timezone.utc).isoformat(),
                'cases': cases, 'sources': source_hashes, 'inputs': inputs,
                'purpose': 'Audit all five existing selected HalfFlow and same-affine SyN maps; no tuning or qualification claim',
                'gates': 'Unchanged independent voxel/coverage/brain inverse and all-complete-cell thresholds; all failures retained',
                'conversion': 'Manual RAS composition verified against native double image warp and float point probes',
                'metrics': 'Common original per-label Dice and HD95; no historical report overwritten',
                'timing': 'Audit stage times are descriptive only; historical fit timings are not controlled end-to-end comparisons',
                'probe': 'One OASIS 11-to-12 coordinate-conversion probe preceded this freeze; no new anatomy was scored'}
    write_json(study / 'protocol.json', protocol)
    print('FROZEN', len(cases), 'cases', len(inputs), 'input identities', flush=True)


def verify(protocol):
    for group in ['sources', 'inputs']:
        for path, digest in protocol[group].items():
            if sha(path) != digest:
                raise ValueError(group + ' changed: ' + path)


def stage_halfflow_inputs(original, output):
    """Link only immutable map inputs; generated report names must stay unlinked."""
    names = ['mode.txt', 'plan.txt', 'export-diagnostics.txt']
    for direction in ['fixed-to-moving', 'moving-to-fixed']:
        name = 'halfflow-' + direction
        names.extend([name + '.json', name + '.f64', name + '.valid-u8', name + '-warped.f64'])
    output.mkdir()
    for name in names:
        source = original / name
        if not source.is_file():
            raise ValueError('Missing saved input: ' + str(source))
        (output / name).symlink_to(source.resolve())


def audit_maps(case, directory, lane):
    started = time.monotonic()
    try:
        evaluate(SimpleNamespace(data=case['data'], output=str(directory), lane=lane, fixed_labels=None, moving_labels=None))
    except SystemExit as e:
        if e.code != 2:
            raise
    report = json.loads((directory / (lane + '-evaluation.json')).read_text())
    cells = {}
    for direction in ['fixed-to-moving', 'moving-to-fixed']:
        coords, valid, _, affine = read_pull(directory, lane + '-' + direction)
        cells[direction] = cell_diagnostics(coords, valid, affine) | {
            'map_sha256': sha(directory / (lane + '-' + direction + '.f64')),
            'validity_sha256': sha(directory / (lane + '-' + direction + '.valid-u8'))}
    geometry_seconds = time.monotonic() - started
    write_json(directory / (lane + '-cell-topology.json'), cells)
    # Evaluation-only labels enter only after geometry was timed and written.
    labels = {role: nib.load(case[role + '_labels']) for role in ['fixed', 'moving']}
    for direction in ['fixed-to-moving', 'moving-to-fixed']:
        target, source = direction.split('-to-')
        coords, _, _, affine = read_pull(directory, lane + '-' + direction)
        report['directions'][direction]['anatomy'] = anatomy(labels[target], labels[source], coords, affine)
    report['anatomical_labels'] = {role: {'path': case[role + '_labels'], 'sha256': sha(case[role + '_labels'])} for role in labels}
    write_json(directory / (lane + '-anatomy-evaluation.json'), report)
    rows = list(report['directions'].values())
    complete = all(r['anatomy']['missing_labels'] == 0 for r in rows)
    return {'geometry_pass': report['geometry_pass'], 'cells_pass': all(c['pass_criteria'] for c in cells.values()),
            'anatomy_complete': complete, 'mean_dice': float(np.mean([r['anatomy']['macro_dice'] for r in rows])),
            'mean_hd95_mm': float(np.mean([r['anatomy']['median_hd95_mm'] for r in rows])) if complete else None,
            'maximum_brain_roundtrip_mm': max(r['brain_roundtrip_max_mm'] for r in rows),
            'minimum_cell_jacobian': min(c['minimum_cell_jacobian'] for c in cells.values()),
            'folded_cells': sum(c['folded_cells'] for c in cells.values()),
            'minimum_brain_coverage': min(min(r['brain_sampling_coverage'], r['brain_roundtrip_coverage']) for r in rows),
            'maximum_reload_error': max(r['reload_max_abs'] for r in rows),
            'geometry_audit_seconds': geometry_seconds, 'total_audit_seconds': time.monotonic() - started}


def run(study):
    protocol = json.loads((study / 'protocol.json').read_text())
    verify(protocol)
    summaries = []
    for c in protocol['cases']:
        for lane in ['ants', 'halfflow']:
            output = study / c['id'] / lane
            receipt = output.parent / (lane + '-receipt.json')
            if receipt.exists():
                summaries.append(json.loads(receipt.read_text())); continue
            output.parent.mkdir(parents=True, exist_ok=True)
            log = output.parent / (lane + '-audit.log')
            row = {'pair': c['id'], 'method': lane, 'protocol_sha256': sha(study / 'protocol.json')}
            print('START', c['id'], lane, flush=True)
            try:
                with log.open('x') as stream, contextlib.redirect_stdout(stream), contextlib.redirect_stderr(stream):
                    if lane == 'ants':
                        conversion = convert(c['data'], c['reference'], c['saved_affine'], output)
                        row['conversion_pass'] = conversion['conversion_pass']
                    else:
                        stage_halfflow_inputs(Path(c['halfflow']), output)
                        row['halfflow_source_sha256'] = c['halfflow_source_sha256']
                    row.update(audit_maps(c, output, lane))
                    # Compare same bidirectional estimand; old ANTs direction
                    # names describe image travel, while these names describe pulls.
                    old_path = Path(c['reference']) / 'anatomy-evaluation.json' if lane == 'ants' else Path(c['halfflow']) / 'halfflow-evaluation.json'
                    old = json.loads(old_path.read_text())
                    old_dirs = list(old.values()) if lane == 'ants' else [r['anatomy'] for r in old['directions'].values()]
                    row['historical_mean_dice_difference'] = row['mean_dice'] - float(np.mean([r['macro_dice'] for r in old_dirs]))
                    row['historical_mean_hd95_difference_mm'] = row['mean_hd95_mm'] - float(np.mean([r['median_hd95_mm'] for r in old_dirs]))
                row['audit_completed'] = True
            except Exception as e:
                row.update(audit_completed=False, error=type(e).__name__ + ': ' + str(e))
            row['log_sha256'] = sha(log)
            write_json(receipt, row); summaries.append(row)
            write_json(study / 'results.json', {'rows': summaries, 'qualification_claim': False})
            print('DONE', json.dumps(row), flush=True)
            gc.collect()
    return summaries


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('command', choices=['freeze','run'])
    p.add_argument('--study', type=Path, required=True)
    a = p.parse_args()
    freeze(a.study.resolve()) if a.command == 'freeze' else run(a.study.resolve())
