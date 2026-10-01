#!/usr/bin/env python3
"""Validate the fail-closed BasinBridge external-court manifest."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MANIFEST = ROOT / "docs" / "benchmarks" / "manifests" / "basinbridge-external-v1.json"
SCHEMA = "reframe4s-basinbridge-external-manifest-v1"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def finite_tree(value: Any, path: str = "manifest") -> None:
    if isinstance(value, float):
        require(math.isfinite(value), f"non-finite value at {path}")
    elif isinstance(value, list):
        for index, item in enumerate(value):
            finite_tree(item, f"{path}[{index}]")
    elif isinstance(value, dict):
        for key, item in value.items():
            finite_tree(item, f"{path}.{key}")


def validate(manifest: dict[str, Any]) -> None:
    finite_tree(manifest)
    require(manifest.get("schema") == SCHEMA, "unexpected external manifest schema")
    require(manifest.get("status") == "pending-required-inputs", "manifest must remain fail-closed")
    inputs = manifest.get("requiredInputs", {})
    ants = inputs.get("sameAffineAnts", {})
    sub18 = inputs.get("sameAffineSub18", {})
    pairs = inputs.get("labeledDevelopmentPairs", {})
    require(ants.get("required") is True, "same-affine ANTs input is no longer required")
    require(ants.get("status") == "required-external-input", "same-affine ANTs boundary changed")
    require(ants.get("providedPath") is None, "unverified ANTs path was admitted")
    require(ants.get("sha256") is None, "unverified ANTs hash was admitted")
    require(sub18.get("required") is True, "sub18 input is no longer required")
    require(sub18.get("status") == "pending-external-input", "sub18 boundary changed")
    require(sub18.get("providedPath") is None, "unverified sub18 path was admitted")
    require(pairs.get("required") == 5, "labeled-pair requirement changed")
    require(pairs.get("available") == 0, "labeled-pair availability changed")
    require(pairs.get("status") == "pending-external-input", "labeled-pair boundary changed")
    require(pairs.get("pairIds") == [], "unverified labeled pair was admitted")
    pair_schema = manifest.get("pairSchema", {})
    require(len(pair_schema.get("requiredFields", [])) >= 10, "pair provenance schema is incomplete")
    require("movingMaskPath" in pair_schema.get("evaluationOnlyFields", []), "moving mask role missing")
    require("movingLabelsPath" in pair_schema.get("forbiddenMatchingInputs", []), "label boundary missing")
    frozen = manifest.get("frozenRun", {})
    require(len(frozen.get("lanes", [])) == 3, "frozen external lane set is incomplete")
    require(frozen.get("sameSettingsAcrossPairs") is True, "cross-pair settings are not frozen")
    require(frozen.get("sameMatcherAndHalfFlowProfile") is True, "matcher/HalfFlow profile is not frozen")
    require(frozen.get("retuneAfterObservation") is False, "post-observation retuning admitted")
    activation = manifest.get("activation", {})
    require(activation.get("doNotRun") is True, "manifest may not authorize a missing-input run")
    require(activation.get("observedRows") == [], "external result rows appeared without inputs")
    diagnostic = manifest.get("sourceTreeDiagnostic", {})
    require(
        diagnostic.get("status") == "observed-real-data-diagnostic",
        "source-tree real-data diagnostic is missing",
    )
    require(
        diagnostic.get("sameAffine") is False,
        "source-tree diagnostic was mislabelled same-affine",
    )
    require(diagnostic.get("promotionEligible") is False, "diagnostic was made promotion-eligible")
    require(diagnostic.get("licenseEvidence") is None, "unverified diagnostic license was admitted")
    require(diagnostic.get("doNotSubstituteForExternalCourt") is True, "diagnostic boundary was weakened")
    sub18_diagnostic = manifest.get("sub18HeadToHeadDiagnostic", {})
    require(
        sub18_diagnostic.get("status") == "observed-method-incomplete",
        "sub18 diagnostic status changed",
    )
    require(sub18_diagnostic.get("sameAffine") is False, "sub18 diagnostic was mislabelled same-affine")
    require(sub18_diagnostic.get("oracleMetricsMatched") is True, "sub18 metric oracle did not match")
    require(sub18_diagnostic.get("completeBasinBridgeRound") is False, "failed rematch was hidden")
    require(sub18_diagnostic.get("fineStageCompleted") is False, "fine topology rejection was hidden")
    require(sub18_diagnostic.get("safeExport") is False, "rejected export was promoted")
    require(sub18_diagnostic.get("promotionEligible") is False, "sub18 diagnostic was made promotion-eligible")
    require(sub18_diagnostic.get("licenseEvidence") is None, "unverified sub18 license was admitted")
    require(
        sub18_diagnostic.get("doNotSubstituteForExternalCourt") is True,
        "sub18 diagnostic boundary was weakened",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    arguments = parser.parse_args()
    manifest_path = arguments.manifest if arguments.manifest.is_absolute() else ROOT / arguments.manifest
    validate(json.loads(manifest_path.read_text(encoding="utf-8")))
    print(f"BasinBridge external manifest boundary valid: {manifest_path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
