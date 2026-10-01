#!/usr/bin/env python3
"""Validate the non-promotional, failure-retaining sub-18 head-to-head receipt."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_RECEIPT = (
    ROOT / "docs" / "benchmarks" / "receipts" / "basinbridge-sub18-headtohead-2026-08-02.json"
)
SCHEMA = "reframe4s-basinbridge-sub18-headtohead-receipt-v1"


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
    require(receipt.get("schema") == SCHEMA, "unexpected sub18 receipt schema")
    require(
        receipt.get("status") == "observed-diagnostic-method-incomplete-external-promotion-pending",
        "sub18 status must retain incomplete method result",
    )
    require(receipt.get("promotionEligible") is False, "sub18 diagnostic was made promotion eligible")
    require(receipt.get("source", {}).get("dataLicenseEvidence") is None, "unverified data license admitted")
    artifacts = {item["role"]: item for item in receipt.get("artifacts", [])}
    require(len(artifacts) == 6, "sub18 artifact inventory changed")
    require(
        artifacts["optimization-moving-softbrain"]["sha256"]
        == "917dd8c4c6cf6fcc96c445fa0e17f88dfad469853e5bc3509bf17b81fb440d06",
        "moving hash changed",
    )
    require(
        artifacts["saved-ants-warped-moving-reference"]["sha256"]
        == "54e7ff7ea17adb15b8ef99627d64f5cf03f534f67ec9ab26fbdf6654ec39d835",
        "saved ANTs output hash changed",
    )
    oracle = receipt.get("oracleContract", {})
    require(oracle.get("scalaHeaderBaselineMatched") is True, "header differential oracle did not match")
    require(oracle.get("savedAntsMetricsMatched") is True, "ANTs differential oracle did not match")
    require(oracle.get("movingMaskUsedForOptimization") is False, "moving evaluation mask leaked into fitting")
    require(oracle.get("olderSavedPreMetricsRejectedAsStale") is True, "stale pre-metrics were admitted")
    frozen = receipt.get("frozenRun", {})
    require(frozen.get("anchorCount") == 18, "anchor count changed")
    require(frozen.get("minimumAccumulatedJacobian") == 0.05, "topology floor changed")
    require(frozen.get("retuneAfterObservation") is False, "post-observation retuning admitted")
    methods = {item["id"]: item for item in receipt.get("methods", [])}
    require(methods["basinbridge-complete-round"]["status"] == "failed-mandatory-rematch", "rematch failure hidden")
    bridge = methods["basinbridge-accepted-assimilation-diagnostic"]
    require(bridge.get("status") == "partial-state-not-method-complete", "partial bridge state promoted")
    require(bridge.get("finalMatchErrorMm") < bridge.get("initialMatchErrorMm"), "match error did not improve")
    require(bridge.get("exportStatus") == "rejected-fixed-residual-inverse-tolerance", "export rejection hidden")
    require(bridge.get("endpointJacobian", {}).get("nonPositive", 0) > 0, "fold evidence disappeared")
    require(
        methods["basinbridge-assimilation-then-budget2x-diagnostic"]["status"]
        == "rejected-before-full-resolution-refinement",
        "fine-stage topology rejection hidden",
    )
    require(
        methods["basinbridge-assimilation-then-budget2x-diagnostic"].get("topologyFloorWasLoosened") is False,
        "topology floor was loosened",
    )
    require(receipt.get("classification", {}).get("decision") == "do-not-tune-fine-objective-yet", "classification drifted")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--receipt", type=Path, default=DEFAULT_RECEIPT)
    arguments = parser.parse_args()
    path = arguments.receipt if arguments.receipt.is_absolute() else ROOT / arguments.receipt
    validate(json.loads(path.read_text(encoding="utf-8")))
    print(f"BasinBridge sub18 receipt boundary valid: {path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
