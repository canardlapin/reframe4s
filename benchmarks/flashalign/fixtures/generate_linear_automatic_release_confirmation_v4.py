#!/usr/bin/env python3
"""Generate the third unopened Flashalign automatic linear release court.

The exact-recovery rows are image-backed and interpolant-identifiable by
construction. Moving voxels are produced by an independent standard-library
trilinear sampler over the float32 stored fixed grid, followed by one positive
affine intensity map. Candidate code is neither imported nor executed.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
import pathlib
import struct
from dataclasses import dataclass
from typing import Callable


VERSION = "4.0.0"
SUBJECT_INDEX_OFFSET = 12
SUBJECTS = tuple(f"r{index:02d}" for index in range(13, 19))
COHORTS = ("exact-core", "capture-range", "partial-slab", "nonlinear-contrast")
MODELS = ("rigid", "affine")
SPACING_MM = 2.0
FULL_SHAPE = (37, 37, 37)
FULL_ORIGIN = (-36.0, -36.0, -36.0)
SLAB_SHAPE = (37, 37, 21)
SLAB_ORIGIN = (-36.0, -36.0, -20.0)
AFFINE_INTENSITY_OFFSET = 80.0
AFFINE_INTENSITY_SCALE = 1.17
ORACLE_MAX_POINTS = 4096
ORACLE_MIN_CORRELATION = 0.999999999
ORACLE_MIN_PERTURBATION_DROP = 1e-8


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def f32(value: float) -> float:
    return struct.unpack("<f", struct.pack("<f", value))[0]


def matrix_multiply(left: tuple[float, ...], right: tuple[float, ...]) -> tuple[float, ...]:
    return tuple(
        sum(left[row * 4 + k] * right[k * 4 + column] for k in range(4))
        for row in range(4)
        for column in range(4)
    )


def rigid_matrix(
    rx: float,
    ry: float,
    rz: float,
    tx: float,
    ty: float,
    tz: float,
) -> tuple[float, ...]:
    cx, sx = math.cos(rx), math.sin(rx)
    cy, sy = math.cos(ry), math.sin(ry)
    cz, sz = math.cos(rz), math.sin(rz)
    x = (1.0, 0.0, 0.0, 0.0, 0.0, cx, -sx, 0.0, 0.0, sx, cx, 0.0, 0.0, 0.0, 0.0, 1.0)
    y = (
        cy, 0.0, sy, 0.0,
        0.0, 1.0, 0.0, 0.0,
        -sy, 0.0, cy, 0.0,
        0.0, 0.0, 0.0, 1.0,
    )
    z = (
        cz, -sz, 0.0, 0.0,
        sz, cz, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0,
    )
    result = list(matrix_multiply(z, matrix_multiply(y, x)))
    result[3], result[7], result[11] = tx, ty, tz
    return tuple(result)


def truth(subject_index: int, cohort: str, model: str) -> tuple[float, ...]:
    draw_index = subject_index - SUBJECT_INDEX_OFFSET
    sign = -1.0 if draw_index % 2 else 1.0
    if cohort == "capture-range":
        degrees = 11.8 + 1.3 * draw_index
        translation = (10.0 * sign, -7.2 + 0.38 * draw_index, 5.1 - 0.21 * draw_index)
    elif cohort == "partial-slab":
        degrees = 4.3 + 0.41 * draw_index
        translation = (4.4 * sign, -2.7 + 0.16 * draw_index, 2.2)
    elif cohort == "nonlinear-contrast":
        degrees = 3.2 + 0.31 * draw_index
        translation = (3.8 * sign, 2.2 - 0.12 * draw_index, -1.7)
    else:
        degrees = 2.5 + 0.37 * draw_index
        translation = (3.4 * sign, -1.7 + 0.17 * draw_index, 1.3)
    angle = math.radians(degrees)
    pose = rigid_matrix(0.34 * angle, -0.41 * angle, sign * angle, *translation)
    if model == "rigid":
        return pose
    strain = (
        1.0 + 0.006 * sign, 0.004, -0.002, 0.0,
        -0.003, 1.0 - 0.005 * sign, 0.003, 0.0,
        0.002, -0.003, 1.004, 0.0,
        0.0, 0.0, 0.0, 1.0,
    )
    return matrix_multiply(pose, strain)


def apply(matrix: tuple[float, ...], point: tuple[float, float, float]) -> tuple[float, float, float]:
    x, y, z = point
    return (
        matrix[0] * x + matrix[1] * y + matrix[2] * z + matrix[3],
        matrix[4] * x + matrix[5] * y + matrix[6] * z + matrix[7],
        matrix[8] * x + matrix[9] * y + matrix[10] * z + matrix[11],
    )


def fixed_value(subject_index: int, x: float, y: float, z: float) -> float:
    phase = 0.23 + 0.41 * subject_index

    def blob(cx: float, cy: float, cz: float, sx: float, sy: float, sz: float) -> float:
        return math.exp(
            -0.5
            * (((x - cx) / sx) ** 2 + ((y - cy) / sy) ** 2 + ((z - cz) / sz) ** 2)
        )

    signal = (
        1.28 * blob(-8.0 + 0.55 * subject_index, -6.0, 7.0, 5.5, 7.0, 6.5)
        - 0.84 * blob(9.0, -7.0 + 0.42 * subject_index, 2.0, 7.0, 4.5, 5.0)
        + 0.81 * blob(4.0, 11.0, -7.0 + 0.26 * subject_index, 4.5, 4.5, 6.5)
        + 0.62 * blob(-12.0, 7.0, -4.0, 4.5, 5.5, 4.5)
        + 0.12 * math.sin(0.17 * x + 0.13 * y + 0.08 * z + phase)
        + 0.10 * math.cos(0.010 * x * y - 0.007 * y * z + phase)
        + 0.0018 * x - 0.0013 * y + 0.0011 * z
    )
    return 500.0 + 310.0 * signal


@dataclass(frozen=True)
class Grid:
    shape: tuple[int, int, int]
    spacing: float
    origin: tuple[float, float, float]
    values: tuple[float, ...]

    def index(self, i: int, j: int, k: int) -> int:
        return i + self.shape[0] * (j + self.shape[1] * k)

    def at(self, i: int, j: int, k: int) -> float:
        return self.values[self.index(i, j, k)]

    def world(self, i: int, j: int, k: int) -> tuple[float, float, float]:
        return (
            self.origin[0] + self.spacing * i,
            self.origin[1] + self.spacing * j,
            self.origin[2] + self.spacing * k,
        )


def build_grid(
    shape: tuple[int, int, int],
    spacing: float,
    origin: tuple[float, float, float],
    sample: Callable[[float, float, float], float],
) -> Grid:
    values = []
    for k in range(shape[2]):
        for j in range(shape[1]):
            for i in range(shape[0]):
                values.append(f32(sample(*(
                    origin[0] + spacing * i,
                    origin[1] + spacing * j,
                    origin[2] + spacing * k,
                ))))
    return Grid(shape, spacing, origin, tuple(values))


def trilinear(grid: Grid, point: tuple[float, float, float]) -> float | None:
    coordinates = tuple((point[axis] - grid.origin[axis]) / grid.spacing for axis in range(3))
    base = tuple(math.floor(value) for value in coordinates)
    if any(base[axis] < 0 or base[axis] + 1 >= grid.shape[axis] for axis in range(3)):
        return None
    fraction = tuple(coordinates[axis] - base[axis] for axis in range(3))
    value = 0.0
    for dz in (0, 1):
        wz = fraction[2] if dz else 1.0 - fraction[2]
        for dy in (0, 1):
            wy = fraction[1] if dy else 1.0 - fraction[1]
            for dx in (0, 1):
                wx = fraction[0] if dx else 1.0 - fraction[0]
                value += wx * wy * wz * grid.at(base[0] + dx, base[1] + dy, base[2] + dz)
    return value


def write_nifti(path: pathlib.Path, grid: Grid) -> None:
    header = bytearray(352)
    struct.pack_into("<i", header, 0, 348)
    struct.pack_into("<8h", header, 40, 3, *grid.shape, 1, 1, 1, 1)
    struct.pack_into("<h", header, 70, 16)
    struct.pack_into("<h", header, 72, 32)
    struct.pack_into("<8f", header, 76, 1.0, grid.spacing, grid.spacing, grid.spacing, 0.0, 0.0, 0.0, 0.0)
    struct.pack_into("<f", header, 108, 352.0)
    struct.pack_into("<f", header, 112, 1.0)
    struct.pack_into("<B", header, 123, 2)
    struct.pack_into("<h", header, 252, 0)
    struct.pack_into("<h", header, 254, 1)
    struct.pack_into("<4f", header, 280, grid.spacing, 0.0, 0.0, grid.origin[0])
    struct.pack_into("<4f", header, 296, 0.0, grid.spacing, 0.0, grid.origin[1])
    struct.pack_into("<4f", header, 312, 0.0, 0.0, grid.spacing, grid.origin[2])
    header[344:348] = b"n+1\0"
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=6) as stream:
            stream.write(header)
            for offset in range(0, len(grid.values), grid.shape[0]):
                row = grid.values[offset : offset + grid.shape[0]]
                stream.write(struct.pack(f"<{len(row)}f", *row))


def moving_grid(fixed: Grid, matrix: tuple[float, ...], cohort: str) -> Grid:
    shape = SLAB_SHAPE if cohort == "partial-slab" else FULL_SHAPE
    origin = SLAB_ORIGIN if cohort == "partial-slab" else FULL_ORIGIN

    def sample(x: float, y: float, z: float) -> float:
        fixed_sample = trilinear(fixed, apply(matrix, (x, y, z)))
        if fixed_sample is None:
            return math.nan
        if cohort == "nonlinear-contrast":
            centered = fixed_sample - 500.0
            return 230.0 + 24.0 * math.copysign(math.sqrt(abs(centered)), centered)
        return AFFINE_INTENSITY_OFFSET + AFFINE_INTENSITY_SCALE * fixed_sample

    return build_grid(shape, SPACING_MM, origin, sample)


def correlation(left: list[float], right: list[float]) -> float:
    mean_left = sum(left) / len(left)
    mean_right = sum(right) / len(right)
    ll = rr = lr = 0.0
    for x, y in zip(left, right):
        cx, cy = x - mean_left, y - mean_right
        ll += cx * cx
        rr += cy * cy
        lr += cx * cy
    return lr / math.sqrt(ll * rr)


def perturbations(model: str) -> list[tuple[str, tuple[float, ...]]]:
    values = []
    for axis, name in enumerate(("x", "y", "z")):
        for sign in (-1.0, 1.0):
            translation = [0.0, 0.0, 0.0]
            translation[axis] = sign * 0.1
            values.append((f"translate-{name}-{sign:+.0f}", rigid_matrix(0.0, 0.0, 0.0, *translation)))
            angles = [0.0, 0.0, 0.0]
            angles[axis] = sign * math.radians(0.3)
            values.append((f"rotate-{name}-{sign:+.0f}", rigid_matrix(*angles, 0.0, 0.0, 0.0)))
    if model == "affine":
        for axis, name in enumerate(("x", "y", "z")):
            for sign in (-1.0, 1.0):
                matrix = [1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0]
                matrix[axis * 4 + axis] += sign * 0.005
                values.append((f"scale-{name}-{sign:+.0f}", tuple(matrix)))
    return values


def oracle(
    fixed: Grid,
    moving: Grid,
    matrix: tuple[float, ...],
    model: str,
) -> dict[str, object]:
    states = [(name, matrix_multiply(delta, matrix)) for name, delta in perturbations(model)]
    eligible = []
    for k in range(moving.shape[2]):
        for j in range(moving.shape[1]):
            for i in range(moving.shape[0]):
                value = moving.at(i, j, k)
                if not math.isfinite(value):
                    continue
                point = moving.world(i, j, k)
                if trilinear(fixed, apply(matrix, point)) is None:
                    continue
                if all(trilinear(fixed, apply(candidate, point)) is not None for _, candidate in states):
                    eligible.append((point, value))
    stride = max(1, len(eligible) // ORACLE_MAX_POINTS)
    selected = eligible[::stride][:ORACLE_MAX_POINTS]
    moving_values = [value for _, value in selected]
    truth_fixed = [trilinear(fixed, apply(matrix, point)) for point, _ in selected]
    if any(value is None for value in truth_fixed):
        raise AssertionError("oracle admitted an unsupported truth sample")
    truth_values = [float(value) for value in truth_fixed]
    truth_correlation = correlation(moving_values, truth_values)
    expected_moving = [f32(AFFINE_INTENSITY_OFFSET + AFFINE_INTENSITY_SCALE * value) for value in truth_values]
    relation_residual = max(abs(actual - expected) for actual, expected in zip(moving_values, expected_moving))
    perturbed = []
    for name, candidate in states:
        fixed_values = [trilinear(fixed, apply(candidate, point)) for point, _ in selected]
        if any(value is None for value in fixed_values):
            raise AssertionError("oracle common-support construction failed")
        value = correlation(moving_values, [float(sample) for sample in fixed_values])
        perturbed.append({"id": name, "correlation": value, "drop_from_truth": truth_correlation - value})
    minimum_drop = min(value["drop_from_truth"] for value in perturbed)
    passed = (
        len(selected) >= 1000
        and truth_correlation >= ORACLE_MIN_CORRELATION
        and relation_residual == 0.0
        and minimum_drop >= ORACLE_MIN_PERTURBATION_DROP
    )
    return {
        "points": len(selected),
        "truth_correlation": truth_correlation,
        "maximum_affine_relation_residual": relation_residual,
        "minimum_perturbation_correlation_drop": minimum_drop,
        "perturbations": perturbed,
        "passed": passed,
    }


def landmarks(cohort: str, matrix: tuple[float, ...]) -> list[dict[str, object]]:
    zs = (-14.0, -7.0, 0.0, 7.0, 14.0)
    if cohort == "partial-slab":
        zs = (-10.0, -5.0, 0.0, 5.0, 10.0)
    xy = ((-14.0, -9.0), (12.0, -8.0), (-9.0, 11.0), (11.0, 10.0), (0.0, 0.0))
    return [
        {"id": f"landmark-{index:02d}", "moving_world_mm": point, "fixed_world_mm": apply(matrix, point)}
        for index, point in enumerate((x, y, z) for z in zs for x, y in xy)
    ]


def generate(output_root: pathlib.Path) -> None:
    output_root.mkdir(parents=True, exist_ok=True)
    repository_root = output_root.parents[3]
    fixed_by_subject: dict[str, tuple[pathlib.Path, Grid]] = {}
    for draw_index, subject in enumerate(SUBJECTS):
        subject_index = draw_index + SUBJECT_INDEX_OFFSET
        fixed = build_grid(
            FULL_SHAPE,
            SPACING_MM,
            FULL_ORIGIN,
            lambda x, y, z, index=subject_index: fixed_value(index, x, y, z),
        )
        path = output_root / f"{subject}-fixed.nii.gz"
        write_nifti(path, fixed)
        fixed_by_subject[subject] = (path, fixed)

    cases = []
    rows = [
        "case_id\tsubject_id\tcohort\tmodel\tmoving\tfixed\t"
        "moving_sha256\tfixed_sha256\ttruth_row_major\ttruth_kind"
    ]
    exact_oracles = []
    for draw_index, subject in enumerate(SUBJECTS):
        subject_index = draw_index + SUBJECT_INDEX_OFFSET
        fixed_path, fixed = fixed_by_subject[subject]
        for cohort in COHORTS:
            for model in MODELS:
                matrix = truth(subject_index, cohort, model)
                moving = moving_grid(fixed, matrix, cohort)
                case_id = f"{subject}-{cohort}-{model}"
                moving_path = output_root / f"{case_id}-moving.nii.gz"
                write_nifti(moving_path, moving)
                truth_kind = "approximation-distribution" if cohort == "nonlinear-contrast" else "interpolant-exact"
                oracle_result = None
                if truth_kind == "interpolant-exact":
                    oracle_result = oracle(fixed, moving, matrix, model)
                    if not oracle_result["passed"]:
                        raise ValueError(f"pre-execution oracle failed for {case_id}: {oracle_result}")
                    exact_oracles.append(oracle_result)
                case = {
                    "case_id": case_id,
                    "subject_id": subject,
                    "cohort": cohort,
                    "model": model,
                    "truth_kind": truth_kind,
                    "moving": {"path": str(moving_path.relative_to(repository_root)), "sha256": sha256(moving_path)},
                    "fixed": {"path": str(fixed_path.relative_to(repository_root)), "sha256": sha256(fixed_path)},
                    "truth_moving_to_fixed": matrix,
                    "landmarks": landmarks(cohort, matrix),
                    "preexecution_oracle": oracle_result,
                }
                cases.append(case)
                rows.append("\t".join((
                    case_id,
                    subject,
                    cohort,
                    model,
                    case["moving"]["path"],
                    case["fixed"]["path"],
                    case["moving"]["sha256"],
                    case["fixed"]["sha256"],
                    ",".join(format(value, ".17g") for value in matrix),
                    truth_kind,
                )))

    descriptor = {
        "schema_version": VERSION,
        "fixture_id": "flashalign-linear-automatic-release-confirmation-v4",
        "status": "audit-sealed-before-candidate-execution",
        "generator_contract": "Fixed float32 images are independent analytic phantoms. Interpolant-exact moving voxels use an independent standard-library trilinear sampler over the stored fixed grid followed by one positive affine intensity map. Candidate code is neither imported nor executed.",
        "provenance": {"kind": "repository-authored-analytic-synthetic", "license": "Apache-2.0"},
        "sampling": {"spacing_mm": SPACING_MM, "full_shape": FULL_SHAPE, "slab_shape": SLAB_SHAPE},
        "intensity": {
            "interpolant_exact": {"offset": AFFINE_INTENSITY_OFFSET, "scale": AFFINE_INTENSITY_SCALE},
            "nonlinear_contrast": "230 + 24 * signed_sqrt(stored_fixed_trilinear - 500); approximation distribution only",
        },
        "preexecution_oracle_contract": {
            "maximum_points_per_case": ORACLE_MAX_POINTS,
            "minimum_truth_correlation": ORACLE_MIN_CORRELATION,
            "minimum_perturbation_drop": ORACLE_MIN_PERTURBATION_DROP,
            "perturbations": "plus/minus 0.1 mm translations and 0.3 degree target-frame rotations; affine rows also include plus/minus 0.5 percent axis scales",
            "exact_rows": len(exact_oracles),
            "all_passed": all(value["passed"] for value in exact_oracles),
            "minimum_observed_truth_correlation": min(value["truth_correlation"] for value in exact_oracles),
            "minimum_observed_perturbation_drop": min(value["minimum_perturbation_correlation_drop"] for value in exact_oracles),
        },
        "case_count": len(cases),
        "subjects": SUBJECTS,
        "cohorts": COHORTS,
        "models": MODELS,
        "cases": cases,
    }
    descriptor_path = output_root.parent / "linear-automatic-release-confirmation-v4.json"
    table_path = output_root.parent / "linear-automatic-release-confirmation-v4.cases.tsv"
    descriptor_path.write_text(json.dumps(descriptor, indent=2, sort_keys=True) + "\n")
    table_path.write_text("\n".join(rows) + "\n")
    print(json.dumps({
        "descriptor": str(descriptor_path),
        "descriptor_sha256": sha256(descriptor_path),
        "case_table": str(table_path),
        "case_table_sha256": sha256(table_path),
        "cases": len(cases),
        "exact_oracles": len(exact_oracles),
        "candidate_executed": False,
    }, sort_keys=True))


def self_test() -> None:
    grid = build_grid((4, 4, 4), 2.0, (-3.0, -3.0, -3.0), lambda x, y, z: 2.0 * x - 3.0 * y + 0.5 * z + 7.0)
    point = (-0.4, 1.2, 0.7)
    expected = 2.0 * point[0] - 3.0 * point[1] + 0.5 * point[2] + 7.0
    actual = trilinear(grid, point)
    if actual is None or not math.isclose(actual, expected, rel_tol=0.0, abs_tol=1e-12):
        raise AssertionError((actual, expected))
    matrix = rigid_matrix(0.0, 0.0, 0.0, 1.0, -2.0, 3.0)
    if apply(matrix, (4.0, 5.0, 6.0)) != (5.0, 3.0, 9.0):
        raise AssertionError("moving-to-fixed direction failed")
    if len({f"{s}-{c}-{m}" for s in SUBJECTS for c in COHORTS for m in MODELS}) != 48:
        raise AssertionError("case IDs are not unique")
    print("linear automatic confirmation generator self-test passed")


def main() -> None:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("self-test")
    generate_parser = subparsers.add_parser("generate")
    generate_parser.add_argument("output_root", type=pathlib.Path)
    args = parser.parse_args()
    if args.command == "self-test":
        self_test()
    else:
        generate(args.output_root)


if __name__ == "__main__":
    main()
