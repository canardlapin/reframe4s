#!/usr/bin/env python3
"""Export ANTs image-pull transforms as independently composed RAS coordinate maps.

ANTs forward image transforms map fixed-domain points to moving coordinates.
The list is applied in listed order for points: [warp, affine] => affine(warp(x)).
Displacements are LPS-mm vectors; NIfTI grid affines are read in RAS by nibabel.
No image/annotation mask changes map validity. ANTs zero displacement outside a
warp grid is retained and explicitly counted as extrapolation.
"""
import argparse
import json
from pathlib import Path
import sys

import ants
import nibabel as nib
import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'benchmarks/flashalign'))
from real_image_court import sample, sha, write_json

FLIP = np.array([-1., -1., 1.])


def affine_ras(path, invert=False):
    transform = ants.read_transform(str(path), precision='double')
    params = np.asarray(transform.parameters, dtype=np.float64)
    center = np.asarray(transform.fixed_parameters, dtype=np.float64)
    if params.shape != (12,) or center.shape != (3,):
        raise ValueError('Expected a 3D affine with twelve parameters and three center coordinates')
    matrix = np.eye(4)
    matrix[:3, :3] = params[:9].reshape(3, 3)
    matrix[:3, 3] = params[9:] + center - matrix[:3, :3] @ center
    flip4 = np.diag([-1., -1., 1., 1.])
    matrix = flip4 @ matrix @ flip4
    return np.linalg.inv(matrix) if invert else matrix


def compose_points(world, paths, inversions):
    if len(paths) != len(inversions):
        raise ValueError('One explicit inversion flag per transform required')
    result = np.array(world, dtype=np.float64, copy=True)
    extrapolation = []
    for path, invert in zip(paths, inversions):
        path = Path(path)
        if path.suffix == '.mat':
            result = nib.affines.apply_affine(affine_ras(path, invert), result)
        else:
            if invert:
                raise ValueError('Displacement inversion requires a saved inverse field')
            image = nib.load(path)
            values = image.get_fdata(dtype=np.float64)
            if values.shape != image.shape[:3] + (1, 3):
                raise ValueError('Expected an ITK 3D displacement vector NIfTI')
            values = values[..., 0, :]
            if not np.isfinite(values).all():
                raise ValueError('Nonfinite displacement field')
            displacement, inside = sample(values, image.affine, result)
            # scipy constant extension implements ITK identity outside the field.
            result += displacement * FLIP
            extrapolation.append({'path': str(path), 'outside_field_points': int((~inside).sum()),
                                  'total_points': int(inside.size)})
    if not np.isfinite(result).all():
        raise ValueError('Nonfinite composed coordinates')
    return result, extrapolation


def world_grid(image):
    return nib.affines.apply_affine(image.affine, np.moveaxis(np.indices(image.shape[:3]), 0, -1))


def export_map(directory, name, coords, warped, affine):
    shape = coords.shape[:3]
    np.stack([coords[..., c].ravel(order='F') for c in range(3)], axis=-1).astype('>f8').tofile(directory / (name + '.f64'))
    np.ones(int(np.prod(shape)), dtype=np.uint8).tofile(directory / (name + '.valid-u8'))
    warped.ravel(order='F').astype('>f8').tofile(directory / (name + '-warped.f64'))
    write_json(directory / (name + '.json'), {'shape': list(shape), 'index_to_ras_mm': affine.ravel().tolist(),
        'semantics': 'absolute RAS-mm pull coordinates, x-fastest interleaved xyz; all transform evaluations finite'})


def verify_affine(path, supplied):
    expected = np.linalg.inv(np.asarray(json.loads(Path(supplied).read_text())).reshape(4, 4))
    observed = affine_ras(path)
    points = np.array([[0., 0., 0.], [100., 0., 0.], [0., 100., 0.], [0., 0., 100.]])
    difference = nib.affines.apply_affine(observed, points) - nib.affines.apply_affine(expected, points)
    error = float(np.abs(difference).max())
    if error > 2e-5:
        raise ValueError(f'Supplied affine changed: point error {error}')
    return {'maximum_coefficient_abs': float(np.abs(observed - expected).max()),
            'four_affinely_independent_points_mm': points.tolist(), 'maximum_point_abs_mm': error,
            'supplied_sha256': sha(supplied), 'itk_affine_sha256': sha(path)}


