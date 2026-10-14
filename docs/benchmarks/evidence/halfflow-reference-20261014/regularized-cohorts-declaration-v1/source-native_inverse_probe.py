#!/usr/bin/env python3
"""Probe original ANTs transform round trips without a composed-map raster.

This isolates inverse error already present in the saved transform chain from
additional interpolation error when a complete composed map is exported.
It is diagnostic evidence; it does not replace the common exported-map gate.
"""
import argparse
import json
from pathlib import Path

import nibabel as nib
import numpy as np

from canonical_ants import compose_points, world_grid
from real_image_court import sha


def probe(case, reference):
    transforms = json.loads((reference / 'result.json').read_text())
    result = {'schema': 'hfq-native-inverse-probe-v1', 'case': case['id'],
              'source_sha256': sha(Path(__file__)),
              'transforms': {p: sha(p) for key in ['forward', 'inverse'] for p in transforms[key]},
              'directions': {}, 'qualification_claim': False}
    for target, first, second in [('fixed', 'forward', 'inverse'), ('moving', 'inverse', 'forward')]:
        mask_path = Path(case['data']) / (target + '-mask.nii.gz')
        mask = nib.load(mask_path)
        points = world_grid(mask)[mask.get_fdata() > .5]
        flags = {'forward': [False, False], 'inverse': [True, False]}
        mapped, a = compose_points(points, transforms[first], flags[first])
        returned, b = compose_points(mapped, transforms[second], flags[second])
        errors = np.linalg.norm(returned - points, axis=-1)
        result['directions'][target] = {
            'mask_sha256': sha(mask_path), 'points': len(points),
            'maximum_mm': float(errors.max()), 'p95_mm': float(np.quantile(errors, .95)),
            'mean_mm': float(errors.mean()), 'extrapolation': a + b,
        }
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol', type=Path, required=True)
    parser.add_argument('--case', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError(args.output)
    protocol = json.loads(args.protocol.read_text())
    case = next(c for c in protocol['cases'] if c['id'] == args.case)
    result = probe(case, Path(case['reference']))
    result['protocol_sha256'] = sha(args.protocol)
    with args.output.open('x') as stream:
        json.dump(result, stream, indent=2)
        stream.write('\n')
