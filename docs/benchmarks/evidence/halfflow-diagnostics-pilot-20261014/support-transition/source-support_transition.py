#!/usr/bin/env python3
"""Reconstruct the pilot's support population from saved states and input masks.

This independent NumPy/SciPy audit matches the declared scalar support counts
and sums before interpreting them. It reads no anatomical label volumes.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import itertools
import json
from pathlib import Path
import shutil

import nibabel as nib
import numpy as np
from scipy import ndimage


def read(path):
    return json.loads(path.read_text())


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1048576), b''):
            h.update(chunk)
    return h.hexdigest()


def smooth(array, spacing, sigma):
    result = array.astype(float)
    for axis in range(3):
        width = sigma / spacing[axis]
        if width <= 1e-12:
            continue
        radius = max(1, int(np.ceil(3 * width)))
        offsets = np.arange(-radius, radius + 1)
        weights = np.exp(-offsets**2 / (2 * width**2))
        weights /= weights.sum()
        result = ndimage.convolve1d(result, weights, axis=axis, mode='constant', cval=0)
    return result


def pulled_validity(source_valid, matrix, coordinates, map_valid):
    """Require every nonzero trilinear contributor, including domain bounds."""
    voxels = coordinates @ matrix[:3, :3].T + matrix[:3, 3]
    nearest = np.rint(voxels)
    voxels = np.where(np.abs(voxels-nearest) <= 1e-10, nearest, voxels)
    finite = np.isfinite(voxels).all(axis=1)
    if not finite.all():
        voxels[~finite] = 0
    lower = np.floor(voxels).astype(np.int64)
    fraction = voxels-lower
    valid = map_valid & finite
    shape = np.asarray(source_valid.shape)
    for offset in itertools.product([0, 1], repeat=3):
        weight = np.prod(np.where(np.asarray(offset), fraction, 1-fraction), axis=1)
        indices = lower + offset
        inside = ((indices >= 0) & (indices < shape)).all(axis=1)
        good = np.zeros(len(valid), dtype=bool)
        good[inside] = source_valid[tuple(indices[inside].T)]
        valid &= (weight == 0) | good
    return valid


def audit(study, output):
    if output.exists():
        raise FileExistsError(output)
    protocol = read(study/'engine/protocol.json')
    case = next(row for row in protocol['cases'] if row['id'] == 'oasis-11-to-12')
    data = Path(case['data'])
    base = study/case['id']/'selected-control'
    events = [json.loads(line) for line in (base/'checkpoints/events.jsonl').read_text().splitlines()]
    plan = (base/'plan.txt').read_text()
    # Deliberately narrow to the pilot whose source and plan were already sealed.
    for fragment in ['HalfFlowCcLevel(2,1.0,', 'HalfFlowCcLevel(1,0.0,',
                     ',10.0,1.0,20,34,TrueNeighborhoodCc,1.5,false)',
                     ',6.0,0.6,20,34,TrueNeighborhoodCc,1.5,false)', ')),1.5,']:
        if fragment not in plan:
            raise ValueError('Unexpected pilot plan: ' + fragment)
    levels = [(22, 2, 1.0, 1.0), (23, 1, 0.0, 0.6)]
    inputs = {str(base/'plan.txt'): sha(base/'plan.txt'),
              str(base/'checkpoints/events.jsonl'): sha(base/'checkpoints/events.jsonl')}
    sealed = read(study/'engine/inputs.json')
    images = {}
    for arm in ['fixed', 'moving']:
        image_path = data/(arm+'.nii.gz')
        image = nib.load(image_path)
        assert sha(image_path) == sealed[str(image_path)]
        inputs[str(image_path)] = sha(image_path)
        if arm == 'fixed':
            mask_path = data/'fixed-mask.nii.gz'
            assert sha(mask_path) == sealed[str(mask_path)]
            inputs[str(mask_path)] = sha(mask_path)
            mask = nib.load(mask_path).get_fdata() > 0
        else:
            # The frozen runner constructs moving RegistrationImage with All validity.
            mask = np.ones(image.shape, dtype=bool)
        assert np.isfinite(image.get_fdata()).all()
        images[arm] = (mask, image.affine)
    output.mkdir(parents=True)
    shutil.copyfile(__file__, output/'source-support_transition.py')
    contract = {'schema': 'hfq-pilot-support-audit-v1',
        'created_at': datetime.now(timezone.utc).isoformat(), 'source_sha256': sha(Path(__file__)),
        'inputs': inputs, 'levels': levels,
        'source_validity': {'fixed': 'prepared fixed mask', 'moving': 'all finite input voxels'},
        'support_sigma_mm': 1.5, 'pyramid_minimum_weight': 1e-8,
        'support_sum_absolute_tolerance': 1e-7, 'support_count_tolerance': 0,
        'scope': 'Descriptive reconstruction; no fitting, labels or policy selection'}
    (output/'protocol.json').write_text(json.dumps(contract, indent=2)+'\n')
    rows = []
    for serial, shrink, sigma, maximum_step in levels:
        event = next(row for row in events if row['serial'] == serial)
        checkpoint = base/'checkpoints'/event['directory']
        row = {'serial': serial, 'shrink': shrink, 'pyramid_sigma_mm': sigma, 'arms': {}}
        warped = {}
        for arm, (native_mask, native_grid) in images.items():
            spacing = np.linalg.norm(native_grid[:3, :3], axis=0)
            if shrink == 1 and sigma == 0:
                source_valid = native_mask
            else:
                weight = smooth(native_mask, spacing, sigma)
                source_valid = weight[::shrink, ::shrink, ::shrink] >= 1e-8
            grid = native_grid.copy()
            grid[:3, :3] *= shrink
            name = 'midpoint-'+arm+'-residual'
            paths = [checkpoint/(name+suffix) for suffix in ['.json', '.f64', '.valid-u8']]
            affine_path = checkpoint/('midpoint-'+arm+'-affine.json')
            meta = read(paths[0])
            coordinates = np.fromfile(paths[1], dtype='>f8').reshape(-1, 3)
            map_valid = np.fromfile(paths[2], dtype=np.uint8) > 0
            matrix = np.linalg.inv(grid) @ np.asarray(read(affine_path)).reshape(4, 4)
            valid = pulled_validity(source_valid, matrix, coordinates, map_valid)
            warped[arm] = valid.reshape(meta['shape'], order='F')
            row['arms'][arm] = {'native_valid': int(native_mask.sum()), 'native_total': native_mask.size,
                'pyramid_valid': int(source_valid.sum()), 'pyramid_total': source_valid.size,
                'warped_valid': int(valid.sum()), 'warped_total': valid.size}
            for path in paths+[affine_path]:
                inputs[str(path)] = sha(path)
        grid = np.asarray(meta['index_to_ras_mm']).reshape(4, 4)
        spacing = np.linalg.norm(grid[:3, :3], axis=0)
        margin = max(1, int(np.ceil(.5 * maximum_step / spacing.min())))
        joint = warped['fixed'] & warped['moving']
        safe = ndimage.minimum_filter(joint, size=2*margin+1, mode='constant', cval=0)
        support = np.where(safe, np.clip(smooth(safe, spacing, 1.5), 0, 1), 0)
        observed = event['info']['after']['support']
        row.update(joint_valid=int(joint.sum()), safe_margin_voxels=margin,
                   eroded_valid=int(safe.sum()), support_positive=int((support > 0).sum()),
                   support_sum=float(support.sum()), total=joint.size, observed=observed)
        row['matches_recorded_support'] = (row['support_positive'] == observed['positive']
            and abs(row['support_sum']-observed['sum']) <= contract['support_sum_absolute_tolerance'])
        rows.append(row)
    result = {'protocol_sha256': sha(output/'protocol.json'), 'inputs': inputs, 'rows': rows,
              'pass': all(row['matches_recorded_support'] for row in rows), 'qualification_claim': False}
    (output/'result.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps({'pass': result['pass'], 'rows': rows}, indent=2))
    if not result['pass']:
        raise SystemExit(2)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--study', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    audit(args.study.resolve(), args.output.resolve())
