#!/usr/bin/env python3
"""Diagnostic numerical inversion of a fixed SyN forward pull map.

The forward transform is unchanged. The reverse map is solved over its entire
original grid, with finite affine extension outside the forward field. This
is a separately identified export policy, never a replacement for historical
ANTs outputs. All residuals and common geometry failures remain visible.
"""
import argparse
import json
from pathlib import Path
import shutil
import time

import ants
import nibabel as nib
import numpy as np

from canonical_ants import export_map, world_grid
from real_image_court import read_pull, sample, sha

POLICY = {'iterations': 40, 'backtracking_trials': 8,
          'residual_tolerance_mm': 1e-5, 'maximum_update_mm': 1.0,
          'selection': 'pointwise residual decrease only; no annotation or intensity optimization'}


def solve_inverse(forward, forward_grid, targets, initial, outside_affine, policy=POLICY):
    shape = initial.shape
    points = np.asarray(initial, dtype=float).reshape(-1,3).copy()
    targets = np.asarray(targets, dtype=float).reshape(-1,3)
    inverse_linear = np.linalg.inv(outside_affine[:3,:3])

    def evaluate(x, y):
        mapped, inside = sample(forward, forward_grid, x)
        mapped[~inside] = nib.affines.apply_affine(outside_affine, x[~inside])
        residual = mapped-y
        return residual, np.linalg.norm(residual,axis=1), int((~inside).sum())

    residual, errors, outside = evaluate(points, targets)
    trace = [{'iteration': 0, 'maximum_residual_mm': float(errors.max()),
              'unresolved_points': int((errors > policy['residual_tolerance_mm']).sum()),
              'outside_forward_grid_points': outside}]
    for iteration in range(1,policy['iterations']+1):
        active = np.flatnonzero(errors > policy['residual_tolerance_mm'])
        if len(active) == 0:
            break
        delta = residual[active] @ inverse_linear.T
        lengths = np.linalg.norm(delta,axis=1)
        delta *= np.minimum(1.,policy['maximum_update_mm']/np.maximum(lengths,1e-30))[:,None]
        unresolved = np.arange(len(active))
        accepted = 0
        for trial in range(policy['backtracking_trials']):
            indices = active[unresolved]
            candidate = points[indices] - (0.5**trial)*delta[unresolved]
            candidate_residual, candidate_error, _ = evaluate(candidate, targets[indices])
            better = candidate_error < errors[indices]
            chosen = indices[better]
            points[chosen] = candidate[better]
            residual[chosen] = candidate_residual[better]
            errors[chosen] = candidate_error[better]
            accepted += len(chosen)
            unresolved = unresolved[~better]
            if len(unresolved) == 0:
                break
        trace.append({'iteration': iteration, 'maximum_residual_mm': float(errors.max()),
                      'unresolved_points': int((errors > policy['residual_tolerance_mm']).sum()),
                      'improved_points': accepted})
        if accepted == 0:
            break
    _, errors, outside = evaluate(points, targets)
    return points.reshape(shape), {'policy':policy, 'trace':trace,
        'all_grid_residual_tolerance_met': bool(np.all(errors <= policy['residual_tolerance_mm'])),
        'maximum_grid_residual_mm':float(errors.max()), 'p99_grid_residual_mm':float(np.quantile(errors,.99)),
        'outside_forward_grid_points':outside, 'total_grid_points':len(points)}


def cleanup(case, original, output):
    if output.exists():
        raise FileExistsError(output)
    output.mkdir(parents=True)
    started = time.monotonic()
    forward, _, _, forward_grid = read_pull(original, 'ants-fixed-to-moving')
    initial, _, _, reverse_grid = read_pull(original, 'ants-moving-to-fixed')
    moving = nib.load(Path(case['data'])/'moving.nii.gz')
    targets = world_grid(moving)
    if initial.shape != targets.shape or not np.array_equal(reverse_grid,moving.affine):
        raise ValueError('Reverse grid must equal the original moving-image grid')
    affine = np.linalg.inv(np.asarray(json.loads(Path(case['saved_affine']).read_text())).reshape(4,4))
    inverse, diagnostics = solve_inverse(forward,forward_grid,targets,initial,affine)
    solve_seconds = time.monotonic()-started
    for suffix in ['.json','.f64','.valid-u8','-warped.f64']:
        shutil.copyfile(original/('ants-fixed-to-moving'+suffix),output/('ants-fixed-to-moving'+suffix))
    # Validate the new map using native ITK displacement resampling as well as
    # the common independent sampler. Vector components are stored in LPS mm.
    displacement = (inverse-targets)*np.array([-1.,-1.,1.])
    field = nib.Nifti1Image(displacement[...,None,:],reverse_grid)
    field.header.set_intent('vector');field.header.set_xyzt_units('mm')
    field_path = output/'cleaned-inverse-warp.nii.gz';nib.save(field,field_path)
    native_moving = ants.image_read(str(Path(case['data'])/'moving.nii.gz'),pixeltype='double')
    native_fixed = ants.image_read(str(Path(case['data'])/'fixed.nii.gz'),pixeltype='double')
    warped = ants.apply_transforms(native_moving,native_fixed,[str(field_path)],
                                 whichtoinvert=[False],interpolator='linear',singleprecision=False).numpy()
    export_map(output,'ants-moving-to-fixed',inverse,warped,reverse_grid)
    diagnostics.update(schema='hfq-inverse-cleanup-v1',case=case['id'],
        source_sha256=sha(Path(__file__)), original_forward_sha256=sha(original/'ants-fixed-to-moving.f64'),
        original_reverse_sha256=sha(original/'ants-moving-to-fixed.f64'),
        cleaned_reverse_sha256=sha(output/'ants-moving-to-fixed.f64'),
        saved_affine_sha256=sha(case['saved_affine']), solve_seconds=solve_seconds,
        conversion_seconds=time.monotonic()-started, qualification_claim=False)
    with (output/'cleanup.json').open('x') as stream:
        json.dump(diagnostics,stream,indent=2);stream.write('\n')
    return diagnostics


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol',type=Path,required=True)
    parser.add_argument('--case',required=True)
    parser.add_argument('--original',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    case=next(c for c in json.loads(args.protocol.read_text())['cases'] if c['id']==args.case)
    cleanup(case,args.original,args.output)
