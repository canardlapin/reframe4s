#!/usr/bin/env python3
"""Summarize the sealed Flashalign automatic release court's complete costs."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import pathlib
import statistics
from collections import Counter
from typing import Any


EXPECTED_RAW_SHA256 = "40672c581ba9d3412be8f59b133d3e4f225a62a018ec98bfb6aca143fca6ac18"
EXPECTED_CANDIDATE = "50ec8257d6f531aec793e11f568627b6b95813b80be8cc2bd8ad748c47ab60d8"
EXPECTED_ROWS = 48
STAGES = (
    "read",
    "decompress",
    "prepare",
    "capture",
    "optimize",
    "validate",
    "output",
    "total",
)
WORK = (
    "unique_interpolations",
    "gradient_evaluations",
    "linearizations",
    "trial_evaluations",
    "rejected_trials",
    "early_rejections",
    "curvature_products",
)


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def nearest_rank(values: list[float], probability: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(probability * len(ordered)) - 1)]


def distribution(values: list[float]) -> dict[str, float]:
    return {
        "median": statistics.median(values),
        "p95": nearest_rank(values, 0.95),
        "maximum": max(values),
    }


def group_summary(rows: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "rows": len(rows),
        "timing_ms": {
            stage: distribution([float(row["timing_ms"][stage]) for row in rows])
            for stage in STAGES
        },
        "work": {
            name: distribution([float(row["work"][name]) for row in rows])
            for name in WORK
        },
    }


def load_rows(path: pathlib.Path) -> list[dict[str, Any]]:
    if sha256(path) != EXPECTED_RAW_SHA256:
        raise ValueError(f"raw evidence hash mismatch for {path}")
    rows = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
    if len(rows) != EXPECTED_ROWS:
        raise ValueError(f"expected {EXPECTED_ROWS} rows, got {len(rows)}")
    case_ids = [str(row["case_id"]) for row in rows]
    if len(set(case_ids)) != EXPECTED_ROWS:
        raise ValueError("case IDs are not unique")
    for row in rows:
        if row["schema_version"] != "1.1.0":
            raise ValueError(f"unexpected row schema for {row['case_id']}")
        if row["method"]["revision"] != EXPECTED_CANDIDATE:
            raise ValueError(f"candidate mismatch for {row['case_id']}")
        for stage in STAGES:
            value = float(row["timing_ms"][stage])
            if not math.isfinite(value) or value < 0.0:
                raise ValueError(f"invalid {stage} timing for {row['case_id']}")
    return rows


def summarize(raw: pathlib.Path) -> dict[str, Any]:
    rows = load_rows(raw)
    by_model = {
        model: [row for row in rows if row["method"]["model"] == model]
        for model in ("rigid", "affine")
    }
    ordinary = {
        model: [
            row
            for row in by_model[model]
            if row["cohort"] == "ordinary-same-subject"
        ]
        for model in ("rigid", "affine")
    }
    targets = {"rigid": 1000.0, "affine": 2000.0}
    target_results = {}
    for model in ("rigid", "affine"):
        values = [float(row["timing_ms"]["total"]) for row in ordinary[model]]
        summary = distribution(values)
        limit = targets[model]
        target_results[model] = {
            "cohort": "ordinary-same-subject",
            "rows": len(values),
            "desirable_total_latency_limit_ms": limit,
            "rows_below_limit": sum(value < limit for value in values),
            "median_total_ms": summary["median"],
            "p95_total_ms": summary["p95"],
            "median_below_limit": summary["median"] < limit,
            "p95_below_limit": summary["p95"] < limit,
        }
    output_times = [float(row["timing_ms"]["output"]) for row in rows]
    output_voxels = [float(row["metrics"]["candidate_output_voxels"]) for row in rows]
    nonfinite_voxels = [
        float(row["metrics"]["candidate_output_nonfinite_voxels"])
        for row in rows
    ]
    latency_passed = all(
        result["median_below_limit"] and result["p95_below_limit"]
        for result in target_results.values()
    )
    status_counts = Counter(str(row["outcome"]["status"]) for row in rows)
    return {
        "schema_version": "flashalign.linear-automatic-release-cost.v2",
        "receipt_id": "flashalign-linear-automatic-release-cost-v2",
        "status": (
            "passed" if latency_passed else "measured-latency-targets-not-met"
        ),
        "candidate_source_tree_sha256": EXPECTED_CANDIDATE,
        "raw": {
            "path": str(raw),
            "sha256": EXPECTED_RAW_SHA256,
            "rows": len(rows),
        },
        "environment": rows[0]["environment"],
        "outcomes": dict(sorted(status_counts.items())),
        "complete_cost_evidence": {
            "passed": True,
            "all_rows_have_positive_output_time": all(value > 0.0 for value in output_times),
            "output_timing_ms": distribution(output_times),
            "materialized_output_voxels": distribution(output_voxels),
            "nonfinite_output_voxels": distribution(nonfinite_voxels),
            "scope": "input read and decompression, public-plan preparation, structural capture, all refinement attempts, final audit, one materialized linear output resampling from the original moving image, and total wall time",
        },
        "all_rows_by_model": {
            model: group_summary(by_model[model]) for model in ("rigid", "affine")
        },
        "ordinary_same_subject_by_model": {
            model: group_summary(ordinary[model]) for model in ("rigid", "affine")
        },
        "desirable_latency_targets": target_results,
        "latency_targets_passed": latency_passed,
        "interpretation": "The fixed-spectrum and shared-inverse optimization materially reduced structural-capture cost, but the automatic public candidate still missed the ordinary same-subject median and p95 desktop latency limits. Structural capture remains the dominant slow-row stage.",
        "claim_boundary": "This is a single-host, single-thread descriptive cost receipt for the sealed analytic-synthetic court. It does not establish cross-host performance, acquired-MRI behavior, or an accuracy claim.",
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--raw",
        type=pathlib.Path,
        default=pathlib.Path(
            "benchmarks/flashalign/raw/linear-automatic-release-confirmation-2026-09-13.jsonl"
        ),
    )
    parser.add_argument(
        "--output",
        type=pathlib.Path,
        default=pathlib.Path(
            "benchmarks/flashalign/receipts/linear-automatic-release-cost-v2.json"
        ),
    )
    args = parser.parse_args()
    receipt = summarize(args.raw)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
    print(
        json.dumps(
            {
                "output": str(args.output),
                "sha256": sha256(args.output),
                "status": receipt["status"],
            },
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
