#!/usr/bin/env python3
"""Generate and summarize the sealed Flashalign linear synthetic court.

The image generator is intentionally independent of reframe4s and image4s. It
samples a closed-form continuous phantom directly in world millimetres and
writes minimal NIfTI-1 files with the Python standard library only.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
import pathlib
import struct
from collections import defaultdict


VERSION = "1.0.0"
SUBJECTS = ("s01", "s02", "s03", "s04")
COHORTS = (
    "ordinary-same-subject",
    "large-initialization-error",
    "partial-slab",
    "dropout-or-low-signal",
)
MODELS = ("rigid", "affine")
IDENTITY = (
    1.0, 0.0, 0.0, 0.0,
    0.0, 1.0, 0.0, 0.0,
    0.0, 0.0, 1.0, 0.0,
    0.0, 0.0, 0.0, 1.0,
)
FAILURE_PENALTY_MM = 100.0
CATASTROPHIC_RMS_MM = 5.0
BOOTSTRAP_REPLICATES = 10000
BOOTSTRAP_SEED = 20260912


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def matrix_multiply(left: tuple[float, ...], right: tuple[float, ...]) -> tuple[float, ...]:
    return tuple(
        sum(left[row * 4 + k] * right[k * 4 + column] for k in range(4))
        for row in range(4)
        for column in range(4)
    )


def rigid_matrix(rx: float, ry: float, rz: float, tx: float, ty: float, tz: float) -> tuple[float, ...]:
    cx, sx = math.cos(rx), math.sin(rx)
    cy, sy = math.cos(ry), math.sin(ry)
    cz, sz = math.cos(rz), math.sin(rz)
    x = (1.0, 0.0, 0.0, 0.0, 0.0, cx, -sx, 0.0, 0.0, sx, cx, 0.0, 0.0, 0.0, 0.0, 1.0)
    y = (cy, 0.0, sy, 0.0, 0.0, 1.0, 0.0, 0.0, -sy, 0.0, cy, 0.0, 0.0, 0.0, 0.0, 1.0)
    z = (cz, -sz, 0.0, 0.0, sz, cz, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
    rotation = matrix_multiply(z, matrix_multiply(y, x))
    values = list(rotation)
    values[3], values[7], values[11] = tx, ty, tz
    return tuple(values)


def truth(subject_index: int, cohort: str, model: str) -> tuple[float, ...]:
    sign = -1.0 if subject_index % 2 else 1.0
    if cohort == "large-initialization-error":
        degrees = 11.0 + 1.7 * subject_index
        translation = (9.0 * sign, -7.0 + subject_index, 6.0 - subject_index)
    elif cohort == "partial-slab":
        degrees = 3.0 + 0.4 * subject_index
        translation = (2.5 * sign, -2.0, 1.5)
    elif cohort == "dropout-or-low-signal":
        degrees = 2.4 + 0.3 * subject_index
        translation = (-2.0 * sign, 2.5, -1.0)
    else:
        degrees = 1.5 + 0.35 * subject_index
        translation = (1.4 * sign, -1.1 + 0.2 * subject_index, 0.8)
    angle = math.radians(degrees)
    pose = rigid_matrix(0.35 * angle, -0.55 * angle, sign * angle, *translation)
    if model == "rigid":
        return pose
    strain = (
        1.0 + 0.008 * sign, 0.006, -0.003, 0.0,
        -0.004, 1.0 - 0.007 * sign, 0.005, 0.0,
        0.003, -0.004, 1.006, 0.0,
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


def phantom(subject_index: int, x: float, y: float, z: float) -> float:
    phase = 0.31 * subject_index
    brain = (x / 25.0) ** 2 + (y / 23.0) ** 2 + (z / 21.0) ** 2
    if brain >= 1.0:
        return 0.0

    def blob(cx: float, cy: float, cz: float, sx: float, sy: float, sz: float) -> float:
        return math.exp(-0.5 * (((x - cx) / sx) ** 2 + ((y - cy) / sy) ** 2 + ((z - cz) / sz) ** 2))

    value = (
        1.55 * blob(-8.0 + subject_index, -4.0, 5.0, 5.0, 7.0, 6.0)
        - 0.95 * blob(8.0, -7.0 + subject_index, 4.0, 7.0, 4.0, 5.0)
        + 0.82 * blob(4.0, 9.0, -7.0 + 0.4 * subject_index, 5.0, 4.0, 6.0)
        + 0.63 * blob(-10.0, 8.0, -5.0, 4.0, 5.0, 4.0)
        + 0.16 * math.sin(0.22 * x + 0.13 * y + 0.09 * z + phase)
        + 0.11 * math.cos(0.013 * x * y - 0.009 * y * z + phase)
    )
    taper = max(0.0, 1.0 - brain) ** 0.35
    return taper * value


def fixed_value(subject_index: int, x: float, y: float, z: float) -> float:
    signal = phantom(subject_index, x, y, z)
    return 0.0 if signal == 0.0 else 220.0 + 780.0 * signal


def moving_value(subject_index: int, cohort: str, matrix: tuple[float, ...], x: float, y: float, z: float) -> float:
    fx, fy, fz = apply(matrix, (x, y, z))
    signal = phantom(subject_index, fx, fy, fz)
    if signal == 0.0:
        return 0.0
    value = 150.0 + 690.0 * math.copysign(math.sqrt(abs(signal)), signal)
    if cohort == "dropout-or-low-signal":
        if -5.5 <= y <= 2.5 and z > -10.0:
            return 0.0
        if x > 7.0 and z < 7.0:
            value *= 0.12
    return value


def write_nifti(path: pathlib.Path, shape: tuple[int, int, int], spacing: float, origin: tuple[float, float, float], sample) -> None:
    header = bytearray(352)
    struct.pack_into("<i", header, 0, 348)
    struct.pack_into("<8h", header, 40, 3, shape[0], shape[1], shape[2], 1, 1, 1, 1)
    struct.pack_into("<h", header, 70, 16)
    struct.pack_into("<h", header, 72, 32)
    struct.pack_into("<8f", header, 76, 1.0, spacing, spacing, spacing, 0.0, 0.0, 0.0, 0.0)
    struct.pack_into("<f", header, 108, 352.0)
    struct.pack_into("<f", header, 112, 1.0)
    struct.pack_into("<B", header, 123, 2)
    struct.pack_into("<h", header, 252, 0)
    struct.pack_into("<h", header, 254, 1)
    struct.pack_into("<4f", header, 280, spacing, 0.0, 0.0, origin[0])
    struct.pack_into("<4f", header, 296, 0.0, spacing, 0.0, origin[1])
    struct.pack_into("<4f", header, 312, 0.0, 0.0, spacing, origin[2])
    header[344:348] = b"n+1\0"
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=6) as stream:
            stream.write(header)
            for k in range(shape[2]):
                z = origin[2] + spacing * k
                for j in range(shape[1]):
                    y = origin[1] + spacing * j
                    values = [sample(origin[0] + spacing * i, y, z) for i in range(shape[0])]
                    stream.write(struct.pack(f"<{shape[0]}f", *values))


def landmark_points(cohort: str) -> list[tuple[float, float, float]]:
    zs = (-12.0, -6.0, 0.0, 6.0, 12.0)
    if cohort == "partial-slab":
        zs = (-8.0, -4.0, 0.0, 4.0, 8.0)
    xy = ((-12.0, -8.0), (10.0, -7.0), (-8.0, 9.0), (9.0, 8.0), (0.0, 0.0))
    return [(x, y, z) for z in zs for x, y in xy]


def generate(output_root: pathlib.Path) -> pathlib.Path:
    output_root.mkdir(parents=True, exist_ok=True)
    repository_root = output_root.parents[3]
    fixed_shape, full_shape, slab_shape = (29, 29, 29), (29, 29, 29), (29, 29, 15)
    spacing = 2.0
    full_origin, slab_origin = (-28.0, -28.0, -28.0), (-28.0, -28.0, -14.0)
    fixed_paths: dict[str, pathlib.Path] = {}
    for subject_index, subject in enumerate(SUBJECTS):
        path = output_root / f"{subject}-fixed.nii.gz"
        write_nifti(path, fixed_shape, spacing, full_origin, lambda x, y, z, index=subject_index: fixed_value(index, x, y, z))
        fixed_paths[subject] = path

    cases = []
    tsv_rows = ["case_id\tsubject_id\tcohort\tmodel\tmoving\tfixed\ttruth_row_major"]
    for subject_index, subject in enumerate(SUBJECTS):
        for cohort in COHORTS:
            for model in MODELS:
                matrix = truth(subject_index, cohort, model)
                case_id = f"{subject}-{cohort}-{model}"
                moving_path = output_root / f"{case_id}-moving.nii.gz"
                shape = slab_shape if cohort == "partial-slab" else full_shape
                origin = slab_origin if cohort == "partial-slab" else full_origin
                write_nifti(
                    moving_path,
                    shape,
                    spacing,
                    origin,
                    lambda x, y, z, index=subject_index, lane=cohort, transform=matrix: moving_value(index, lane, transform, x, y, z),
                )
                landmarks = [
                    {"id": f"landmark-{index:02d}", "moving_world_mm": point, "fixed_world_mm": apply(matrix, point)}
                    for index, point in enumerate(landmark_points(cohort))
                ]
                entry = {
                    "case_id": case_id,
                    "subject_id": subject,
                    "cohort": cohort,
                    "model": model,
                    "moving": {"path": str(moving_path.relative_to(repository_root)), "sha256": sha256(moving_path)},
                    "fixed": {"path": str(fixed_paths[subject].relative_to(repository_root)), "sha256": sha256(fixed_paths[subject])},
                    "initialization": {"id": "world-identity-v1", "moving_to_fixed": IDENTITY},
                    "truth_moving_to_fixed": matrix,
                    "landmarks": landmarks,
                }
                cases.append(entry)
                tsv_rows.append(
                    "\t".join(
                        (
                            case_id,
                            subject,
                            cohort,
                            model,
                            str(moving_path.relative_to(repository_root)),
                            str(fixed_paths[subject].relative_to(repository_root)),
                            ",".join(format(v, ".17g") for v in matrix),
                        )
                    )
                )

    descriptor = {
        "schema_version": VERSION,
        "fixture_id": "flashalign-linear-accuracy-audit-v1",
        "status": "audit-sealed",
        "generator_contract": "Independent closed-form continuous world-space phantom sampled directly onto fixed and moving grids; candidate code and interpolation are not imported.",
        "provenance": {"kind": "repository-authored-analytic-synthetic", "license": "Apache-2.0"},
        "initialization_contract": "Every method receives the exact world-space identity matrix; neither lane receives structural capture.",
        "case_count": len(cases),
        "subjects": SUBJECTS,
        "cohorts": COHORTS,
        "models": MODELS,
        "cases": cases,
    }
    descriptor_path = output_root.parent / "linear-accuracy-audit-v1.json"
    descriptor_path.write_text(json.dumps(descriptor, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (output_root.parent / "linear-accuracy-audit-v1.cases.tsv").write_text("\n".join(tsv_rows) + "\n", encoding="utf-8")
    print(json.dumps({"fixture": str(descriptor_path), "fixture_sha256": sha256(descriptor_path), "case_count": len(cases)}, sort_keys=True))
    return descriptor_path


def percentile(values: list[float], probability: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return float("nan")
    index = min(len(ordered) - 1, max(0, math.ceil(probability * len(ordered)) - 1))
    return ordered[index]


def summarize(raw_path: pathlib.Path, fixture_path: pathlib.Path, receipt_path: pathlib.Path) -> None:
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    rows = [json.loads(line) for line in raw_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    expected = {(case["case_id"], method) for case in fixture["cases"] for method in ("flashalign", "3dAllineate")}
    observed = {(row["case_id"], row["method"]["id"].split("-")[0] if row["method"]["id"].startswith("flashalign") else "3dAllineate") for row in rows}
    missing = sorted(expected - observed)
    counts = defaultdict(int)
    for key in ((row["case_id"], "flashalign" if row["method"]["id"].startswith("flashalign") else "3dAllineate") for row in rows):
        counts[key] += 1
    duplicates = sorted(key for key, count in counts.items() if count != 1)
    fixture_by_case = {case["case_id"]: case for case in fixture["cases"]}
    input_hash_mismatches = []
    for row in rows:
        case = fixture_by_case.get(row["case_id"])
        if case is None or row["inputs"]["moving"] != case["moving"]["sha256"] or row["inputs"]["fixed"] != case["fixed"]["sha256"]:
            input_hash_mismatches.append(row["run_id"])

    def method_family(row):
        return "flashalign" if row["method"]["id"].startswith("flashalign") else "3dAllineate"

    def adjudicated(row):
        value = row["metrics"]["landmark_rms_mm"]
        return value if row["outcome"]["status"] == "success" and value is not None else FAILURE_PENALTY_MM

    grouped = defaultdict(list)
    for row in rows:
        grouped[(method_family(row), row["cohort"], row["method"]["model"])].append(row)
    summaries = []
    for (method, cohort, model), values in sorted(grouped.items()):
        errors = [adjudicated(row) for row in values]
        catastrophes = [row["outcome"]["status"] != "success" or error > CATASTROPHIC_RMS_MM for row, error in zip(values, errors)]
        summaries.append({
            "method": method,
            "cohort": cohort,
            "model": model,
            "denominator": len(values),
            "successes": sum(row["outcome"]["status"] == "success" for row in values),
            "median_adjudicated_rms_mm": percentile(errors, 0.5),
            "p95_adjudicated_rms_mm": percentile(errors, 0.95),
            "catastrophic_rate": sum(catastrophes) / len(values),
            "failures": [{"case_id": row["case_id"], "status": row["outcome"]["status"], "detail": row["outcome"]["failure_detail"]} for row in values if row["outcome"]["status"] != "success"],
        })

    paired = []
    by_case_method = {(row["case_id"], method_family(row)): row for row in rows}
    import random
    rng = random.Random(BOOTSTRAP_SEED)
    for cohort in COHORTS:
        for model in MODELS:
            case_entries = [case for case in fixture["cases"] if case["cohort"] == cohort and case["model"] == model]
            complete = [case for case in case_entries if (case["case_id"], "flashalign") in by_case_method and (case["case_id"], "3dAllineate") in by_case_method]
            diffs = []
            if len(complete) == len(case_entries):
                for _ in range(BOOTSTRAP_REPLICATES):
                    sampled_subjects = [rng.choice(SUBJECTS) for _ in SUBJECTS]
                    sampled_cases = [case for subject in sampled_subjects for case in complete if case["subject_id"] == subject]
                    fa = [adjudicated(by_case_method[(case["case_id"], "flashalign")]) for case in sampled_cases]
                    afni = [adjudicated(by_case_method[(case["case_id"], "3dAllineate")]) for case in sampled_cases]
                    fa_cat = [v > CATASTROPHIC_RMS_MM for v in fa]
                    afni_cat = [v > CATASTROPHIC_RMS_MM for v in afni]
                    diffs.append((percentile(fa, 0.5) - percentile(afni, 0.5), percentile(fa, 0.95) - percentile(afni, 0.95), sum(fa_cat) / len(fa_cat) - sum(afni_cat) / len(afni_cat)))
            point_fa = [adjudicated(by_case_method[(case["case_id"], "flashalign")]) for case in complete]
            point_afni = [adjudicated(by_case_method[(case["case_id"], "3dAllineate")]) for case in complete]
            point = {
                "median_difference_mm": percentile(point_fa, 0.5) - percentile(point_afni, 0.5) if complete else None,
                "p95_difference_mm": percentile(point_fa, 0.95) - percentile(point_afni, 0.95) if complete else None,
                "catastrophic_rate_difference": (sum(v > CATASTROPHIC_RMS_MM for v in point_fa) - sum(v > CATASTROPHIC_RMS_MM for v in point_afni)) / len(complete) if complete else None,
            }
            intervals = {
                "median_difference_mm": [percentile([d[0] for d in diffs], 0.025), percentile([d[0] for d in diffs], 0.975)] if diffs else None,
                "p95_difference_mm": [percentile([d[1] for d in diffs], 0.025), percentile([d[1] for d in diffs], 0.975)] if diffs else None,
                "catastrophic_rate_difference": [percentile([d[2] for d in diffs], 0.025), percentile([d[2] for d in diffs], 0.975)] if diffs else None,
            }
            superiority = bool(diffs) and all(intervals[key][1] < 0.0 for key in intervals)
            paired.append({"cohort": cohort, "model": model, "pairs": len(complete), "point_estimates_flashalign_minus_afni": point, "subject_clustered_bootstrap_95_percent_ci": intervals, "superiority_passed": superiority})

    ordinary_rigid = [row for row in rows if method_family(row) == "flashalign" and row["cohort"] == "ordinary-same-subject" and row["method"]["model"] == "rigid"]
    accuracy_passed = len(ordinary_rigid) == len(SUBJECTS) and all(row["outcome"]["status"] == "success" and row["metrics"]["landmark_rms_mm"] is not None and row["metrics"]["landmark_rms_mm"] <= 0.1 for row in ordinary_rigid)
    receipt = {
        "schema_version": "flashalign.linear-accuracy-qualification.v1",
        "receipt_id": "flashalign-linear-accuracy-qualification-2026-09-12",
        "status": "passed" if not missing and not duplicates and not input_hash_mismatches and len(rows) == len(expected) else "failed",
        "scope": "sealed repository-authored analytic synthetic engineering court; no acquired-MRI accuracy or clinical claim",
        "fixture": {"path": str(fixture_path), "sha256": sha256(fixture_path), "declared_cases": fixture["case_count"]},
        "raw_evidence": {"path": str(raw_path), "sha256": sha256(raw_path), "rows": len(rows)},
        "denominator": {"expected_rows": len(expected), "observed_rows": len(rows), "missing": missing, "duplicates": duplicates, "input_hash_mismatches": input_hash_mismatches, "complete": not missing and not duplicates and not input_hash_mismatches and len(rows) == len(expected)},
        "predeclared_policy": {"failure_penalty_mm": FAILURE_PENALTY_MM, "catastrophic_rms_mm": CATASTROPHIC_RMS_MM, "bootstrap_replicates": BOOTSTRAP_REPLICATES, "bootstrap_seed": BOOTSTRAP_SEED, "superiority": "upper endpoint of every subject-clustered 95% CI for Flashalign-minus-3dAllineate median, p95, and catastrophic-rate difference is strictly below zero"},
        "cohort_results": summaries,
        "paired_comparisons": paired,
        "fixture_release_accuracy": {"gate": "all ordinary rigid Flashalign cases succeed with landmark RMS <= 0.1 mm", "passed": accuracy_passed},
        "superiority_claim": {"passed": bool(paired) and all(entry["superiority_passed"] for entry in paired), "claim": "none"},
        "nonclaims": ["No acquired MRI was used.", "A favorable point estimate is not superiority.", "The court does not test structural capture because the public candidate path does not execute it."],
    }
    receipt_path.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({"receipt": str(receipt_path), "sha256": sha256(receipt_path), "missing": len(missing), "accuracy_passed": accuracy_passed}, sort_keys=True))


def self_test() -> None:
    identity = rigid_matrix(0.0, 0.0, 0.0, 2.0, -3.0, 4.0)
    assert apply(identity, (1.0, 2.0, 3.0)) == (3.0, -1.0, 7.0)
    assert len({f"{s}-{c}-{m}" for s in SUBJECTS for c in COHORTS for m in MODELS}) == 32
    assert percentile([4.0, 1.0, 3.0, 2.0], 0.5) == 2.0
    print("linear-accuracy-audit self-test passed")


def main() -> None:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    generate_parser = subparsers.add_parser("generate")
    generate_parser.add_argument("output_root", type=pathlib.Path)
    summarize_parser = subparsers.add_parser("summarize")
    summarize_parser.add_argument("raw", type=pathlib.Path)
    summarize_parser.add_argument("fixture", type=pathlib.Path)
    summarize_parser.add_argument("receipt", type=pathlib.Path)
    subparsers.add_parser("self-test")
    args = parser.parse_args()
    if args.command == "generate":
        generate(args.output_root)
    elif args.command == "summarize":
        summarize(args.raw, args.fixture, args.receipt)
    else:
        self_test()


if __name__ == "__main__":
    main()