def convert(data, reference, supplied, output):
    data, reference, supplied, output = map(Path, [data, reference, supplied, output])
    if output.exists():
        raise ValueError('Fresh export directory required')
    output.mkdir(parents=True)
    result = json.loads((reference / 'result.json').read_text())
    settings = json.loads((reference / 'settings.json').read_text())
    for role in ['fixed', 'moving']:
        if settings[role + '_sha256'] != sha(data / (role + '.nii.gz')):
            raise ValueError('Reference input identity mismatch: ' + role)
    if settings['initialization_sha256'] != sha(supplied):
        raise ValueError('Reference affine identity mismatch')
    affine_paths = set(p for k in ['forward', 'inverse'] for p in result[k] if p.endswith('.mat'))
    if len(affine_paths) != 1:
        raise ValueError('One shared collapsed affine required')
    report = {'schema': 'ants-canonical-pull-export-v1', 'affine': verify_affine(next(iter(affine_paths)), supplied),
              'source_sha256': sha(Path(__file__)), 'reference_result_sha256': sha(reference / 'result.json'),
              'settings_sha256': sha(reference / 'settings.json'), 'antspyx_version': ants.__version__, 'directions': {}}
    images = {role: nib.load(data / (role + '.nii.gz')) for role in ['fixed', 'moving']}
    native = {role: ants.image_read(str(data / (role + '.nii.gz')), pixeltype='double') for role in images}
    for target, source, key, inverse, historical in [('fixed', 'moving', 'forward', [False, False], 'warped.nii.gz'),
                                                   ('moving', 'fixed', 'inverse', [True, False], 'inverse-warped.nii.gz')]:
        paths = result[key]
        if len(paths) != 2:
            raise ValueError('Expected one displacement and one affine')
        grid = world_grid(images[target])
        coords, extrapolation = compose_points(grid, paths, inverse)
        # Independent native double-precision image warp, not an image generated
        # from our coordinate map. Historical float outputs are checked separately.
        warped = ants.apply_transforms(native[target], native[source], transformlist=paths,
                    whichtoinvert=inverse, interpolator='linear', singleprecision=False).numpy()
        name = 'ants-' + target + '-to-' + source
        export_map(output, name, coords, warped, images[target].affine)
        independently_warped, inside = sample(images[source].get_fdata(), images[source].affine, coords)
        difference = np.abs(warped - independently_warped)
        reload_error = float(difference[inside].max())
        # Point API uses float buffers; compare sampled points at an explicitly
        # separate conversion tolerance, while image reload retains its 1e-6 gate.
        probes = grid.reshape(-1, 3)[np.linspace(0, grid[..., 0].size - 1, 97, dtype=int)]
        direct = ants.apply_transforms_to_points(3, pd.DataFrame(probes * FLIP, columns=['x','y','z']),
                    paths, whichtoinvert=inverse)[['x','y','z']].to_numpy() * FLIP
        manual, _ = compose_points(probes, paths, inverse)
        point_error = float(np.abs(manual - direct).max())
        old_image = nib.load(reference / historical)
        old_delta = float(np.abs(old_image.get_fdata() - warped).max())
        conversion_pass = point_error <= 1e-4 and reload_error <= 1e-6 * max(1., float(np.abs(images[source].get_fdata()).max()))
        row = {'point_probe_max_abs_mm': point_error, 'point_probe_tolerance_mm': 1e-4,
               'independent_native_reload_max_abs': reload_error, 'historical_float_warp_max_abs_difference': old_delta,
               'conversion_pass': conversion_pass, 'extrapolation': extrapolation,
               'transform_sha256': {p: sha(p) for p in paths},
               'historical_image_sha256': sha(reference / historical),
               'map_sha256': sha(output / (name + '.f64'))}
        report['directions'][target + '-to-' + source] = row
    report['conversion_pass'] = all(r['conversion_pass'] for r in report['directions'].values())
    write_json(output / 'conversion.json', report)
    if not report['conversion_pass']:
        raise ValueError('Independent ANTs conversion parity failed; retain diagnostic export')
    return report


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for key in ['data', 'reference', 'affine', 'output']:
        p.add_argument('--' + key, type=Path, required=True)
    a = p.parse_args()
    r = convert(a.data, a.reference, a.affine, a.output)
    print(json.dumps({'conversion_pass': r['conversion_pass'], 'directions': r['directions']}, indent=2))


if __name__ == '__main__':
    main()
