#!/usr/bin/env python3
"""Validate the non-promotional structure of the B4 performance receipt."""

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
    / "basinbridge-b4-performance-2026-08-02.json"
)
SCHEMA = "reframe4s-basinbridge-b4-performance-receipt-v1"


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
    require(receipt.get("schema") == SCHEMA, "unexpected B4 performance schema")
    require(
        receipt.get("status")
        == "jvm-allocation-and-cross-platform-runtime-baseline-complete-process-envelope-qualified",
        "performance receipt status crossed its evidence boundary",
    )
    benchmark = receipt.get("benchmark", {})
    require(benchmark.get("grid") == [25, 25, 25], "unexpected benchmark grid")
    require(benchmark.get("forks") == 1, "benchmark fork policy changed")
    require(benchmark.get("measurementIterations") == 3, "benchmark measurement policy changed")
    setup = receipt.get("setupOracle", {})
    require(setup.get("nativeBridge") == "accepted", "native setup oracle missing")
    require(setup.get("nativeRematch") == "present", "native rematch setup oracle missing")
    profile = receipt.get("jvmGcProfile", {})
    native = profile.get("nativeRound", {})
    oracle = profile.get("oracleRound", {})
    require(native.get("averageMsPerOp", 0.0) > 0.0, "native runtime missing")
    require(oracle.get("averageMsPerOp", 0.0) > 0.0, "oracle runtime missing")
    require(native.get("allocationBytesPerOp", 0.0) > 0.0, "native allocation missing")
    require(oracle.get("allocationBytesPerOp", 0.0) > 0.0, "oracle allocation missing")
    probe = receipt.get("crossPlatformProbe", {})
    require(probe.get("schema") == "reframe4s-basinbridge-performance-probe-v1", "cross-platform probe missing")
    require(probe.get("grid") == [25, 25, 25], "cross-platform probe grid changed")
    require(probe.get("warmups") == 1, "cross-platform probe warmup policy changed")
    require(probe.get("samples") == 3, "cross-platform probe sample policy changed")
    jvm_probe = probe.get("jvm", {})
    jvm_native = jvm_probe.get("nativeRound", {})
    jvm_oracle = jvm_probe.get("oracleRound", {})
    require(jvm_native.get("medianNanos", 0) > 0, "JVM probe native runtime missing")
    require(jvm_oracle.get("medianNanos", 0) > 0, "JVM probe oracle runtime missing")
    require(jvm_native.get("medianAllocatedBytes", 0) > 0, "JVM probe native allocation missing")
    require(jvm_oracle.get("medianAllocatedBytes", 0) > 0, "JVM probe oracle allocation missing")
    scala_js = receipt.get("scalaJs", {})
    require(scala_js.get("runtime") == "captured-in-cross-platform-probe", "Scala.js runtime boundary weakened")
    require(scala_js.get("allocation") == "not-instrumented", "Scala.js allocation boundary weakened")
    scala_js_probe = probe.get("scalaJs", {})
    scala_js_native = scala_js_probe.get("nativeRound", {})
    scala_js_oracle = scala_js_probe.get("oracleRound", {})
    require(scala_js_probe.get("platform") == "scalajs-node", "Scala.js probe platform missing")
    require(scala_js_native.get("medianMillis", 0) > 0, "Scala.js probe native runtime missing")
    require(scala_js_oracle.get("medianMillis", 0) > 0, "Scala.js probe oracle runtime missing")
    memory = receipt.get("peakResidentMemory", {})
    require(memory.get("status") == "process-envelope-captured", "peak memory capture missing")
    require(memory.get("unit") == "bytes", "peak memory unit missing")
    require(memory.get("jvm", 0) > 0, "JVM peak memory envelope missing")
    require(memory.get("scalaJs", 0) > 0, "Scala.js peak memory envelope missing")
    require("process envelope" in memory.get("scope", ""), "peak memory scope not qualified")
    require(receipt.get("decision", {}).get("status") == "baseline-only", "performance receipt became promotional")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--receipt", type=Path, default=DEFAULT_RECEIPT)
    arguments = parser.parse_args()
    receipt_path = arguments.receipt if arguments.receipt.is_absolute() else ROOT / arguments.receipt
    validate(json.loads(receipt_path.read_text(encoding="utf-8")))
    print(f"B4 performance receipt boundary valid: {receipt_path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
