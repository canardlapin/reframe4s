#!/usr/bin/env python3
"""Post-outcome confirmation with SciPy's independent multilinear interpolator."""
import argparse
from pathlib import Path

import numpy as np
import scipy
from scipy.interpolate import RegularGridInterpolator
from analyze_c04 import load, read, sha, write
from field_oracle import world_nodes


def interpolate(values, shape, affine, points):
    grid = tuple(np.arange(n, dtype=float) for n in reversed(shape))
    interpolator = RegularGridInterpolator(grid, values.reshape(*reversed(shape), 3),
                                          method='linear', bounds_error=False, fill_value=None)
    voxel = (points - affine[:3, 3]) @ np.linalg.inv(affine[:3, :3]).T
    return interpolator(voxel[:, ::-1])


def verify(study):
    p = read(study / 'protocol.json')
    results = read(study / 'results.json')
    if read(study / 'completion.json')['exit_code'] != 0 or len(results['variants']) != 6:
        raise ValueError('Complete six-variant study required')
    for path, digest in {**p['inputs'], **p['sources']}.items():
        if sha(path) != digest:
            raise ValueError('Frozen source or input changed')
    rows = []
    for result in results['variants']:
        arm, factor = result['arm'], result['factor']
        directory = study / (arm + '-factor' + str(factor))
        for name, digest in result['payload_sha256'].items():
            if sha(study / name) != digest:
                raise ValueError('Persisted inverse payload changed')
        roots = np.load(directory / 'inverse.npy', allow_pickle=False)
        available = np.load(directory / 'valid.npy', allow_pickle=False)
        forward, valid, shape, affine, _ = load(Path(p['fields'][arm]['forward']))
        if not valid.all() or not available.all():
            raise ValueError('This independent confirmation requires the recorded complete support')
        nodes = world_nodes(shape, affine)
        inverse_shape, inverse_affine = result['inverse_shape'], np.array(result['inverse_affine'])
        inverse_forward = interpolate(roots, inverse_shape, inverse_affine, forward)
        inverse_nodes = interpolate(roots, inverse_shape, inverse_affine, nodes)
        forward_inverse = interpolate(forward, shape, affine, inverse_nodes)
        measurements = {}
        for direction, coords in [('forward_then_inverse', inverse_forward), ('inverse_then_forward', forward_inverse)]:
            if not np.isfinite(coords).all() or len(coords) != 50653:
                raise ValueError('Nonfinite result or changed original denominator')
            maximum = float(np.linalg.norm(coords - nodes, axis=1).max())
            expected = result['composition_errors'][direction]['all']
            difference = abs(maximum - expected['maximum_mm'])
            if difference > 1e-10 or (maximum <= .2) != expected['passes_02mm']:
                raise ValueError('Independent interpolator differs from primary result')
            measurements[direction] = dict(eligible=len(coords), maximum_mm=maximum,
                                            primary_maximum_difference_mm=difference, passes_02mm=maximum <= .2)
        rows.append(dict(arm=arm, factor=factor, compositions=measurements))
    output = study / 'independent-interpolation-verification.json'
    if output.exists():
        raise FileExistsError(output)
    write(output, dict(schema='hfq-independent-inverse-interpolation-v1', source_sha256=sha(Path(__file__)),
          protocol_sha256=sha(study / 'protocol.json'), results_sha256=sha(study / 'results.json'),
          scipy=scipy.__version__, numpy=np.__version__, absolute_tolerance_mm=1e-10, rows=rows, passed=True,
          scope='Post-outcome interpolation confirmation only. Does not modify the prospective protocol, '
                'primary results, thresholds, root solves or declared variants.', qualification_claim=False))
    print('PASS independent SciPy interpolation: all six variants, twelve original-domain cycle maxima and verdicts')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--study', type=Path, required=True)
    args = parser.parse_args()
    verify(args.study.resolve())
