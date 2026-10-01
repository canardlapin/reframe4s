#!/usr/bin/env python3
"""Validate the structural boundary of the B4 decomposition receipt.

This validator intentionally does not bless accuracy. It checks that the
receipt names all frozen lanes, records the contract controls, and keeps the
external anatomical gate explicit.
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_RECEIPT = (
    ROOT
    / "docs"
    / "benchmarks"
    / "receipts"
    / "basinbridge-b4-decomposition-2026-08-02.json"
)
SCHEMA = "reframe4s-basinbridge-b4-decomposition-receipt-v1"
LANES = {
    "oracle-to-bridge",
    "oracle-to-bridge-to-halfflow",
    "block-to-bridge-to-halfflow",
}


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
    require(receipt.get("schema") == SCHEMA, "unexpected B4 receipt schema")
    require(set(receipt.get("lanes", [])) == LANES, "B4 lane set is incomplete")
    contract = receipt.get("frozenContract", {})
    require(contract.get("grid") == [25, 25, 25], "unexpected contract grid")
    require(contract.get("spacingMm") == [1.0, 1.0, 1.0], "unexpected contract spacing")
    require(contract.get("blockRadiusVox") == [1, 1, 1], "unexpected block radius")
    require(contract.get("searchRadiusVox") == [3, 2, 2], "unexpected search radius")
    require(contract.get("minimumValidFraction") == 0.8, "unexpected matcher validity floor")
    require(contract.get("projectorSigmaMm") == 1.5, "unexpected projector sigma")
    require(contract.get("ccMaximumStepMm") == 4.0, "unexpected CC step bound")
    fine_levels = contract.get("fine", {}).get("levels", [])
    require(len(fine_levels) == 1, "contract fine profile must have one level")
    require(fine_levels[0].get("shrink") == 1, "unexpected fine shrink")
    require(fine_levels[0].get("maximumAttempts") == 2, "unexpected fine attempt budget")
    production = receipt.get("productionBudget2x", {})
    production_levels = production.get("levels", [])
    require(len(production_levels) == 2, "budget2x profile must retain two levels")
    require(
        [level.get("shrink") for level in production_levels] == [2, 1],
        "budget2x levels must descend from two to one",
    )
    require(production.get("metric") == "TrueNeighborhoodCc", "budget2x metric changed")
    external = receipt.get("external", {})
    require(
        external.get("manifest") == "docs/benchmarks/manifests/basinbridge-external-v1.json",
        "external manifest link is missing",
    )
    require(external.get("sameAffineAnts") == "required-external-input", "external oracle boundary was weakened")
    pairs = external.get("labeledDevelopmentPairs", {})
    require(pairs.get("required") == 5 and pairs.get("available") == 0, "labeled-pair gate changed")
    decision = receipt.get("decision", {})
    require(
        decision.get("status") in {"not_decided", "partial-classification"},
        "synthetic receipt cannot mint a promotional decision",
    )
    require("0.8" in decision.get("retentionThreshold", ""), "retention threshold is missing")
    require("oracle" in decision.get("oracleComparison", "").lower(), "oracle comparison must remain explicit")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--receipt", type=Path, default=DEFAULT_RECEIPT)
    arguments = parser.parse_args()
    receipt_path = arguments.receipt if arguments.receipt.is_absolute() else ROOT / arguments.receipt
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    validate(receipt)
    print(f"B4 receipt boundary valid: {receipt_path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
