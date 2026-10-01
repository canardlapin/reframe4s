#!/usr/bin/env python3
"""Diagnose the opened linear court's sub-voxel truth bias.

This program is independent of Flashalign. It reads the sealed NIfTI byte
streams directly, samples them with SciPy's trilinear interpolator, and compares
a global NCC proxy at the declared truth with nearby translations. The proxy is
deliberately narrower than Flashalign's sampled patch mixture objective; it can
falsify the assumption that truth is an NCC optimum for these fixtures, but it
cannot establish where Flashalign's exact objective is minimized.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
import pathlib
import struct
import sys
from typing import Callable

import numpy as np
import scipy
from scipy.ndimage import map_coordinates
from scipy.optimize import minimize


DESCRIPTOR = pathlib.Path(
    "benchmarks/flashalign/fixtures/linear-accuracy-audit-v1.json"
)
CASE_TABLE = pathlib.Path(
    "benchmarks/flashalign/fixtures/linear-accuracy-audit-v1.cases.tsv"
)
DESCRIPTOR_SHA256 = (
    "7b9e2387701b9138cff66697a37b5a1c8d45c691d336ab9917eebba8a55ade0a"
)
CASE_TABLE_SHA256 = (
    "c39c7c20b28c768ad548cfa3d7d83cc7f48e3cbfc959689b61c8bf220a79bf0e"
)
SEARCH_BOUND_MM = 0.75
FINITE_DIFFERENCE_STEP_MM = 0.01


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require_seal(path: pathlib.Path, expected: str) -> None:
    actual = sha256(path)
    if actual != expected:
        raise ValueError(f"sealed input {path} has SHA-256 {actual}, expected {expected}")


def read_nifti_float32(path: pathlib.Path) -> tuple[np.ndarray, np.ndarray]:
    with gzip.open(path, "rb") as stream:
        payload = stream.read()
    if len(payload) < 352 or struct.unpack_from("<i", payload, 0)[0] != 348:
        raise ValueError(f"{path} is not a supported little-endian NIfTI-1 stream")
    dimensions = struct.unpack_from("<8h", payload, 40)
    datatype = struct.unpack_from("<h", payload, 70)[0]
    bitpix = struct.unpack_from("<h", payload, 72)[0]
    voxel_offset = int(struct.unpack_from("<f", payload, 108)[0])
    if dimensions[0] != 3 or datatype != 16 or bitpix != 32:
        raise ValueError(f"{path} must be scalar D3 float32 NIfTI")
    shape = tuple(int(value) for value in dimensions[1:4])
    count = math.prod(shape)
    data = np.frombuffer(
        payload,
        dtype="<f4",
        count=count,
        offset=voxel_offset,
    ).reshape(shape, order="F").astype(np.float64)
    affine = np.array(
        [
            struct.unpack_from("<4f", payload, 280),
            struct.unpack_from("<4f", payload, 296),
            struct.unpack_from("<4f", payload, 312),
            (0.0, 0.0, 0.0, 1.0),
        ],
        dtype=np.float64,
    )
    return data, affine


def phantom(
    subject_index: int,
    x: np.ndarray,
    y: np.ndarray,
    z: np.ndarray,
) -> np.ndarray:
    """Vector form of the sealed generator, checked against its source hash."""
    phase = 0.31 * subject_index
    brain = (x / 25.0) ** 2 + (y / 23.0) ** 2 + (z / 21.0) ** 2

    def blob(
        cx: float,
        cy: float,
        cz: float,
        sx: float,
        sy: float,
        sz: float,
    ) -> np.ndarray:
        return np.exp(
            -0.5
            * (
                ((x - cx) / sx) ** 2
                + ((y - cy) / sy) ** 2
                + ((z - cz) / sz) ** 2
            )
        )

    signal = (
        1.55 * blob(-8.0 + subject_index, -4.0, 5.0, 5.0, 7.0, 6.0)
        - 0.95 * blob(8.0, -7.0 + subject_index, 4.0, 7.0, 4.0, 5.0)
        + 0.82
        * blob(4.0, 9.0, -7.0 + 0.4 * subject_index, 5.0, 4.0, 6.0)
        + 0.63 * blob(-10.0, 8.0, -5.0, 4.0, 5.0, 4.0)
        + 0.16 * np.sin(0.22 * x + 0.13 * y + 0.09 * z + phase)
        + 0.11 * np.cos(0.013 * x * y - 0.009 * y * z + phase)
    )
    return np.where(brain >= 1.0, 0.0, np.maximum(0.0, 1.0 - brain) ** 0.35 * signal)


def correlation(left: np.ndarray, right: np.ndarray) -> float:
    centered_left = left - left.mean()
    centered_right = right - right.mean()
    denominator = math.sqrt(
        float(centered_left @ centered_left)
        * float(centered_right @ centered_right)
    )
    if denominator == 0.0:
        raise ValueError("NCC proxy encountered a constant signal")
    return float((centered_left @ centered_right) / denominator)


def optimize_translation(
    evaluator: Callable[[np.ndarray], float],
) -> dict[str, object]:
    zero = np.zeros(3, dtype=np.float64)
    step = FINITE_DIFFERENCE_STEP_MM
    derivatives = []
    for axis in range(3):
        offset = np.zeros(3, dtype=np.float64)
        offset[axis] = step
        derivatives.append((evaluator(offset) - evaluator(-offset)) / (2.0 * step))
    initial_simplex = np.vstack((zero, np.eye(3, dtype=np.float64) * 0.1))
    result = minimize(
        lambda delta: -evaluator(delta),
        zero,
        method="Nelder-Mead",
        bounds=[(-SEARCH_BOUND_MM, SEARCH_BOUND_MM)] * 3,
        options={
            "initial_simplex": initial_simplex,
            "xatol": 1e-6,
            "fatol": 1e-13,
            "maxiter": 500,
        },
    )
    if not result.success:
        raise ValueError(f"translation proxy search failed: {result.message}")
    optimum = np.asarray(result.x, dtype=np.float64)
    return {
        "truth_correlation": evaluator(zero),
        "central_difference_correlation_per_mm": derivatives,
        "optimum_translation_mm": optimum.tolist(),
        "optimum_translation_norm_mm": float(np.linalg.norm(optimum)),
        "optimum_correlation": float(-result.fun),
        "search_evaluations": int(result.nfev),
    }


def analyze_case(case: dict[str, object], subject_index: int) -> dict[str, object]:
    moving, moving_affine = read_nifti_float32(pathlib.Path(case["moving"]["path"]))
    fixed, fixed_affine = read_nifti_float32(pathlib.Path(case["fixed"]["path"]))
    truth = np.asarray(case["truth_moving_to_fixed"], dtype=np.float64).reshape(4, 4)

    grid = np.indices(moving.shape, dtype=np.float64)
    moving_index = np.stack(
        [grid[axis].reshape(-1, order="F") for axis in range(3)]
    )
    moving_world = (
        moving_affine[:3, :3] @ moving_index + moving_affine[:3, 3, None]
    )
    fixed_world_at_truth = (
        truth[:3, :3] @ moving_world + truth[:3, 3, None]
    )
    fixed_index_at_truth = np.linalg.solve(
        fixed_affine[:3, :3],
        fixed_world_at_truth - fixed_affine[:3, 3, None],
    )
    margin_voxels = SEARCH_BOUND_MM / np.linalg.norm(fixed_affine[:3, :3], axis=0)
    common = np.all(
        (fixed_index_at_truth >= margin_voxels[:, None])
        & (
            fixed_index_at_truth
            <= np.asarray(fixed.shape)[:, None] - 1.0 - margin_voxels[:, None]
        ),
        axis=0,
    )
    moving_values = moving.reshape(-1, order="F")[common]
    fixed_world = fixed_world_at_truth[:, common]

    def sampled_fixed(delta: np.ndarray) -> np.ndarray:
        fixed_index = np.linalg.solve(
            fixed_affine[:3, :3],
            fixed_world + delta[:, None] - fixed_affine[:3, 3, None],
        )
        return map_coordinates(
            fixed,
            fixed_index,
            order=1,
            mode="nearest",
            prefilter=False,
        )

    def discretized(delta: np.ndarray) -> float:
        return correlation(moving_values, sampled_fixed(delta))

    def continuous(delta: np.ndarray) -> float:
        world = fixed_world + delta[:, None]
        signal = phantom(subject_index, world[0], world[1], world[2])
        values = np.where(signal == 0.0, 0.0, 220.0 + 780.0 * signal)
        return correlation(moving_values, values)

    signal_at_truth = phantom(
        subject_index,
        fixed_world[0],
        fixed_world[1],
        fixed_world[2],
    )
    affine_contrast_moving = np.where(
        signal_at_truth == 0.0,
        0.0,
        150.0 + 690.0 * signal_at_truth,
    )

    def affine_contrast(delta: np.ndarray) -> float:
        return correlation(affine_contrast_moving, sampled_fixed(delta))

    return {
        "case_id": case["case_id"],
        "common_full_support_voxels": int(common.sum()),
        "stored_sqrt_contrast_with_trilinear_fixed": optimize_translation(discretized),
        "stored_sqrt_contrast_with_continuous_fixed": optimize_translation(continuous),
        "affine_contrast_counterfactual_with_trilinear_fixed": optimize_translation(
            affine_contrast
        ),
    }


def apply_affine(matrix: list[float], point: list[float]) -> np.ndarray:
    value = np.asarray(matrix, dtype=np.float64).reshape(4, 4)
    return value[:3, :3] @ np.asarray(point, dtype=np.float64) + value[:3, 3]


def transform_rms(
    candidate: list[float],
    truth: list[float],
    landmarks: list[dict[str, object]],
) -> float:
    squared = [
        float(
            np.sum(
                (
                    apply_affine(candidate, landmark["moving_world_mm"])
                    - apply_affine(truth, landmark["moving_world_mm"])
                )
                ** 2
            )
        )
        for landmark in landmarks
    ]
    return math.sqrt(sum(squared) / len(squared))


def summarize_flashalign(
    raw_path: pathlib.Path,
    cases: dict[str, dict[str, object]],
) -> dict[str, object]:
    rows = [json.loads(line) for line in raw_path.read_text().splitlines() if line]
    values = []
    for row in rows:
        case = cases[row["case_id"]]
        result = row["result"]["moving_to_fixed"]
        checkpoint = row["last_checkpoint"]
        selected = result if result is not None else checkpoint["moving_to_fixed"]
        last_valid = result if result is not None else checkpoint["last_valid_moving_to_fixed"]
        values.append(
            {
                "case_id": row["case_id"],
                "status": row["outcome"]["status"],
                "selected_landmark_rms_from_truth_mm": transform_rms(
                    selected,
                    case["truth_moving_to_fixed"],
                    case["landmarks"],
                ),
                "last_valid_landmark_rms_from_truth_mm": transform_rms(
                    last_valid,
                    case["truth_moving_to_fixed"],
                    case["landmarks"],
                ),
                "linearizations": row["work"]["linearizations"],
                "trial_evaluations": row["work"]["trial_evaluations"],
                "rejected_trials": row["work"]["rejected_trials"],
            }
        )
    return {
        "path": str(raw_path),
        "sha256": sha256(raw_path),
        "rows": values,
    }


def self_test() -> None:
    require_seal(DESCRIPTOR, DESCRIPTOR_SHA256)
    require_seal(CASE_TABLE, CASE_TABLE_SHA256)
    descriptor = json.loads(DESCRIPTOR.read_text())
    case = descriptor["cases"][0]
    fixed, affine = read_nifti_float32(pathlib.Path(case["fixed"]["path"]))
    center = tuple(value // 2 for value in fixed.shape)
    center_world = affine[:3, :3] @ np.asarray(center) + affine[:3, 3]
    expected_signal = phantom(0, *[np.asarray([value]) for value in center_world])[0]
    expected = 0.0 if expected_signal == 0.0 else 220.0 + 780.0 * expected_signal
    if not math.isclose(float(fixed[center]), float(expected), rel_tol=0.0, abs_tol=2e-5):
        raise AssertionError((fixed[center], expected))
    sample = np.array([1.0, 3.0, 8.0, 12.0])
    if not math.isclose(correlation(sample, sample * 7.0 + 2.0), 1.0, abs_tol=1e-14):
        raise AssertionError("NCC affine-intensity invariance failed")
    print("linear truth-bias diagnostic self-test passed")


def run(args: argparse.Namespace) -> None:
    require_seal(DESCRIPTOR, DESCRIPTOR_SHA256)
    require_seal(CASE_TABLE, CASE_TABLE_SHA256)
    descriptor = json.loads(DESCRIPTOR.read_text())
    ordinary = [
        case
        for case in descriptor["cases"]
        if case["cohort"] == "ordinary-same-subject" and case["model"] == "rigid"
    ]
    if len(ordinary) != 4:
        raise ValueError(f"expected four ordinary rigid cases, got {len(ordinary)}")
    by_id = {case["case_id"]: case for case in ordinary}
    analyses = [analyze_case(case, index) for index, case in enumerate(ordinary)]
    output = {
        "schema_version": "flashalign.linear-truth-bias-analysis.v1",
        "status": "opened-cohort-development-diagnostic",
        "sealed_inputs": {
            "descriptor": {"path": str(DESCRIPTOR), "sha256": DESCRIPTOR_SHA256},
            "case_table": {"path": str(CASE_TABLE), "sha256": CASE_TABLE_SHA256},
        },
        "environment": {
            "python": sys.version.split()[0],
            "numpy": np.__version__,
            "scipy": scipy.__version__,
        },
        "proxy_contract": {
            "objective": "Pearson correlation over one fixed set of moving voxels with complete fixed-image trilinear support throughout the bounded search",
            "search": "Nelder-Mead translation-only search within plus or minus 0.75 mm, initialized by 0.1 mm coordinate vertices",
            "finite_difference_step_mm": FINITE_DIFFERENCE_STEP_MM,
            "claim_boundary": "This independent global NCC proxy can falsify truth stationarity for the stored fixture/interpolant combination. It is not Flashalign's sampled patch-mixture objective and cannot qualify or condemn the optimizer by itself.",
        },
        "cases": analyses,
        "flashalign_truth_start": {
            "frozen_pyramid": summarize_flashalign(args.frozen_raw, by_id),
            "native_only": summarize_flashalign(args.native_raw, by_id),
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, indent=2, sort_keys=True) + "\n")
    print(json.dumps({"output": str(args.output), "sha256": sha256(args.output)}))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--output", type=pathlib.Path)
    parser.add_argument("--frozen-raw", type=pathlib.Path)
    parser.add_argument("--native-raw", type=pathlib.Path)
    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        if args.output is None or args.frozen_raw is None or args.native_raw is None:
            parser.error("--output, --frozen-raw and --native-raw are required")
        run(args)


if __name__ == "__main__":
    main()
