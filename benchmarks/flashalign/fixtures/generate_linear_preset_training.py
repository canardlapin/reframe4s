#!/usr/bin/env python3
"""Generate and verify Flashalign v1 linear-preset training evidence.

This lane is deliberately analytic-synthetic.  It checks loss behavior,
policy budgets, holdout-role separation and immutable identities; it does not
claim MRI accuracy or runtime performance.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import random
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[3]
FIXTURE_PATH = ROOT / "benchmarks/flashalign/fixtures/linear-preset-training-v1.json"
RECEIPT_PATH = ROOT / "benchmarks/flashalign/receipts/linear-preset-calibration-v1.json"
MANIFEST_PATH = ROOT / "benchmarks/flashalign/manifests/linear-preset-calibration-v1.json"
SCHEMA_PATH = ROOT / "benchmarks/flashalign/schemas/linear-preset-calibration-v1.schema.json"
PRD_PATH = ROOT / "docs/plans/flashalign-implementation-prd.md"
SOURCE_PATH = ROOT / "modules/reframe4s-flashalign/shared/src/main/scala/reframe4s/flashalign/LinearPreset.scala"
ORACLES = (
    ROOT / "docs/plans/flashalign/nuisance_projected_registration_core.py",
    ROOT / "docs/plans/flashalign/flashalign_extension_checks.py",
    ROOT / "docs/plans/flashalign/flashalign_extension_check_results.json",
)

PRESETS: dict[str, dict[str, Any]] = {
    "epi-t1": {
        "positive_polarity_prior": 0.15,
        "tau": 0.55,
        "outlier_floor": 0.02,
        "minimum_contrast_energy": 1e-12,
        "optimization_draws": 4000,
        "selection_draws": 2000,
        "audit_draws": 2000,
        "uniform_probability_mixture": 0.30,
        "candidate_patches": 20000,
        "capture_maximum_angle_degrees": 30.0,
        "capture_maximum_rotations": 256,
        "minimum_overlap_fraction": 0.30,
        "minimum_spatial_inlier_coverage": 0.25,
        "minimum_patch_inlier_weight": 0.50,
    },
    "epi-t2": {
        "positive_polarity_prior": 0.80,
        "tau": 0.55,
        "outlier_floor": 0.02,
        "minimum_contrast_energy": 1e-12,
        "optimization_draws": 4000,
        "selection_draws": 2000,
        "audit_draws": 2000,
        "uniform_probability_mixture": 0.30,
        "candidate_patches": 20000,
        "capture_maximum_angle_degrees": 30.0,
        "capture_maximum_rotations": 256,
        "minimum_overlap_fraction": 0.30,
        "minimum_spatial_inlier_coverage": 0.25,
        "minimum_patch_inlier_weight": 0.50,
    },
    "within-modality": {
        "positive_polarity_prior": 0.995,
        "tau": 0.40,
        "outlier_floor": 0.01,
        "minimum_contrast_energy": 1e-12,
        "optimization_draws": 2500,
        "selection_draws": 1500,
        "audit_draws": 1500,
        "uniform_probability_mixture": 0.25,
        "candidate_patches": 15000,
        "capture_maximum_angle_degrees": 30.0,
        "capture_maximum_rotations": 192,
        "minimum_overlap_fraction": 0.40,
        "minimum_spatial_inlier_coverage": 0.35,
        "minimum_patch_inlier_weight": 0.60,
    },
    "slab-to-anatomy": {
        "positive_polarity_prior": 0.15,
        "tau": 0.65,
        "outlier_floor": 0.04,
        "minimum_contrast_energy": 1e-12,
        "optimization_draws": 6000,
        "selection_draws": 2500,
        "audit_draws": 2500,
        "uniform_probability_mixture": 0.40,
        "candidate_patches": 30000,
        "capture_maximum_angle_degrees": 40.0,
        "capture_maximum_rotations": 384,
        "minimum_overlap_fraction": 0.20,
        "minimum_spatial_inlier_coverage": 0.15,
        "minimum_patch_inlier_weight": 0.45,
    },
}

RIGID_TRUST = {
    "maximum_linearizations": 40,
    "maximum_trial_attempts": 8,
    "initial_damping": 1e-2,
    "minimum_damping": 1e-8,
    "maximum_damping": 1e8,
    "trust_radius_rms_mm": 2.0,
    "maximum_displacement_mm": 4.0,
    "acceptance_ratio": 0.1,
    "objective_tolerance": 1e-8,
    "gradient_tolerance": 1e-7,
    "step_tolerance_rms_mm": 1e-6,
    "condition_limit": 1e12,
}
AFFINE_TRUST = {
    **RIGID_TRUST,
    "maximum_linearizations": 50,
    "trust_radius_rms_mm": 1.5,
    "maximum_displacement_mm": 3.0,
}

_DETAILS = {
    "epi-t1": {
        "sampling_seed_hex": "4f1bbcdc6762c9d5",
        "effective_resolution_mm": [6.0, 3.0, 2.0],
        "stencil_spacing_mm": [6.0, 3.0, 2.0],
        "maximum_centers_to_screen": 120000,
        "maximum_candidates_per_cell": 16,
        "capture_stages": [[15.0, 5.0], [30.0, 10.0]],
        "capture_seed_hex": "1475b8a31c9de620",
        "maximum_regional_refit_displacement_mm": 3.0,
        "near_equal_objective_tolerance": 1e-3,
        "maximum_data_condition_number": 1e10,
    },
    "epi-t2": {
        "sampling_seed_hex": "59d6e147b20ca83f",
        "effective_resolution_mm": [6.0, 3.0, 2.0],
        "stencil_spacing_mm": [6.0, 3.0, 2.0],
        "maximum_centers_to_screen": 120000,
        "maximum_candidates_per_cell": 16,
        "capture_stages": [[15.0, 5.0], [30.0, 10.0]],
        "capture_seed_hex": "3a51cb90e74d826f",
        "maximum_regional_refit_displacement_mm": 3.0,
        "near_equal_objective_tolerance": 1e-3,
        "maximum_data_condition_number": 1e10,
    },
    "within-modality": {
        "sampling_seed_hex": "2c9277b5a6e41d03",
        "effective_resolution_mm": [6.0, 3.0, 1.5],
        "stencil_spacing_mm": [6.0, 3.0, 1.5],
        "maximum_centers_to_screen": 90000,
        "maximum_candidates_per_cell": 16,
        "capture_stages": [[10.0, 5.0], [30.0, 10.0]],
        "capture_seed_hex": "527cd36e9810ab4f",
        "maximum_regional_refit_displacement_mm": 2.0,
        "near_equal_objective_tolerance": 5e-4,
        "maximum_data_condition_number": 1e9,
    },
    "slab-to-anatomy": {
        "sampling_seed_hex": "73ad48e910bf265c",
        "effective_resolution_mm": [8.0, 4.0, 2.5],
        "stencil_spacing_mm": [8.0, 4.0, 2.5],
        "maximum_centers_to_screen": 180000,
        "maximum_candidates_per_cell": 20,
        "capture_stages": [[20.0, 10.0], [40.0, 20.0]],
        "capture_seed_hex": "6e25c0a91f7b483d",
        "maximum_regional_refit_displacement_mm": 4.0,
        "near_equal_objective_tolerance": 2e-3,
        "maximum_data_condition_number": 1e10,
    },
}

for _mode, _policy in PRESETS.items():
    _policy.update(_DETAILS[_mode])
    _policy.update(
        {
            "correlation_rounding_tolerance": 1e-12,
            "selection_fraction": 0.15,
            "audit_fraction": 0.15,
            "world_cell_size_mm": 12.0,
            "capture_maximum_retained_candidates": 4,
            "capture_peaks_per_rotation": 2,
            "capture_minimum_candidates_to_stop": 2,
            "capture_minimum_structural_energy": 1e-10,
            "capture_minimum_structural_score": 0.15 if _mode == "within-modality" else (0.08 if _mode == "slab-to-anatomy" else 0.10),
            "capture_minimum_overlap_fraction": 0.30 if _mode == "within-modality" else (0.15 if _mode == "slab-to-anatomy" else 0.20),
            "capture_minimum_peak_separation_mm": 8.0,
            "capture_minimum_candidate_displacement_mm": 8.0,
            "rigid_trust": RIGID_TRUST,
            "affine_trust": AFFINE_TRUST,
        }
    )

ROLE_SUBJECTS = {
    "training": [f"fa-train-{index:02d}" for index in range(1, 25)],
    "selection": [f"fa-select-{index:02d}" for index in range(1, 13)],
    "audit": [f"fa-audit-{index:02d}" for index in range(1, 13)],
    "test": [f"fa-test-{index:02d}" for index in range(1, 13)],
}


def canonical_bytes(value: Any) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def logsumexp(values: list[float]) -> float:
    maximum = max(values)
    return maximum + math.log(sum(math.exp(value - maximum) for value in values))


def loss_and_inlier_weight(correlation: float, policy: dict[str, Any]) -> tuple[float, float]:
    tau2 = policy["tau"] ** 2
    floor = policy["outlier_floor"]
    prior = policy["positive_polarity_prior"]
    terms = [
        math.log(floor),
        math.log1p(-floor) + math.log(prior) - (1.0 - correlation) / tau2,
        math.log1p(-floor) + math.log1p(-prior) - (1.0 + correlation) / tau2,
    ]
    normalizer = logsumexp(terms)
    loss = -tau2 * normalizer
    inlier = math.exp(terms[1] - normalizer) + math.exp(terms[2] - normalizer)
    return loss, inlier


def case_observations(subject: str, mode: str, cohort: str, seed: int) -> dict[str, Any]:
    rng = random.Random(seed)
    sign = -1.0 if mode in ("epi-t1", "slab-to-anatomy") else 1.0
    mean_by_cohort = {
        "ordinary": 0.80,
        "large-initialization": 0.72,
        "partial-slab": 0.62,
        "dropout-low-signal": 0.48,
    }
    valid_by_cohort = {
        "ordinary": 0.93,
        "large-initialization": 0.88,
        "partial-slab": 0.50,
        "dropout-low-signal": 0.18,
    }
    coverage_by_cohort = {
        "ordinary": 0.86,
        "large-initialization": 0.74,
        "partial-slab": 0.32,
        "dropout-low-signal": 0.12,
    }
    aligned: list[float] = []
    displaced: list[float] = []
    for index in range(96):
        aligned_value = sign * (mean_by_cohort[cohort] + rng.gauss(0.0, 0.10))
        if cohort == "dropout-low-signal" and index % 7 == 0:
            aligned_value = rng.uniform(-0.15, 0.15)
        aligned.append(max(-0.995, min(0.995, aligned_value)))
        displaced.append(max(-0.995, min(0.995, rng.gauss(0.0, 0.16))))
    required_angle = 26.0 if cohort == "large-initialization" else 8.0
    return {
        "subject_id": subject,
        "mode": mode,
        "cohort": cohort,
        "aligned_correlations": aligned,
        "displaced_correlations": displaced,
        "valid_overlap_fraction": valid_by_cohort[cohort],
        "spatial_inlier_coverage": coverage_by_cohort[cohort],
        "required_capture_angle_degrees": required_angle,
    }


def build_fixture() -> dict[str, Any]:
    modes = ("epi-t1", "epi-t2", "within-modality")
    cohorts = ("ordinary", "large-initialization", "partial-slab", "dropout-low-signal")
    cases = []
    subject_index = 0
    for mode_index, mode in enumerate(modes):
        for cohort_index, cohort in enumerate(cohorts):
            for replicate in range(2):
                subject = ROLE_SUBJECTS["training"][subject_index]
                seed = 104729 + mode_index * 1009 + cohort_index * 101 + replicate * 17
                cases.append(case_observations(subject, mode, cohort, seed))
                subject_index += 1
    role_commitments = {
        role: {
            "subject_ids": subjects,
            "subject_ids_sha256": sha256_bytes(canonical_bytes(subjects)),
            "status": "open-training-input" if role == "training" else "reserved-unopened-role",
        }
        for role, subjects in ROLE_SUBJECTS.items()
    }
    return {
        "schema_version": "1.0.0",
        "fixture_id": "flashalign-linear-preset-training-v1",
        "provenance": {
            "kind": "analytic-synthetic",
            "license": "Apache-2.0",
            "generator": "generate_linear_preset_training.py",
            "claim_boundary": "Engineering loss and policy calibration only; no MRI accuracy or runtime claim.",
        },
        "role_commitments": role_commitments,
        "training_cases": cases,
    }


def summarize_mode(mode: str, cases: list[dict[str, Any]]) -> dict[str, Any]:
    policy = PRESETS[mode]
    matching = [case for case in cases if case["mode"] == mode]
    margins = []
    inliers = []
    overlaps = []
    coverages = []
    capture_covered = []
    qc_by_cohort: dict[str, list[bool]] = {}
    for case in matching:
        aligned_stats = [loss_and_inlier_weight(value, policy) for value in case["aligned_correlations"]]
        displaced_stats = [loss_and_inlier_weight(value, policy) for value in case["displaced_correlations"]]
        aligned_loss = sum(value[0] for value in aligned_stats) / len(aligned_stats)
        displaced_loss = sum(value[0] for value in displaced_stats) / len(displaced_stats)
        margins.append(displaced_loss - aligned_loss)
        inliers.append(sum(value[1] for value in aligned_stats) / len(aligned_stats))
        overlaps.append(case["valid_overlap_fraction"])
        coverages.append(case["spatial_inlier_coverage"])
        capture_covered.append(
            case["required_capture_angle_degrees"] <= policy["capture_maximum_angle_degrees"]
        )
        qc_by_cohort.setdefault(case["cohort"], []).append(
            case["valid_overlap_fraction"] >= policy["minimum_overlap_fraction"]
            and case["spatial_inlier_coverage"] >= policy["minimum_spatial_inlier_coverage"]
            and inliers[-1] >= policy["minimum_patch_inlier_weight"]
        )
    ordinary_accepted = all(qc_by_cohort["ordinary"])
    stress_flagged = any(
        not accepted
        for cohort in ("partial-slab", "dropout-low-signal")
        for accepted in qc_by_cohort[cohort]
    )
    return {
        "training_subjects": len(matching),
        "minimum_aligned_vs_displaced_loss_margin": min(margins),
        "minimum_mean_patch_inlier_weight": min(inliers),
        "minimum_valid_overlap_fraction": min(overlaps),
        "minimum_spatial_inlier_coverage": min(coverages),
        "capture_envelope_covered": all(capture_covered),
        "qc_accepted_by_cohort": {
            cohort: sum(1 for accepted in values if accepted)
            for cohort, values in qc_by_cohort.items()
        },
        "checks": {
            "positive_loss_margin": min(margins) > 0.0,
            "finite_bounded_outlier_cost": math.isfinite(
                -(policy["tau"] ** 2) * math.log(policy["outlier_floor"])
            ),
            "capture_envelope": all(capture_covered),
            "sampling_uniform_component": policy["uniform_probability_mixture"] >= 0.25,
            "production_candidate_budget": 10000 <= policy["candidate_patches"] <= 30000,
            "qc_accepts_ordinary": ordinary_accepted,
            "qc_flags_stress_case": stress_flagged,
        },
    }


def build_receipt(fixture: dict[str, Any]) -> dict[str, Any]:
    cases = fixture["training_cases"]
    summaries = {
        mode: summarize_mode(mode, cases)
        for mode in ("epi-t1", "epi-t2", "within-modality")
    }
    slab_cases = [case.copy() for case in cases if case["mode"] == "epi-t1"]
    for case in slab_cases:
        case["mode"] = "slab-to-anatomy"
    summaries["slab-to-anatomy"] = summarize_mode("slab-to-anatomy", slab_cases)
    all_checks = [
        passed
        for summary in summaries.values()
        for passed in summary["checks"].values()
    ]
    roles = fixture["role_commitments"]
    flattened = [subject for role in roles.values() for subject in role["subject_ids"]]
    return {
        "schema_version": "1.0.0",
        "receipt_id": "flashalign-linear-preset-calibration-v1",
        "status": "engineering-presets-frozen",
        "evidence_scope": "analytic-synthetic-training-only",
        "claim_boundary": "Does not establish MRI accuracy, clinical validity, external superiority or runtime targets.",
        "training_fixture_sha256": sha256_bytes(canonical_bytes(fixture)),
        "oracle_inputs": [
            {"path": str(path.relative_to(ROOT)), "sha256": sha256_file(path)} for path in ORACLES
        ],
        "sample_roles": {
            "unit": "synthetic-subject",
            "subject_disjoint": len(flattened) == len(set(flattened)),
            "commitments": roles,
            "selection_audit_test_inputs_opened": False,
            "leakage_rule": "Only training observations are present. Selection, audit and test identities are committed but their future inputs and outcomes are absent; new immutable input manifests must bind those identities before use.",
        },
        "candidate_source": {
            "kind": "explicit-reviewed-grid",
            "script_defaults_automatically_adopted": False,
            "statement": "The script evaluates the frozen candidates below. Values were reviewed against PRD constraints, component validators, training stress cases and work budgets; the generator does not rewrite product source.",
        },
        "frozen_presets": PRESETS,
        "training_summary": summaries,
        "decisions": [
            "EPI-T1 retains a polarity mixture biased toward negative correlation; EPI-T2 retains a polarity mixture biased toward positive correlation; within-modality uses a strong but non-degenerate positive prior.",
            "The robust scale and outlier floor retain a finite bounded invalid-patch cost and positive aligned-versus-displaced loss margin in every declared training cohort.",
            "A uniform sampling component of at least 0.25 protects against transform-dependent information screening; optimization, selection and audit draws remain separate immutable roles.",
            "Candidate populations remain in the PRD production range. Slab mode spends more samples and uses lower overlap and coverage gates because partial support is part of its declared use.",
            "Capture schedules cover the declared training rotation envelope. Trust limits remain physical millimetre controls and damping does not enter the objective.",
            "QC limits are gates on overlap, spatial coverage and data-only conditioning. Priors and damping cannot manufacture data information.",
        ],
        "all_declared_training_checks_pass": all(all_checks),
    }


def build_manifest(fixture_bytes: bytes, receipt_bytes: bytes) -> dict[str, Any]:
    role_hashes = {
        role: sha256_bytes(canonical_bytes(subjects)) for role, subjects in ROLE_SUBJECTS.items()
    }
    return {
        "$schema": "../schemas/linear-preset-calibration-v1.schema.json",
        "schema_version": "1.0.0",
        "manifest_id": "flashalign-linear-preset-calibration-v1",
        "status": "training-frozen-holdouts-reserved",
        "artifacts": {
            "prd": {"path": str(PRD_PATH.relative_to(ROOT)), "sha256": sha256_file(PRD_PATH)},
            "generator": {"path": str(Path(__file__).resolve().relative_to(ROOT)), "sha256": sha256_file(Path(__file__).resolve())},
            "fixture": {"path": str(FIXTURE_PATH.relative_to(ROOT)), "sha256": sha256_bytes(fixture_bytes)},
            "receipt": {"path": str(RECEIPT_PATH.relative_to(ROOT)), "sha256": sha256_bytes(receipt_bytes)},
            "preset_source": {"path": str(SOURCE_PATH.relative_to(ROOT)), "sha256": sha256_file(SOURCE_PATH)},
        },
        "roles": {
            "unit": "synthetic-subject",
            "training_subject_ids_sha256": role_hashes["training"],
            "selection_subject_ids_sha256": role_hashes["selection"],
            "audit_subject_ids_sha256": role_hashes["audit"],
            "test_subject_ids_sha256": role_hashes["test"],
            "all_roles_subject_disjoint": True,
            "unopened_roles": ["selection", "audit", "test"],
        },
        "claim_boundary": "Synthetic engineering calibration only. External MRI qualification remains a separate sealed benchmark gate.",
    }


def generate() -> dict[Path, bytes]:
    fixture = build_fixture()
    fixture_bytes = canonical_bytes(fixture)
    receipt = build_receipt(fixture)
    receipt_bytes = canonical_bytes(receipt)
    manifest = build_manifest(fixture_bytes, receipt_bytes)
    return {
        FIXTURE_PATH: fixture_bytes,
        RECEIPT_PATH: receipt_bytes,
        MANIFEST_PATH: canonical_bytes(manifest),
    }


def verify_semantics(outputs: dict[Path, bytes]) -> None:
    fixture = json.loads(outputs[FIXTURE_PATH])
    receipt = json.loads(outputs[RECEIPT_PATH])
    manifest = json.loads(outputs[MANIFEST_PATH])
    roles = fixture["role_commitments"]
    subjects = [subject for role in roles.values() for subject in role["subject_ids"]]
    if len(subjects) != len(set(subjects)):
        raise RuntimeError("sample roles are not subject-disjoint")
    if set(manifest["roles"]["unopened_roles"]) != {"selection", "audit", "test"}:
        raise RuntimeError("selection/audit/test roles are not sealed")
    if receipt["sample_roles"]["selection_audit_test_inputs_opened"]:
        raise RuntimeError("holdout inputs were marked opened during training calibration")
    if not receipt["all_declared_training_checks_pass"]:
        raise RuntimeError("one or more declared training checks failed")
    if sha256_bytes(outputs[FIXTURE_PATH]) != receipt["training_fixture_sha256"]:
        raise RuntimeError("receipt does not bind the generated training fixture")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="verify committed bytes instead of writing")
    args = parser.parse_args()
    outputs = generate()
    verify_semantics(outputs)
    if args.check:
        drift = [str(path.relative_to(ROOT)) for path, data in outputs.items() if not path.is_file() or path.read_bytes() != data]
        if drift:
            raise SystemExit("calibration evidence drift: " + ", ".join(drift))
        print("flashalign linear preset calibration: verified")
    else:
        for path, data in outputs.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        print("flashalign linear preset calibration: generated")


if __name__ == "__main__":
    main()
