#!/usr/bin/env python3
"""Run the predeclared one-case inverse diagnostic after timed fits finish."""
import argparse
import contextlib
import json
from pathlib import Path
import time

from audit_study import audit_maps
from inverse_cleanup import cleanup
from native_inverse_probe import probe
from real_image_court import sha


def write(path, value):
    with path.open('x') as stream:
        json.dump(value,stream,indent=2);stream.write('\n')


def run(study):
    protocol=json.loads((study/'protocol.json').read_text())
    driver=json.loads((study/'driver-seal.json').read_text())
    if driver['protocol_sha256']!=sha(study/'protocol.json') or driver['source_sha256']!=sha(Path(__file__)):
        raise ValueError('Driver or protocol identity changed')
    for name,digest in protocol['sources'].items():
        if sha(name)!=digest:raise ValueError('Source identity changed: '+name)
    case=protocol['cases'][0]
    original=Path(protocol['original'])
    if (study/'receipt.json').exists():raise FileExistsError(study/'receipt.json')
    started=time.monotonic()
    with (study/'probe.log').open('x') as stream,contextlib.redirect_stdout(stream),contextlib.redirect_stderr(stream):
        native=probe(case,Path(case['reference']))
        write(study/'native-original.json',native)
        diagnostics=cleanup(case,original,study/'cleaned')
        audited=audit_maps(case,study/'cleaned','ants')
    historical=json.loads((original.parent/'ants-receipt.json').read_text())
    receipt={
        'schema':'hfq-inverse-cleanup-receipt-v1','protocol_sha256':sha(study/'protocol.json'),
        'driver_seal_sha256':sha(study/'driver-seal.json'), 'case':case['id'],
        'original_forward_unchanged':sha(original/'ants-fixed-to-moving.f64')==sha(study/'cleaned/ants-fixed-to-moving.f64'),
        'original':{k:historical[k] for k in ['mean_dice','mean_hd95_mm','maximum_brain_roundtrip_mm','geometry_pass','cells_pass']},
        'cleaned':audited, 'all_grid_residual_tolerance_met':diagnostics['all_grid_residual_tolerance_met'],
        'maximum_grid_residual_mm':diagnostics['maximum_grid_residual_mm'],
        'conversion_seconds':diagnostics['conversion_seconds'],'total_diagnostic_seconds':time.monotonic()-started,
        'qualification_claim':False,'reference_promoted':False,
    }
    write(study/'receipt.json',receipt)
    print(json.dumps(receipt,indent=2))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--study',type=Path,required=True)
    args=parser.parse_args();run(args.study.resolve())
