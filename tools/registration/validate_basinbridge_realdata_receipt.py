#!/usr/bin/env python3
"""Validate the non-promotional Hodgeflow real-data diagnostic receipt."""

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
    / "basinbridge-realdata-hodgeflow-2026-08-02.json"
)
SCHEMA = "reframe4s-basinbridge-realdata-diagnostic-receipt-v1"


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
    require(receipt.get("schema") == SCHEMA, "unexpected real-data receipt schema")
    require(
        receipt.get("status")
        == "source-tree-real-data-bridge-complete-external-promotion-pending",
        "real-data receipt may not claim promotion",
    )
    source = receipt.get("sourceTree", {})
    require(source.get("project") == "hodgeflow", "Hodgeflow source is missing")
    require(source.get("dataLicenseEvidence") is None, "unverified data license was admitted")
    case = receipt.get("case", {})
    require(case.get("sameAffine") is False, "diagnostic pair was mislabelled same-affine")
    require(case.get("movingShape") == [62, 93, 93], "moving shape changed")
    require(case.get("fixedShape") == [91, 109, 91], "fixed shape changed")
    require(
        case.get("movingSha256")
        == "e0563a9d227383125ed4b1514bc66f480ed016de68ce1dc4ed6699b436d93692",
        "moving hash changed",
    )
    require(
        case.get("fixedSha256")
        == "5f6e8fc4f60b8e53ec5574d536238034bc340aca01438d7ab32ee9df8ea80b4d",
        "fixed hash changed",
    )
    run = receipt.get("observed", {})
    require(run.get("status") == "passed", "real-data run did not pass")
    require(run.get("bridgeAccepted") is True, "bridge acceptance was not observed")
    require(run.get("finalMatchErrorMm") <= run.get("initialMatchErrorMm"), "match error did not decrease")
    require(run.get("finalCcLoss") <= run.get("initialCcLoss"), "true-CC loss did not decrease")
    require(
        receipt.get("interpretation", {}).get("externalGate", "").endswith("doNotRun=true"),
        "external gate was weakened",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--receipt", type=Path, default=DEFAULT_RECEIPT)
    arguments = parser.parse_args()
    receipt_path = arguments.receipt if arguments.receipt.is_absolute() else ROOT / arguments.receipt
    validate(json.loads(receipt_path.read_text(encoding="utf-8")))
    print(f"BasinBridge real-data receipt boundary valid: {receipt_path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
