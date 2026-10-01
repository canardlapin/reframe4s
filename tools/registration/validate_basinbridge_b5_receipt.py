#!/usr/bin/env python3
"""Validate the non-promotional structural boundary of the B5 receipt."""

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
    / "basinbridge-b5-rank-one-2026-08-02.json"
)
SCHEMA = "reframe4s-basinbridge-b5-rank-one-receipt-v1"


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
    require(receipt.get("schema") == SCHEMA, "unexpected B5 receipt schema")
    require(receipt.get("status") == "rejected-jvm-falsification", "B5 status must remain non-promotional")
    candidate = receipt.get("candidate", {})
    require(candidate.get("productionDefaultChanged") is False, "candidate changed the production default")
    require(candidate.get("factorChanged") == "rank-one scaling only", "B5 changed more than one factor")
    matrix = receipt.get("matrix", [])
    require([row.get("shiftMm") for row in matrix] == [2.0, 4.0, 8.0, 12.0], "B5 matrix is incomplete")
    require([row.get("fine") for row in matrix[:2]] == ["passed", "passed"], "B5 lost a baseline pass")
    require(all("topology" in row.get("fine", "") for row in matrix[2:]), "B5 failure type was weakened")
    decision = receipt.get("decision", {})
    require(decision.get("status") == "reject", "B5 candidate was not rejected")
    require(decision.get("retainProductionChange") is False, "rejected candidate was retained")
    external = receipt.get("external", {})
    require(external.get("labeledDevelopmentPairsRequired") == 5, "labeled-pair gate changed")
    require(external.get("labeledDevelopmentPairsAvailable") == 0, "external data boundary changed")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--receipt", type=Path, default=DEFAULT_RECEIPT)
    arguments = parser.parse_args()
    receipt_path = arguments.receipt if arguments.receipt.is_absolute() else ROOT / arguments.receipt
    validate(json.loads(receipt_path.read_text(encoding="utf-8")))
    print(f"B5 receipt boundary valid: {receipt_path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
