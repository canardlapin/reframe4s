#!/usr/bin/env python3
"""Test the fixed regularized reference on two already-observed non-OASIS cohorts."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import shutil

from budget_study import ROOT, HERE, run
from regularized_followup import archive, write
from real_image_court import sha
from summarize_study import summarize


def freeze(study, prior, historical):
    if study.exists():raise FileExistsError(study)
    summary=summarize(prior)
    p=json.loads((prior/'protocol.json').read_text())
    previous=json.loads((prior/'results.json').read_text())['rows']
    if len(previous)!=2 or not all(row['audit_completed'] and row['geometry_pass'] and row['cells_pass']
            and summary['stopping'][row['pair']+'/'+row['lane']]['all_enabled_levels_stop_by_native_rule']
            for row in previous):
        raise ValueError('Completed fixed-setting replication required')
    if p['lanes'] != [{'id':'syn-total0.5','method':'ants','iterations':[100,70,200],'total_sigma':.5}]:
        raise ValueError('Unexpected reference setting')
    old=json.loads((historical/'protocol.json').read_text())
    old_inputs=json.loads((historical/'inputs.json').read_text())
    chosen=['mmrr-21-11-to-12','nki-rs-22-8-to-9']
    cases=[];inputs={}
    for pair in chosen:
        case=dict(next(c for c in old['cases'] if c['id']==pair))
        original=historical/pair
        saved=original/'affine/flashalign-affine-moving-to-fixed.json'
        command=original/'commands/selected.json'
        receipt=json.loads(command.read_text())
        original_hashes=dict(line.split('\t') for line in (original/'selected/inputs.sha256').read_text().splitlines())
        if receipt['exit_code']!=0 or str(saved) not in receipt['command']:
            raise ValueError('Historical selected fit did not use the declared affine')
        if sha(saved)!=original_hashes[str(saved)]:raise ValueError('Shared historical affine changed')
        case.update(saved_affine=str(saved),historical_halfflow=str(original/'selected'),
            historical_fit_receipt_sha256=sha(command),historical_source_sha256=receipt['source_sha256'],
            selection_reason='Fixed previously observed non-OASIS sentinel; no reference outcome used for selection')
        paths=[saved,command,original/'selected/inputs.sha256']
        paths += [Path(case[k]) for k in ['moving_labels','fixed_labels']]
        paths += list(Path(case['data']).glob('*.nii.gz'))
        for path in paths:
            digest=sha(path)
            if str(path) in old_inputs and old_inputs[str(path)]!=digest:raise ValueError('Historical input changed')
            if str(path) in original_hashes and original_hashes[str(path)]!=digest:raise ValueError('Historical fit input changed')
            inputs[str(path)]=digest
        cases.append(case)
    # The original manifest seals the images and labels; the acquired-image
    # runner's inputs.sha256 separately seals its generated same-affine input.
    for case in cases:
        for key in ['moving_labels','fixed_labels']:
            if case[key] not in old_inputs:raise ValueError('Unsealed historical labels')
        for name in ['moving.nii.gz','fixed.nii.gz','fixed-mask.nii.gz','moving-mask.nii.gz']:
            if str(Path(case['data'])/name) not in old_inputs:raise ValueError('Unsealed prepared images/masks')
    sources=list(HERE.glob('*.py'))+[ROOT/'benchmarks/flashalign/real_image_court.py',ROOT/'benchmarks/flashalign/real_mri_cell_topology.py']
    protocol=dict(p,schema='hfq-p02-regularized-reference-cohorts-v1',created_at=datetime.now(timezone.utc).isoformat(),
        cases=cases,inputs=inputs,sources={str(path):sha(path) for path in sources},
        previous_replication_protocol_sha256=sha(prior/'protocol.json'),
        previous_replication_results_sha256=sha(prior/'results.json'),
        historical_protocol_sha256=sha(historical/'protocol.json'),historical_inputs_sha256=sha(historical/'inputs.json'),
        purpose='Fixed-setting reference feasibility beyond OASIS, on observed MMRR and NKI-RS inputs',
        selection='MMRR 11-to-12 and NKI-RS 8-to-9 chosen before this reference run. Both cases and all failures retained. No new smoothing or iteration variant.',
        success_rule='Both new cases must stop by native convergence at every enabled level and pass every unchanged common geometry/cell gate; otherwise this cross-cohort replication fails.',
        follow_up='Keep all outcomes. This two-case feasibility study cannot establish a qualified strong reference; broader observed validation and isolated timing remain.',
        runtime_comparison_allowed=False,qualification_claim=False)
    study.mkdir(parents=True)
    for path in sources:shutil.copyfile(path,study/('source-'+path.name))
    write(study/'protocol.json',protocol)
    print('FROZEN MMRR and NKI-RS same-affine reference; unchanged total_sigma=0.5 and 100/70/200',flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('command',choices=['freeze','run','archive']);p.add_argument('--study',type=Path,required=True)
    p.add_argument('--prior',type=Path);p.add_argument('--historical',type=Path);p.add_argument('--output',type=Path)
    args=p.parse_args()
    if args.command=='freeze':
        if args.prior is None or args.historical is None:p.error('freeze requires --prior and --historical')
        freeze(args.study.resolve(),args.prior.resolve(),args.historical.resolve())
    elif args.command=='archive':
        if args.output is None:p.error('archive requires --output')
        archive(args.study.resolve(),args.output.resolve())
    else:run(args.study.resolve())
