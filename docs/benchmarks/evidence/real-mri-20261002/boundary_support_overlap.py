"""Check initial/final brain support and the saved residual boundary cells.
Usage: python boundary_support_overlap.py RUN_DIRECTORY DATA_DIRECTORY OUTPUT_JSON
Labels are not read. The final state check is not a check of every optimizer step.
"""
import json
import sys
from pathlib import Path
import nibabel as nib
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[4] / 'benchmarks/flashalign'))
from real_image_court import read_pull, sample, write_json

run, data, output = map(Path, sys.argv[1:])
meta = json.loads((run / 'midpoint-fixed-residual.json').read_text())
full = np.array(meta['shape'])
base = np.array(meta['index_to_ras_mm']).reshape(4, 4)
rows = []
for shrink in (2, 1):
    shape = (full - 1) // shrink + 1
    affine = base.copy()
    affine[:3, :3] *= shrink
    indices = np.moveaxis(np.indices(shape), 0, -1)
    world = nib.affines.apply_affine(affine, indices)
    spacing = 1 / np.linalg.norm(np.linalg.inv(affine)[:3, :3], axis=1)
    def smooth(distance):
        t = np.clip(distance / 6, 0, 1)
        return t * t * (3 - 2 * t)
    low = (indices - 1) * spacing
    high = (shape - 2 - indices) * spacing
    weight = (smooth(low) * smooth(high)).prod(-1)
    old_weight = smooth(np.minimum(low, high)).prod(-1)
    assert np.array_equal(weight, old_weight), 'opposite collars overlap on this grid'
    for arm in ('fixed', 'moving'):
        image = nib.load(data / f'{arm}-mask.nii.gz')
        matrix = np.array(json.loads((run / f'midpoint-{arm}-affine.json').read_text())).reshape(4, 4)
        coordinates = [('initial', world)]
        if shrink == 1:
            residual = read_pull(run, f'midpoint-{arm}-residual')[0]
            coordinates.append(('final', residual))
        for state, points in coordinates:
            warped, valid = sample(image.get_fdata(), image.affine,
                                   nib.affines.apply_affine(matrix, points), order=0)
            mask = (warped > .5) & valid
            row = {'shrink': shrink, 'arm': arm, 'state': state,
                   'brain_work_voxels': int(mask.sum()),
                   'taper_overlap_voxels': int((mask & (weight < 1)).sum()),
                   'minimum_taper_over_brain': float(weight[mask].min()),
                   'separate_face_window_equals_original': True}
            if state == 'final':
                boundary = np.any((indices <= 1) | (indices >= shape - 2), axis=-1)
                row['boundary_cell_max_displacement_mm'] = float(np.linalg.norm(points - world, axis=-1)[boundary].max())
            rows.append(row)
write_json(output, rows)
print(json.dumps(rows, indent=2))
