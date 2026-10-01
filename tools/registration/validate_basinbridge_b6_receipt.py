#!/usr/bin/env python3
"""Validate the one-factor, non-promotional B6 rematch-support receipt."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_RECEIPT = ROOT / "docs" / "benchmarks" / "receipts" / "basinbridge-b6-rematch-support-2026-08-02.json"
SCHEMA = "reframe4s-basinbridge-b6-rematch-support-receipt-v1"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def finite_tree(value: Any, path: str = "receipt") -> None:
    if isinstance(value, float):
        require(math.isfinite(value), f"non-finite value at {path}")
    elif isinstance(value, list):
        for index, item in enumerate(value):
            finite_tree(item, f"{path}[{index}]")
    elif isinstance(value, dict):
        for key, item in value.items():
            finite_tree(item, f"{path}.{key}")


def validate(receipt: dict[str, Any]) -> None:
    finite_tree(receipt)
    require(receipt.get("schema") == SCHEMA, "unexpected B6 receipt schema")
    require(receipt.get("status") == "accepted-rematch-contract-no-accuracy-promotion", "non-promotional status changed")
    require(receipt.get("promotionEligible") is False, "B6 was made promotion eligible")

    contract = receipt.get("contract", {})
    require(contract.get("strictInitialSearchChanged") is False, "strict initial search changed")
    require(contract.get("retainingSearchUsedOnlyAfterAcceptedAssimilation") is True, "retaining search escaped rematch")
    require(contract.get("allOtherFailuresRemainFatal") is True, "fatal matcher failures became droppable")
    require(contract.get("minimumRetainedAnchors") == 4, "retained-anchor floor changed")
    require(contract.get("minimumRetainedAnchorsBoundedByRequestedCount") is True, "small declared anchor sets became impossible")
    require(contract.get("minimumRetainedFraction") == 0.5, "retained fraction changed")
    require(contract.get("movingEvaluationMaskUsedForOptimization") is False, "moving mask leaked into fitting")
    require(contract.get("labelsUsedForOptimization") is False, "labels leaked into fitting")

    synthetic = receipt.get("syntheticVerification", {})
    for platform in ("jvm", "scalaJs"):
        require(synthetic.get(platform, {}).get("status") == "passed", f"{platform} verification did not pass")
        require(synthetic.get(platform, {}).get("tests") == 16, f"{platform} test count changed")

    broad = receipt.get("broadVerification", {})
    require(broad.get("jvm", {}).get("status") == "passed", "broad JVM gate did not pass")
    require(broad.get("jvm", {}).get("tests") == 143, "broad JVM test count changed")
    require(broad.get("jvm", {}).get("failed") == 0, "broad JVM failures hidden")
    require(broad.get("scalaJs", {}).get("status") == "affected-shared-suites-passed", "Scala.js scope overstated")

    sub18 = receipt.get("sub18", {})
    require(sub18.get("status") == "complete-round-returned-unsafe-state", "complete-round result hidden")
    initial = sub18.get("initialMatch", {})
    require(initial.get("strictSearch") is True, "initial search was not strict")
    require(initial.get("requestedAnchors") == 18, "initial anchor count changed")
    require(initial.get("correspondences") == 18, "initial correspondence count changed")

    rematch = sub18.get("rematch", {})
    require(rematch.get("status") == "completed", "B6 rematch did not complete")
    require(rematch.get("requestedAnchors") == 18, "rematch request count changed")
    require(rematch.get("retainedAnchors") == 15, "rematch retained count changed")
    require(rematch.get("retainedIndices") == [0, 2, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17], "retained order changed")
    dropped = rematch.get("dropped", [])
    require([item.get("index") for item in dropped] == [1, 3, 5], "dropped-anchor set changed")
    require(all(item.get("reason") == "InsufficientReferenceSupport" for item in dropped), "unexpected drop reason")
    require(all(item.get("direction") == "Forward" for item in dropped), "unexpected drop direction")

    bridge = sub18.get("bridge", {})
    require(bridge.get("accepted") is True, "bridge acceptance disappeared")
    require(bridge.get("finalMatchErrorMm") < bridge.get("initialMatchErrorMm"), "bridge match error did not improve")
    require(bridge.get("stateNumericallyIdenticalToBaselinePartialAssimilation") is True, "rematch changed accepted state")
    metrics = sub18.get("metrics", {})
    require(metrics.get("accuracyChangedByDiagnosticRematch") is False, "rematch claimed an accuracy change")
    require(metrics.get("gradientNcc", 0.0) < 0.0, "negative gradient-NCC evidence disappeared")

    fine = sub18.get("fine", {})
    require(fine.get("status") == "rejected-before-full-resolution-refinement", "fine rejection hidden")
    require(fine.get("topologyFloorWasLoosened") is False, "topology floor was loosened")
    require(fine.get("minimumObservedFixedAccumulatedJacobian", 1.0) < fine.get("minimumRequiredAccumulatedJacobian", 0.0), "fine rejection lacks numeric support")
    export = sub18.get("export", {})
    require(export.get("status") == "rejected-fixed-residual-inverse-tolerance", "export rejection hidden")
    require(export.get("endpointJacobianNonPositive", 0) > 0, "non-positive endpoint Jacobians hidden")

    decision = receipt.get("decision", {})
    require(decision.get("retainSupportAwareRematch") is True, "support contract was not retained")
    require(decision.get("accuracyPromoted") is False, "accuracy was promoted from an unsafe state")
    require("global-affine" in decision.get("nextFactor", ""), "next factor does not preserve decomposition")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--receipt", type=Path, default=DEFAULT_RECEIPT)
    arguments = parser.parse_args()
    path = arguments.receipt if arguments.receipt.is_absolute() else ROOT / arguments.receipt
    validate(json.loads(path.read_text(encoding="utf-8")))
    print(f"BasinBridge B6 rematch-support receipt valid: {path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
