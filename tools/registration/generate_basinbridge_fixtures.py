#!/usr/bin/env python3
"""Generate deterministic BasinBridge B0 correspondence fixtures.

The fixture is deliberately independent of the Scala implementation.  It uses
only closed-form transforms and the Python standard library, so it can define
the correspondence convention without sharing a matcher or projector bug.
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT = (
    ROOT
    / "modules"
    / "reframe4s-halfflow"
    / "fixtures"
    / "v1"
    / "basin-bridge-v1.json"
)
SCHEMA = "reframe4s-basinbridge-fixtures-v1"
ROUND_DIGITS = 14
SEED_COORDINATES = (-20.0, -12.0, -4.0, 4.0, 12.0)
BACKGROUND_CASES = {
    "translation-12mm": (12.0, 0.0, 0.0),
    "regional-residual": (6.0, -2.0, 1.0),
    "outlier-robustness": (5.0, 0.0, 0.0),
    "boundary-support": (4.0, 0.0, 0.0),
}

Vector3 = tuple[float, float, float]


def clean_float(value: float) -> float:
    if not math.isfinite(value):
        raise ValueError(f"fixture produced a non-finite value: {value}")
    rounded = float(format(value, f".{ROUND_DIGITS}g"))
    return 0.0 if rounded == 0.0 else rounded


def clean_tree(value: Any) -> Any:
    if isinstance(value, float):
        return clean_float(value)
    if isinstance(value, tuple):
        return [clean_tree(item) for item in value]
    if isinstance(value, list):
        return [clean_tree(item) for item in value]
    if isinstance(value, dict):
        return {key: clean_tree(item) for key, item in value.items()}
    return value


def add(left: Vector3, right: Vector3) -> Vector3:
    return tuple(left[index] + right[index] for index in range(3))  # type: ignore[return-value]


def subtract(left: Vector3, right: Vector3) -> Vector3:
    return tuple(left[index] - right[index] for index in range(3))  # type: ignore[return-value]


def scale(value: Vector3, factor: float) -> Vector3:
    return tuple(component * factor for component in value)  # type: ignore[return-value]


def norm(value: Vector3) -> float:
    return math.sqrt(sum(component * component for component in value))


def rotation_z(point: Vector3, angle_degrees: float) -> Vector3:
    angle = math.radians(angle_degrees)
    cosine = math.cos(angle)
    sine = math.sin(angle)
    x, y, z = point
    return (
        cosine * x - sine * y,
        sine * x + cosine * y,
        z,
    )


def affine_background(point: Vector3) -> Vector3:
    x, y, z = point
    return (
        1.04 * x + 0.06 * y + 2.0,
        -0.03 * x + 0.97 * y - 1.5,
        0.02 * x + 0.04 * y + 1.01 * z + 0.75,
    )


def regional_transform(point: Vector3) -> Vector3:
    background = add(point, BACKGROUND_CASES["regional-residual"])
    x, y, z = point
    envelope = math.exp(-0.5 * ((x + 2.0) ** 2 + (y - 1.0) ** 2 + (z - 1.0) ** 2) / 8.0**2)
    residual = (0.0, 5.0 * envelope, -3.0 * envelope)
    return add(background, residual)


def correspondence(
    q_fixed: Vector3,
    p_moving: Vector3,
    *,
    confidence: float = 1.0,
    oracle_outlier: bool = False,
) -> dict[str, Any]:
    midpoint = scale(add(p_moving, q_fixed), 0.5)
    tangent = subtract(p_moving, q_fixed)
    return {
        "p": p_moving,
        "q": q_fixed,
        "midpoint": midpoint,
        "tangent": tangent,
        "confidence": confidence,
        "oracleOutlier": oracle_outlier,
    }


def seed_points() -> Iterable[Vector3]:
    for z in SEED_COORDINATES:
        for y in SEED_COORDINATES:
            for x in SEED_COORDINATES:
                yield (x, y, z)


def make_case(case_id: str) -> dict[str, Any]:
    correspondences: list[dict[str, Any]] = []
    for q_fixed in seed_points():
        if case_id == "translation-12mm":
            p_moving = add(q_fixed, (12.0, 0.0, 0.0))
        elif case_id == "rotation-4deg":
            p_moving = rotation_z(q_fixed, 4.0)
        elif case_id == "affine-background":
            p_moving = affine_background(q_fixed)
        elif case_id == "regional-residual":
            p_moving = regional_transform(q_fixed)
        elif case_id == "outlier-robustness":
            p_moving = add(q_fixed, BACKGROUND_CASES[case_id])
            if q_fixed == (-4.0, -4.0, -4.0):
                p_moving = add(p_moving, (24.0, -18.0, 12.0))
                correspondences.append(
                    correspondence(q_fixed, p_moving, oracle_outlier=True)
                )
                continue
        elif case_id == "boundary-support":
            p_moving = add(q_fixed, BACKGROUND_CASES[case_id])
        else:
            raise ValueError(f"unknown fixture case: {case_id}")
        correspondences.append(correspondence(q_fixed, p_moving))

    expected: dict[str, Any] = {
        "seedCount": len(correspondences),
        "seedSpacingMm": 8.0,
    }
    if case_id in BACKGROUND_CASES:
        expected["backgroundTranslationMm"] = BACKGROUND_CASES[case_id]
    if case_id == "translation-12mm":
        expected["constantTangentMm"] = (12.0, 0.0, 0.0)
    elif case_id == "rotation-4deg":
        expected["rotationDegrees"] = 4.0
    elif case_id == "affine-background":
        expected["linearPart"] = (
            (1.04, 0.06, 0.0),
            (-0.03, 0.97, 0.0),
            (0.02, 0.04, 1.01),
        )
    elif case_id == "regional-residual":
        expected["residualEnvelope"] = "gaussian(center=(-2,1,1), sigma=8mm)"
    elif case_id == "outlier-robustness":
        expected["outlierCount"] = 1
        expected["outlierTangentNormMm"] = norm((29.0, -18.0, 12.0))
    elif case_id == "boundary-support":
        expected["boundarySeedCount"] = sum(
            1
            for item in correspondences
            if any(abs(float(component)) >= 20.0 for component in item["q"])
        )

    return {
        "id": case_id,
        "kind": {
            "translation-12mm": "constant-translation",
            "rotation-4deg": "rotation",
            "affine-background": "affine-background",
            "regional-residual": "regional-residual",
            "outlier-robustness": "outlier",
            "boundary-support": "boundary",
        }[case_id],
        "oracle": {
            "type": "closed-form-known-transform",
            "status": "executable",
            "notAnatomicalGroundTruth": True,
        },
        "expected": expected,
        "correspondences": correspondences,
    }


def build_fixture() -> dict[str, Any]:
    cases = [
        make_case(case_id)
        for case_id in (
            "translation-12mm",
            "rotation-4deg",
            "affine-background",
            "regional-residual",
            "outlier-robustness",
            "boundary-support",
        )
    ]
    return {
        "schema": SCHEMA,
        "generator": {
            "path": "tools/registration/generate_basinbridge_fixtures.py",
            "version": 1,
            "dependencies": ["python-standard-library"],
        },
        "coordinateContract": {
            "mapKind": "physical-coordinate-pull",
            "pRole": "moving-source coordinate",
            "qRole": "fixed-target coordinate",
            "midpoint": "z = (p + q) / 2",
            "tangent": "t = p - q",
            "translationExample": "moving(x) = fixed(x - shift) yields p - q = +shift",
            "composition": "B(A(x)) for A >>> B",
        },
        "grid": {
            "shape": [49, 49, 49],
            "spacingMm": [1.0, 1.0, 1.0],
            "originMm": [-24.0, -24.0, -24.0],
            "seedCoordinatesMm": list(SEED_COORDINATES),
            "seedSpacingMm": 8.0,
        },
        "externalOracle": {
            "type": "same-affine-ants",
            "status": "required-external-input",
            "artifactRoot": "user-provided licensed input",
            "affinePolicy": "one hashed supplied ITK affine; affine estimation disabled",
            "sourceRole": "engineering oracle, not anatomical ground truth",
            "evaluationOnly": ["moving-mask", "labels"],
            "note": "No licensed anatomical input is checked into this repository.",
        },
        "inputRoles": {
            "optimization": ["fixed-native-pyramid", "moving-native-pyramid", "fixed-mask"],
            "evaluationOnly": ["moving-mask", "labels", "same-affine-ants-reference"],
        },
        "lanes": [
            {
                "id": "oracle-to-bridge",
                "correspondenceSource": "same-affine-ants-or-known-transform",
                "bridge": "BasinBridge",
                "fineRefinement": "disabled",
                "purpose": "measure projection and topology before HalfFlow",
            },
            {
                "id": "oracle-to-bridge-to-halfflow",
                "correspondenceSource": "same-affine-ants-or-known-transform",
                "bridge": "BasinBridge",
                "fineRefinement": "frozen-HalfFlow-budget2x",
                "purpose": "separate assimilation from local refinement",
            },
            {
                "id": "block-to-bridge-to-halfflow",
                "correspondenceSource": "current-block-search",
                "bridge": "BasinBridge",
                "fineRefinement": "frozen-HalfFlow-budget2x",
                "purpose": "measure matcher versus assimilation loss",
            },
        ],
        "cases": cases,
        "measurementPlan": [
            "weighted-match-error-p50-p95",
            "true-neighborhood-cc-loss",
            "internal-label-dice-or-surface-distance",
            "tre-when-available",
            "sampled-jacobian-distribution-and-fold-count",
            "export-inverse-interior-p99-and-maximum",
            "active-support-and-edge-band-fractions",
            "accepted-alpha-and-rematch-rounds",
            "rejection-reasons",
            "runtime-allocation-and-peak-memory",
        ],
        "status": {
            "syntheticFixtures": "complete",
            "sameAffineAntsExtraction": "pending-external-input",
            "anatomicalAccuracy": "notRun",
        },
    }


def validate_fixture(fixture: dict[str, Any]) -> None:
    if fixture.get("schema") != SCHEMA:
        raise ValueError("unexpected BasinBridge fixture schema")
    contract = fixture.get("coordinateContract", {})
    if contract.get("pRole") != "moving-source coordinate":
        raise ValueError("p must be the moving-source coordinate")
    if contract.get("qRole") != "fixed-target coordinate":
        raise ValueError("q must be the fixed-target coordinate")
    expected_lanes = {
        "oracle-to-bridge",
        "oracle-to-bridge-to-halfflow",
        "block-to-bridge-to-halfflow",
    }
    actual_lanes = {lane.get("id") for lane in fixture.get("lanes", [])}
    if actual_lanes != expected_lanes:
        raise ValueError(f"unexpected lane set: {actual_lanes}")
    cases = fixture.get("cases", [])
    if [case.get("id") for case in cases] != [
        "translation-12mm",
        "rotation-4deg",
        "affine-background",
        "regional-residual",
        "outlier-robustness",
        "boundary-support",
    ]:
        raise ValueError("fixture cases are not in the frozen order")
    for case in cases:
        correspondences = case.get("correspondences", [])
        if len(correspondences) != 125:
            raise ValueError(f"{case.get('id')} does not contain 125 seeds")
        outliers = 0
        for item in correspondences:
            p = tuple(float(value) for value in item["p"])
            q = tuple(float(value) for value in item["q"])
            midpoint = tuple(float(value) for value in item["midpoint"])
            tangent = tuple(float(value) for value in item["tangent"])
            expected_midpoint = scale(add(p, q), 0.5)
            expected_tangent = subtract(p, q)
            if max(abs(midpoint[index] - expected_midpoint[index]) for index in range(3)) > 1e-12:
                raise ValueError(f"{case.get('id')} midpoint contract failed")
            if max(abs(tangent[index] - expected_tangent[index]) for index in range(3)) > 1e-12:
                raise ValueError(f"{case.get('id')} tangent contract failed")
            confidence = float(item["confidence"])
            if not 0.0 <= confidence <= 1.0:
                raise ValueError(f"{case.get('id')} confidence is outside [0,1]")
            outliers += int(bool(item.get("oracleOutlier", False)))
        if case["id"] == "outlier-robustness" and outliers != 1:
            raise ValueError("outlier fixture must contain exactly one marked outlier")
        if case["id"] != "outlier-robustness" and outliers != 0:
            raise ValueError(f"{case['id']} unexpectedly contains an outlier")
    if fixture.get("inputRoles", {}).get("moving-mask") is not None:
        raise ValueError("moving mask must not be an optimization input")
    if fixture.get("externalOracle", {}).get("status") != "required-external-input":
        raise ValueError("external anatomical oracle status must remain explicit")


def render_fixture(fixture: dict[str, Any]) -> str:
    return json.dumps(clean_tree(fixture), indent=2, sort_keys=True, allow_nan=False) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="fail if the checked-in fixture is stale")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    arguments = parser.parse_args()
    fixture = build_fixture()
    validate_fixture(fixture)
    rendered = render_fixture(fixture)
    output = arguments.output if arguments.output.is_absolute() else ROOT / arguments.output
    if arguments.check:
        if not output.exists():
            raise SystemExit(f"missing fixture: {output.relative_to(ROOT)}")
        if output.read_text(encoding="utf-8") != rendered:
            raise SystemExit(f"fixture is stale: run {Path(__file__).relative_to(ROOT)}")
        print(f"fixture current: {output.relative_to(ROOT)}")
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(rendered, encoding="utf-8")
        print(f"wrote {output.relative_to(ROOT)}")
    print(
        f"validated {len(fixture['cases'])} cases, "
        f"{sum(len(case['correspondences']) for case in fixture['cases'])} correspondences"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
