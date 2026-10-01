#!/usr/bin/env python3
"""Generate small, candidate-independent Flashalign development fixtures.

The oracle evaluates an analytic continuous phantom and closed-form gradients
directly in world millimetres.  It does not import or call reframe4s, image4s,
Ravel, Gale, or a candidate interpolation/deformation implementation.
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Iterable, Sequence


GENERATOR_ID = "flashalign-development-fixtures"
GENERATOR_VERSION = "1.0.0"


def matmul4(a: Sequence[Sequence[float]], b: Sequence[Sequence[float]]) -> list[list[float]]:
    return [
        [sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)]
        for i in range(4)
    ]


def apply4(matrix: Sequence[Sequence[float]], point: Sequence[float]) -> list[float]:
    homogeneous = [point[0], point[1], point[2], 1.0]
    mapped = [sum(matrix[i][j] * homogeneous[j] for j in range(4)) for i in range(4)]
    if mapped[3] == 0.0:
        raise ValueError("homogeneous point mapped to zero weight")
    return [mapped[i] / mapped[3] for i in range(3)]


def rotation_x(angle: float) -> list[list[float]]:
    c, s = math.cos(angle), math.sin(angle)
    return [[1.0, 0.0, 0.0, 0.0], [0.0, c, -s, 0.0], [0.0, s, c, 0.0], [0.0, 0.0, 0.0, 1.0]]


def rotation_y(angle: float) -> list[list[float]]:
    c, s = math.cos(angle), math.sin(angle)
    return [[c, 0.0, s, 0.0], [0.0, 1.0, 0.0, 0.0], [-s, 0.0, c, 0.0], [0.0, 0.0, 0.0, 1.0]]


def rotation_z(angle: float) -> list[list[float]]:
    c, s = math.cos(angle), math.sin(angle)
    return [[c, -s, 0.0, 0.0], [s, c, 0.0, 0.0], [0.0, 0.0, 1.0, 0.0], [0.0, 0.0, 0.0, 1.0]]


def rigid_matrix(degrees_xyz: Sequence[float], translation: Sequence[float]) -> list[list[float]]:
    radians = [math.radians(value) for value in degrees_xyz]
    rotation = matmul4(rotation_z(radians[2]), matmul4(rotation_y(radians[1]), rotation_x(radians[0])))
    for axis in range(3):
        rotation[axis][3] = translation[axis]
    return rotation


def gaussian(point: Sequence[float], center: Sequence[float], scales: Sequence[float]) -> tuple[float, list[float]]:
    normalized = [(point[i] - center[i]) / scales[i] for i in range(3)]
    value = math.exp(-0.5 * sum(value_i * value_i for value_i in normalized))
    gradient = [-(point[i] - center[i]) / (scales[i] * scales[i]) * value for i in range(3)]
    return value, gradient


def phantom(point: Sequence[float]) -> tuple[float, list[float]]:
    """Asymmetric analytic scalar field and its exact world gradient."""
    g1, dg1 = gaussian(point, (-6.0, 3.0, 4.0), (8.0, 5.0, 6.0))
    g2, dg2 = gaussian(point, (7.0, -5.0, -2.0), (4.0, 7.0, 5.0))
    phase = 0.11 * point[0] - 0.07 * point[1] + 0.05 * point[2]
    ripple = math.sin(phase)
    value = 1.7 + 0.9 * g1 - 0.55 * g2 + 0.08 * ripple + 0.003 * point[0]
    gradient = [
        0.9 * dg1[0] - 0.55 * dg2[0] + 0.08 * math.cos(phase) * 0.11 + 0.003,
        0.9 * dg1[1] - 0.55 * dg2[1] - 0.08 * math.cos(phase) * 0.07,
        0.9 * dg1[2] - 0.55 * dg2[2] + 0.08 * math.cos(phase) * 0.05,
    ]
    return value, gradient


def patch(center: Sequence[float], spacing: float) -> dict[str, object]:
    offsets = [
        [dx, dy, dz]
        for dx in (-spacing, 0.0, spacing)
        for dy in (-spacing, 0.0, spacing)
        for dz in (-spacing, 0.0, spacing)
    ]
    values = [phantom([center[i] + offset[i] for i in range(3)])[0] for offset in offsets]
    mean = sum(values) / len(values)
    centered = [value - mean for value in values]
    norm = math.sqrt(sum(value * value for value in centered))
    return {
        "center_world_mm": list(center),
        "offsets_world_mm": offsets,
        "moving_values": values,
        "moving_mean": mean,
        "moving_contrast_norm": norm,
        "moving_u": [value / norm for value in centered],
    }


def mapped_landmarks(matrix: Sequence[Sequence[float]], points: Iterable[Sequence[float]]) -> list[dict[str, object]]:
    return [
        {"moving_world_mm": list(point), "fixed_world_mm": apply4(matrix, point)}
        for point in points
    ]


def build_fixture() -> dict[str, object]:
    identity = [[1.0, 0.0, 0.0, 0.0], [0.0, 1.0, 0.0, 0.0], [0.0, 0.0, 1.0, 0.0], [0.0, 0.0, 0.0, 1.0]]
    translate_x = [[1.0, 0.0, 0.0, 10.0], [0.0, 1.0, 0.0, 0.0], [0.0, 0.0, 1.0, 0.0], [0.0, 0.0, 0.0, 1.0]]
    rigid = rigid_matrix((7.0, -5.0, 11.0), (4.0, -3.5, 2.0))
    affine = [
        [1.03, 0.04, -0.02, 3.0],
        [0.01, 0.97, 0.03, -4.0],
        [-0.02, 0.02, 1.05, 1.5],
        [0.0, 0.0, 0.0, 1.0],
    ]
    landmarks = [
        (-12.0, -8.0, -5.0),
        (-7.0, 9.0, 2.0),
        (-1.0, -4.0, 11.0),
        (0.0, 0.0, 0.0),
        (5.0, 7.0, -9.0),
        (11.0, -6.0, 4.0),
        (14.0, 10.0, 8.0),
    ]
    probe_points = [
        (-10.25, -7.5, -3.0),
        (-6.0, 3.0, 4.0),
        (-2.5, 8.25, 5.75),
        (0.0, 0.0, 0.0),
        (3.5, -2.0, 9.0),
        (7.0, -5.0, -2.0),
        (9.75, 6.5, -7.25),
        (13.0, -8.0, 6.0),
    ]
    oblique = matmul4(rotation_z(math.radians(23.0)), rotation_x(math.radians(-17.0)))
    for axis, spacing in enumerate((1.7, 2.3, 3.1)):
        for row in range(3):
            oblique[row][axis] *= spacing
    for axis, origin in enumerate((-17.0, 8.0, 31.0)):
        oblique[axis][3] = origin

    headers = [
        {"id": "isotropic-origin", "shape": [17, 17, 17], "voxel_to_world": [[2.0, 0.0, 0.0, -16.0], [0.0, 2.0, 0.0, -16.0], [0.0, 0.0, 2.0, -16.0], [0.0, 0.0, 0.0, 1.0]]},
        {"id": "anisotropic-offset", "shape": [19, 13, 11], "voxel_to_world": [[1.5, 0.0, 0.0, -13.5], [0.0, 2.5, 0.0, -15.0], [0.0, 0.0, 3.5, -17.5], [0.0, 0.0, 0.0, 1.0]]},
        {"id": "axis-permuted-reflected", "shape": [13, 15, 17], "voxel_to_world": [[0.0, 0.0, -2.0, 16.0], [0.0, 2.0, 0.0, -14.0], [2.0, 0.0, 0.0, -12.0], [0.0, 0.0, 0.0, 1.0]]},
        {"id": "oblique-anisotropic", "shape": [21, 15, 13], "voxel_to_world": oblique},
        {"id": "sheared-offset", "shape": [17, 15, 13], "voxel_to_world": [[1.8, 0.25, -0.1, -18.0], [0.1, 2.1, 0.2, 7.0], [0.0, -0.15, 2.7, -11.0], [0.0, 0.0, 0.0, 1.0]]},
    ]

    return {
        "fixture_set": "flashalign-development-fixtures-v1",
        "generator": {"id": GENERATOR_ID, "version": GENERATOR_VERSION},
        "units": {"world": "millimetres", "angles": "degrees"},
        "phantom": {
            "id": "asymmetric-analytic-mixture-v1",
            "definition": "1.7 + 0.9*G((-6,3,4),(8,5,6)) - 0.55*G((7,-5,-2),(4,7,5)) + 0.08*sin(0.11*x-0.07*y+0.05*z) + 0.003*x",
            "probe_values_and_world_gradients": [
                {"world_mm": list(point), "value": phantom(point)[0], "gradient_per_mm": phantom(point)[1]}
                for point in probe_points
            ],
        },
        "headers": headers,
        "transforms": [
            {"id": "identity", "model": "rigid", "moving_to_fixed": identity, "landmarks": mapped_landmarks(identity, landmarks)},
            {"id": "translate-positive-x-10mm", "model": "rigid", "moving_to_fixed": translate_x, "landmarks": mapped_landmarks(translate_x, landmarks)},
            {"id": "rigid-xyz-7-minus5-11", "model": "rigid", "moving_to_fixed": rigid, "landmarks": mapped_landmarks(rigid, landmarks)},
            {"id": "mild-affine", "model": "affine", "moving_to_fixed": affine, "landmarks": mapped_landmarks(affine, landmarks)},
        ],
        "patches": {
            "informative": [patch((-6.0, 3.0, 4.0), 2.0), patch((7.0, -5.0, -2.0), 2.0), patch((1.0, 2.0, -3.0), 3.0)],
            "degenerate": {"sample_count": 27, "values": [5.0] * 27, "expected": "contrast-gated-outlier"},
            "invalid_support": {"sample_count": 27, "invalid_indices": [0, 9, 18], "expected": "constant-outlier-cost"},
        },
        "finite_difference_step_sweeps": {
            "algebra_directional": [1.0e-3, 3.0e-4, 1.0e-4, 3.0e-5, 1.0e-5, 3.0e-6, 1.0e-6, 3.0e-7, 1.0e-7],
            "translation_mm": [1.0e-2, 3.0e-3, 1.0e-3, 3.0e-4, 1.0e-4, 3.0e-5, 1.0e-5],
            "rotation_radians": [1.0e-3, 3.0e-4, 1.0e-4, 3.0e-5, 1.0e-5, 3.0e-6, 1.0e-6],
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(build_fixture(), indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
