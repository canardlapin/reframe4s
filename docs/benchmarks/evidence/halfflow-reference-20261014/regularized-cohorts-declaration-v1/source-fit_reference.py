#!/usr/bin/env python3
"""Fit one explicitly budgeted SyNOnly reference from the shared saved affine."""
import argparse
import json
import math
from pathlib import Path
import time
import ants
import numpy as np
from canonical_ants import verify_affine
from real_image_court import sha, write_json


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for key in ['data', 'affine', 'output']:
        p.add_argument('--' + key, type=Path, required=True)
    p.add_argument('--iterations', required=True)
    p.add_argument('--total-sigma', type=float, default=0.0)
    a = p.parse_args()
    iterations = tuple(int(n) for n in a.iterations.split(','))
    if len(iterations) != 3 or any(n < 0 for n in iterations):
        raise ValueError('Three nonnegative iteration limits required')
    if not math.isfinite(a.total_sigma) or a.total_sigma < 0:
        raise ValueError('Total-field smoothing must be finite and nonnegative')
    a.output.mkdir(parents=True, exist_ok=False)
    ants.config.set_ants_deterministic(True, seed_value=20261002)
    fixed = ants.image_read(str(a.data / 'fixed.nii.gz'))
    moving = ants.image_read(str(a.data / 'moving.nii.gz'))
    mask = ants.image_read(str(a.data / 'fixed-mask.nii.gz'))
    forward = np.asarray(json.loads(a.affine.read_text()), dtype=float).reshape(4,4)
    flip = np.diag([-1.,-1.,1.,1.]); pull = flip @ np.linalg.inv(forward) @ flip
    tx = ants.create_ants_transform(transform_type='AffineTransform', precision='double', dimension=3,
                                    matrix=pull[:3,:3], translation=pull[:3,3], center=(0,0,0))
    supplied = a.output / 'supplied-affine.mat'; ants.write_transform(tx, str(supplied))
    write_json(a.output / 'initialization-verification.json', verify_affine(supplied, a.affine))
    settings = dict(type_of_transform='SyNOnly', syn_metric='CC', syn_sampling=2, reg_iterations=iterations,
                    grad_step=.2, flow_sigma=3, total_sigma=a.total_sigma, mask_all_stages=True, singleprecision=True, verbose=True)
    write_json(a.output / 'settings.json', dict(settings=settings, seed=20261002, antspyx_version=ants.__version__,
               initialization_sha256=sha(a.affine), fixed_sha256=sha(a.data / 'fixed.nii.gz'), moving_sha256=sha(a.data / 'moving.nii.gz')))
    start = time.monotonic()
    result = ants.registration(fixed, moving, mask=mask, initial_transform=str(supplied),
                              outprefix=str(a.output / 'ants-'), **settings)
    ants.image_write(result['warpedmovout'], str(a.output / 'warped.nii.gz'))
    ants.image_write(result['warpedfixout'], str(a.output / 'inverse-warped.nii.gz'))
    affine = next(path for path in result['fwdtransforms'] if path.endswith('.mat'))
    write_json(a.output / 'collapsed-affine-verification.json', verify_affine(affine, a.affine))
    write_json(a.output / 'result.json', dict(seconds=time.monotonic()-start,
               forward=result['fwdtransforms'], inverse=result['invtransforms']))


if __name__ == '__main__': main()
