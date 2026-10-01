#!/usr/bin/env python3
"""Validate the non-promotional B5 boundary/midpoint follow-up receipt."""

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
    / "basinbridge-b5-followup-2026-08-02.json"
)
SCHEMA = "reframe4s-basinbridge-b5-followup-receipt-v1"


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
    require(receipt.get("schema") == SCHEMA, "unexpected B5 follow-up schema")
    require(receipt.get("status") == "rejected-jvm-falsifications", "follow-up crossed its evidence boundary")
    experiments = receipt.get("experiments", [])
    require([experiment.get("name") for experiment in experiments] == [
        "true-computational-halo",
        "fixed-anchor-midpoint-action",
    ], "required one-factor experiments are missing")
    for experiment in experiments:
        require(experiment.get("topologyFloorsChanged") is False, "topology floor changed")
        matrix = experiment.get("matrix", [])
        require([row.get("shiftMm") for row in matrix] == [8.0, 12.0], "follow-up matrix is incomplete")
        require(all(row.get("bridge") == "accepted" for row in matrix), "bridge was not accepted in every case")
        require(all("topology" in row.get("fine", "").lower() for row in matrix), "typed topology failure was weakened")
        require(all(row.get("widenedSafeBasin") is False for row in matrix), "negative result became promotional")
    fixed_anchor = experiments[1]
    require(fixed_anchor.get("productionDefaultChanged") is False, "production default changed")
    decision = receipt.get("decision", {})
    require(decision.get("status") == "reject", "follow-up was not rejected")
    require(decision.get("retainProductionChange") is False, "rejected change was retained")
    external = receipt.get("external", {})
    require(external.get("labeledDevelopmentPairsRequired") == 5, "labeled-pair gate changed")
    require(external.get("labeledDevelopmentPairsAvailable") == 0, "external data boundary changed")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--receipt", type=Path, default=DEFAULT_RECEIPT)
    arguments = parser.parse_args()
    receipt_path = arguments.receipt if arguments.receipt.is_absolute() else ROOT / arguments.receipt
    validate(json.loads(receipt_path.read_text(encoding="utf-8")))
    print(f"B5 follow-up receipt boundary valid: {receipt_path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
