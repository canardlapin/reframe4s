#!/usr/bin/env python3
"""Declare a one-pair convergence/regularity diagnostic for the SyN reference.

Two standard ANTs parameter settings, no label-based parameter sweep. Fits
overlap other diagnostic work and cannot supply production timing evidence.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import shutil

from budget_study import ROOT, HERE, run
from real_image_court import sha


def freeze(study, original):
    if study.exists():
        raise FileExistsError(study)
    baseline = json.loads(original.read_text())
    case = next(c for c in baseline['cases'] if c['id'] == 'oasis-11-to-12')
    sources = list(HERE.glob('*.py')) + [ROOT/'benchmarks/flashalign/real_image_court.py',
                                       ROOT/'benchmarks/flashalign/real_mri_cell_topology.py']
    source_hashes = {str(p): sha(p) for p in sources}
    inputs = {case[k]: sha(Path(case[k])) for k in ['saved_affine', 'moving_labels', 'fixed_labels']}
    inputs.update({str(p): sha(p) for p in Path(case['data']).glob('*.nii.gz')})
    for path, digest in inputs.items():
        if baseline['inputs'][path] != digest:
            raise ValueError('Original observed input changed: '+path)
    lanes = [dict(id='syn-convergence', method='ants', iterations=[100, 70, 200], total_sigma=0.0),
             dict(id='syn-total0.5', method='ants', iterations=[100, 70, 200], total_sigma=0.5)]
    policy = dict(baseline, schema='hfq-p02-regularized-reference-diagnostic-v1',
        created_at=datetime.now(timezone.utc).isoformat(), cases=[case], lanes=lanes,
        sources=source_hashes, inputs=inputs, workers=1, runtime_comparison_allowed=False,
        baseline_protocol_sha256=sha(original),
        purpose='Separate fine-iteration-cap effects from accumulated-field regularity on one observed pair',
        native_parameter_source='https://antspy.readthedocs.io/en/latest/api/ants.registration.html',
        interpretation='total_sigma is the native ANTs parameter; no claim that it equals HalfFlow physical sigma',
        stopping_rule='Native convergence <=1e-7 before the declared cap on all enabled levels; caps and unresolved stops remain failures',
        concurrency='One reference worker may overlap the separately sealed two-worker HalfFlow diagnostic panel',
        timing_scope='Diagnostic wall/peak-memory measurements only; no production runtime comparison or qualification claim',
        matched_budget_rule='Not applicable: these are diagnostic variants, not matched-budget timing lanes',
        selection='Exactly two declared variants; preserve both results and failures. No reference promotion from this pilot.',
        follow_up='Only after both pilot outcomes: record the mechanism evidence and declare any broader validation prospectively',
        qualification_claim=False)
    study.mkdir(parents=True)
    for path in sources:
        shutil.copyfile(path, study/('source-'+path.name))
    with (study/'protocol.json').open('x') as stream:
        json.dump(policy, stream, indent=2)
        stream.write('\n')
    print('FROZEN one observed pair, two reference diagnostics; production timing excluded', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['freeze', 'run'])
    parser.add_argument('--study', type=Path, required=True)
    parser.add_argument('--baseline', type=Path)
    args = parser.parse_args()
    if args.command == 'freeze':
        if args.baseline is None:
            parser.error('--baseline required')
        freeze(args.study.resolve(), args.baseline.resolve())
    else:
        run(args.study.resolve())
