"""Read-only, local inverse/representation probe; does not qualify a full map.

Usage: python3 targeted_residual_inverse_probe.py RUN_DIRECTORY OUTPUT_JSON
Forward maps are x-fastest, interleaved xyz, big-endian doubles in RAS mm.
Uses independent explicit trilinear values/Jacobians and scipy pointwise roots.
"""
import hashlib
import itertools
import json
import sys
from pathlib import Path

import numpy as np
import scipy
from scipy.optimize import root


class Map:
    def __init__(self, stem):
        self.stem = stem
        self.meta = json.loads(stem.with_suffix('.json').read_text())
        self.shape = np.array(self.meta['shape'])
        self.affine = np.array(self.meta['index_to_ras_mm']).reshape(4, 4)
        self.inverse_affine = np.linalg.inv(self.affine)
        storage_shape = tuple(self.shape[::-1])
        self.values = np.fromfile(stem.with_suffix('.f64'), dtype='>f8').reshape(storage_shape + (3,))
        self.valid = np.fromfile(str(stem) + '.valid-u8', dtype='u1').reshape(storage_shape)

    def world(self, index):
        return self.affine[:3, :3] @ index + self.affine[:3, 3]

    def index(self, world):
        return self.inverse_affine[:3, :3] @ world + self.inverse_affine[:3, 3]

    def evaluate(self, world):
        coordinate = self.index(world)
        lower = np.clip(np.floor(coordinate).astype(int), 0, self.shape - 2)
        fraction = coordinate - lower
        value = np.zeros(3)
        jacobian = np.zeros((3, 3))
        supported = True
        for corner in itertools.product([0, 1], repeat=3):
            corner = np.array(corner)
            weights = np.where(corner, fraction, 1 - fraction)
            index = lower + corner
            tap = self.values[index[2], index[1], index[0]]
            supported &= bool(self.valid[index[2], index[1], index[0]])
            value += np.prod(weights) * tap
            for axis in range(3):
                derivative_weight = (1 if corner[axis] else -1) * np.prod(np.delete(weights, axis))
                jacobian[:, axis] += derivative_weight * tap
        return value, jacobian @ self.inverse_affine[:3, :3], supported

    def solve(self, target, seed):
        result = root(lambda point: self.evaluate(point)[0] - target, seed,
                      jac=lambda point: self.evaluate(point)[1], tol=1e-11)
        value, jacobian, supported = self.evaluate(result.x)
        error = float(np.linalg.norm(value - target))
        if error > 1e-8 or not supported:
            raise RuntimeError((target.tolist(), result.message, error, supported))
        return result.x, error, jacobian


def probe(field, arm, index):
    sx, sy, _ = field.shape
    voxel = np.array([index % sx, index // sx % sy, index // (sx * sy)])
    point = field.world(voxel)
    forward, jacobian, _ = field.evaluate(point)
    inverse, residual, root_jacobian = field.solve(point, point)
    exact_back, _, _ = field.solve(forward, point)
    interpolation = []
    for step in [1., .5, .25, .125]:
        coordinate = field.index(forward)
        lower = np.floor(coordinate / step) * step
        fraction = (coordinate - lower) / step
        represented_inverse = np.zeros(3)
        maximum_root_error = 0.
        for corner in itertools.product([0, 1], repeat=3):
            corner = np.array(corner)
            weight = np.prod(np.where(corner, fraction, 1 - fraction))
            target = field.world(lower + step * corner)
            solution, error, _ = field.solve(target, point)
            represented_inverse += weight * solution
            maximum_root_error = max(maximum_root_error, error)
        interpolation.append(dict(inverse_grid_axis_spacing_mm=(step * np.linalg.norm(field.affine[:3, :3], axis=0)).tolist(),
            inverse_of_forward_error_mm=float(np.linalg.norm(represented_inverse - point)),
            maximum_corner_root_residual_mm=maximum_root_error))
    minimum = float('inf')
    minimum_at = None
    negative = 0
    for delta in itertools.product(np.arange(-2, 2.01, .25), repeat=3):
        location = voxel + np.array(delta) + 1e-7
        determinant = float(np.linalg.det(field.evaluate(field.world(location))[1]))
        negative += determinant <= 0
        if determinant < minimum:
            minimum, minimum_at = determinant, location.tolist()
    solutions = []
    for delta in itertools.product([-3., 0., 3.], repeat=3):
        solution, _, _ = field.solve(forward, point + np.array(delta))
        if all(np.linalg.norm(solution - prior) > 1e-5 for prior in solutions):
            solutions.append(solution)
    return dict(arm=arm, index=index, voxel=voxel.tolist(), world=point.tolist(),
        forward_at_world=forward.tolist(), pointwise_inverse_at_world=inverse.tolist(),
        pointwise_forward_of_inverse_residual_mm=residual,
        pointwise_inverse_of_forward_error_mm=float(np.linalg.norm(exact_back - point)),
        forward_cell_jacobian_det=float(np.linalg.det(jacobian)),
        root_cell_jacobian_det=float(np.linalg.det(root_jacobian)),
        root_cell_singular_values=np.linalg.svd(root_jacobian, compute_uv=False).tolist(),
        inverse_interpolation_experiment=interpolation,
        local_cell_jacobian_minimum=minimum, local_minimum_at_voxel=minimum_at,
        local_nonpositive_samples=int(negative), local_jacobian_sample_count=17**3,
        root_multistart_count=27, distinct_roots_of_forward_at_world=[point.tolist() for point in solutions])


base = Path(sys.argv[1])
output = Path(sys.argv[2])
result = dict(schema='targeted-residual-inverse-probe-v1', numpy=np.__version__, scipy=scipy.__version__,
    limitation='Four targeted locations only; sampled local Jacobians and multistart roots are not a global injectivity proof.',
    inputs={}, probes=[])
for arm in ['fixed', 'moving']:
    stem = base / f'midpoint-{arm}-residual'
    for extension in ['.json', '.f64', '.valid-u8']:
        path = Path(str(stem) + extension)
        result['inputs'][str(path)] = hashlib.sha256(path.read_bytes()).hexdigest()
    field = Map(stem)
    result['probes'].extend(probe(field, arm, index) for index in [224552, 228705])
with output.open('x') as stream:
    json.dump(result, stream, indent=2)
    stream.write('\n')
print(output)
