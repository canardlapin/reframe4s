"""Locate exported endpoint folds relative to affine queries and brain masks.
Read-only input inspection; writes a separate JSON diagnostic artifact.
"""
import gzip
import json
import struct
import sys
from pathlib import Path

import numpy as np
from scipy.ndimage import binary_erosion, distance_transform_edt


def nifti_mask(path):
    with gzip.open(path, 'rb') as stream:
        payload = stream.read()
    endian = '<' if struct.unpack_from('<i', payload)[0] == 348 else '>'
    assert struct.unpack_from(endian + 'i', payload)[0] == 348
    dims = struct.unpack_from(endian + '8h', payload, 40)
    code = struct.unpack_from(endian + 'h', payload, 70)[0]
    offset = int(struct.unpack_from(endian + 'f', payload, 108)[0])
    dtype = {2: 'u1', 4: 'i2', 8: 'i4', 16: 'f4', 64: 'f8', 256: 'i1', 512: 'u2'}[code]
    shape = tuple(dims[1:4])
    values = np.frombuffer(payload, dtype=endian + dtype, count=int(np.prod(shape)), offset=offset)
    return values.reshape(shape[::-1]) > 0


base = Path(sys.argv[1])
data = Path(sys.argv[2])
output = Path(sys.argv[3])
rows = []
wm = json.loads((base / 'midpoint-fixed-residual.json').read_text())
wa = np.array(wm['index_to_ras_mm']).reshape(4, 4)
wb = np.linalg.inv(wa)
ws = np.array(wm['shape'])
for target, source in [('fixed', 'moving'), ('moving', 'fixed')]:
    stem = base / f'halfflow-rejected-{target}-to-{source}'
    meta = json.loads(stem.with_suffix('.json').read_text())
    shape = np.array(meta['shape'])
    affine = np.array(meta['index_to_ras_mm']).reshape(4, 4)
    field = np.fromfile(stem.with_suffix('.f64'), dtype='>f8').reshape(tuple(shape[::-1]) + (3,))
    valid = np.fromfile(str(stem) + '.valid-u8', dtype='u1').reshape(tuple(shape[::-1])).astype(bool)
    jacobian = np.stack([np.stack(np.gradient(field[..., c], edge_order=1)[::-1], axis=-1)
                         for c in range(3)], axis=-2) @ np.linalg.inv(affine[:3, :3])
    determinant = np.linalg.det(jacobian)
    del jacobian
    interior = binary_erosion(valid, np.ones((3, 3, 3)))
    negative = (determinant <= 0) & interior
    indices = np.argwhere(negative)[:, ::-1]
    world = indices @ affine[:3, :3].T + affine[:3, 3]
    arm = np.array(json.loads((base / f'midpoint-{target}-affine.json').read_text())).reshape(4, 4)
    inverse_arm = np.linalg.inv(arm)
    query = world @ inverse_arm[:3, :3].T + inverse_arm[:3, 3]
    query_index = query @ wb[:3, :3].T + wb[:3, 3]
    excess = np.maximum(np.maximum(-query_index, query_index - (ws - 1)), 0) * np.linalg.norm(wa[:3, :3], axis=0)
    brain = nifti_mask(data / f'{target}-mask.nii.gz')
    assert brain.shape == tuple(shape[::-1])
    distance = distance_transform_edt(~brain, sampling=np.linalg.norm(affine[:3, :3], axis=0)[::-1])
    zyx = np.unravel_index(np.where(interior, determinant, np.inf).argmin(), determinant.shape)
    ijk = np.array(zyx[::-1])
    worst_world = affine[:3, :3] @ ijk + affine[:3, 3]
    worst_query = inverse_arm[:3, :3] @ worst_world + inverse_arm[:3, 3]
    row = dict(direction=f'{target}-to-{source}', negative_count=int(negative.sum()),
        brain_negative_count=int((negative & brain).sum()),
        negative_native_voxel_bbox=[indices.min(0).tolist(), indices.max(0).tolist()] if len(indices) else None,
        negative_world_bbox=[world.min(0).tolist(), world.max(0).tolist()] if len(indices) else None,
        negative_affine_query_outside_count=int(np.any(excess > 1e-8, axis=1).sum()),
        negative_affine_query_excess_mm_percentiles=np.percentile(excess.max(1), [0, 50, 100]).tolist() if len(indices) else None,
        negative_distance_to_brain_mm_percentiles=np.percentile(distance[negative], [0, 50, 100]).tolist() if len(indices) else None,
        minimum=float(determinant[zyx]), minimum_voxel=ijk.tolist(), minimum_world=worst_world.tolist(),
        minimum_affine_query_world=worst_query.tolist(), minimum_saved_endpoint=field[zyx].tolist())
    rows.append(row)
with output.open('x') as stream:
    json.dump(rows, stream, indent=2)
    stream.write('\n')
print(json.dumps(rows, indent=2))
