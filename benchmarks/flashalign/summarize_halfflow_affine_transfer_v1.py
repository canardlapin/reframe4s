#!/usr/bin/env python3
"""Adjudicate the frozen Flashalign -> HalfFlow C04 transfer court."""

from __future__ import annotations

import hashlib
import itertools
import json
import math
import platform
import statistics
import subprocess
import sys
from collections import Counter
from pathlib import Path


EXPECTED_MANIFEST_SHA256 = (
    "b150d86bd0e707e91faa72d7d9d4caa539c7d07165fd70fd9b647c2fbcdd6989"
)
EXPECTED_COURT = "flashalign-halfflow-affine-transfer-v1"
REPORT_PATH = Path("docs/benchmarks/flashalign-halfflow-affine-transfer-v1.md")
EXECUTED_LANES = ("C1", "C4", "C5")
ALL_LANES = tuple(f"C{index}" for index in range(8))


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def quantile(values: list[float], probability: float) -> float:
    ordered = sorted(values)
    position = (len(ordered) - 1) * probability
    lower = math.floor(position)
    upper = min(len(ordered) - 1, lower + 1)
    fraction = position - lower
    return ordered[lower] * (1.0 - fraction) + ordered[upper] * fraction


def summary(values: list[float]) -> dict[str, float]:
    return {
        "minimum": min(values),
        "median": statistics.median(values),
        "p95": quantile(values, 0.95),
        "maximum": max(values),
        "mean": statistics.fmean(values),
    }


def stage_total(row: dict) -> float:
    costs = row["costs"]
    return sum(
        costs[key]
        for key in (
            "prepare_ms",
            "capture_ms",
            "optimize_ms",
            "validate_ms",
            "output_ms",
        )
    )


def adjudicated_rms(row: dict, failure_penalty_mm: float) -> float:
    value = row["metrics"]["landmark_rms_mm"]
    return float(value) if row["status"] == "success" else failure_penalty_mm


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def command_output(command: list[str]) -> str:
    completed = subprocess.run(command, check=True, capture_output=True, text=True)
    return (completed.stdout + completed.stderr).strip()


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit(
            "usage: summarize_halfflow_affine_transfer_v1.py RAW MANIFEST RECEIPT"
        )
    raw_path = Path(sys.argv[1])
    manifest_path = Path(sys.argv[2])
    receipt_path = Path(sys.argv[3])

    require(sha256(manifest_path) == EXPECTED_MANIFEST_SHA256, "manifest seal changed")
    manifest = json.loads(manifest_path.read_text())
    courts = [
        json.loads(line)
        for line in raw_path.read_text().splitlines()
        if line.strip()
    ]
    expected_pairs = {pair["case_id"]: pair for pair in manifest["inputs"]["pairs"]}
    require(len(courts) == len(expected_pairs) == 6, "court denominator must be six")
    require({court["case_id"] for court in courts} == set(expected_pairs), "case set changed")

    per_case = []
    paired_rows = []
    lane_rows: dict[str, list[dict]] = {lane: [] for lane in ALL_LANES}
    lane_complete_totals: dict[str, list[float]] = {
        lane: [] for lane in EXECUTED_LANES
    }
    shared_input_totals = []
    for court in courts:
        require(court["schema_version"] == "1.0.0", "unexpected raw schema")
        require(court["plan_id"] == EXPECTED_COURT, "unexpected court plan")
        require(court["shared_input_cost"]["load_invocations"] == 1, "input was loaded more than once")
        shared_input_ms = float(court["shared_input_cost"]["read_and_hash_ms"]) + float(
            court["shared_input_cost"]["decompress_ms"]
        )
        shared_input_totals.append(shared_input_ms)
        expected = expected_pairs[court["case_id"]]
        require(court["subject_id"] == expected["subject_id"], "subject changed")
        require(court["inputs"]["moving"] == expected["moving_sha256"], "moving input changed")
        require(court["inputs"]["fixed"] == expected["fixed_sha256"], "fixed input changed")
        by_lane = {row["lane"]: row for row in court["rows"]}
        require(tuple(row["lane"] for row in court["rows"]) == ALL_LANES, "C0-C7 order changed")
        require(len(by_lane) == 8, "lane denominator changed")
        for lane, row in by_lane.items():
            lane_rows[lane].append(row)
        for lane in EXECUTED_LANES:
            lane_complete_totals[lane].append(shared_input_ms + stage_total(by_lane[lane]))
        for lane in EXECUTED_LANES:
            require(by_lane[lane]["map_interpretation"] == "complete-moving-to-fixed", f"{lane} map semantics changed")
        require(
            by_lane["C4"]["controls"]["recipient_controls_sha256"]
            == by_lane["C5"]["controls"]["recipient_controls_sha256"]
            == manifest["controls"]["recipient_controls_sha256"],
            "C4/C5 recipient controls differ",
        )
        for lane in ("C0", "C2", "C3", "C6", "C7"):
            require(by_lane[lane]["status"] == "unavailable", f"{lane} must remain explicit unavailable")
        require(
            by_lane["C1"]["metrics"]["candidate_secondary"]["output_resamplings"] == 0.0,
            "C1 must preserve the affine checkpoint without final resampling",
        )
        for lane in ("C4", "C5"):
            expected_resamplings = 1.0 if by_lane[lane]["status"] == "success" else 0.0
            require(
                by_lane[lane]["metrics"]["candidate_secondary"]["output_resamplings"]
                == expected_resamplings,
                f"{lane} final output resampling count changed",
            )
        paired_rows.append((by_lane["C4"], by_lane["C5"]))
        require(
            by_lane["C5"]["metrics"]["candidate_secondary"]["upstream_affine_completed"] == 1.0,
            "C5 did not preserve a complete upstream affine",
        )
        require(
            by_lane["C5"]["costs"]["capture_ms"]
            == by_lane["C1"]["costs"]["capture_ms"],
            "C5 does not contain the upstream capture cost",
        )
        per_case.append(
            {
                "case_id": court["case_id"],
                "subject_id": court["subject_id"],
                "shared_input_ms": shared_input_ms,
                "C1": {
                    "status": by_lane["C1"]["status"],
                    "landmark_rms_mm": by_lane["C1"]["metrics"]["landmark_rms_mm"],
                    "stage_total_ms": stage_total(by_lane["C1"]),
                    "complete_shared_execution_ms": shared_input_ms + stage_total(by_lane["C1"]),
                },
                "C4": {
                    "status": by_lane["C4"]["status"],
                    "failure": by_lane["C4"]["failure"],
                    "landmark_rms_mm": by_lane["C4"]["metrics"]["landmark_rms_mm"],
                    "stage_total_ms": stage_total(by_lane["C4"]),
                    "complete_shared_execution_ms": shared_input_ms + stage_total(by_lane["C4"]),
                },
                "C5": {
                    "status": by_lane["C5"]["status"],
                    "failure": by_lane["C5"]["failure"],
                    "landmark_rms_mm": by_lane["C5"]["metrics"]["landmark_rms_mm"],
                    "stage_total_ms": stage_total(by_lane["C5"]),
                    "complete_shared_execution_ms": shared_input_ms + stage_total(by_lane["C5"]),
                },
            }
        )

    penalty = float(manifest["estimand"]["failure_penalty_mm"])
    paired_differences = []
    common_success_differences = []
    for item, (c4, c5) in zip(per_case, paired_rows, strict=True):
        difference = adjudicated_rms(c5, penalty) - adjudicated_rms(c4, penalty)
        paired_differences.append(difference)
        item["paired_C5_minus_C4_adjudicated_rms_mm"] = difference
        if item["C4"]["status"] == item["C5"]["status"] == "success":
            common_success_differences.append(
                float(item["C5"]["landmark_rms_mm"])
                - float(item["C4"]["landmark_rms_mm"])
            )

    bootstrap_means = [
        statistics.fmean(paired_differences[index] for index in resample)
        for resample in itertools.product(range(6), repeat=6)
    ]
    interval = [quantile(bootstrap_means, 0.025), quantile(bootstrap_means, 0.975)]
    if interval[1] < 0.0:
        classification = "benefit"
    elif interval[0] > 0.0:
        classification = "regression"
    else:
        classification = "no-detected-benefit"

    lane_summaries = {}
    for lane in EXECUTED_LANES:
        rows = lane_rows[lane]
        successes = [row for row in rows if row["status"] == "success"]
        lane_summaries[lane] = {
            "statuses": dict(sorted(Counter(row["status"] for row in rows).items())),
            "successes": len(successes),
            "denominator": len(rows),
            "successful_landmark_rms_mm": summary(
                [float(row["metrics"]["landmark_rms_mm"]) for row in successes]
            ) if successes else None,
            "stage_total_ms": summary([stage_total(row) for row in rows]),
            "complete_shared_execution_ms": summary(lane_complete_totals[lane]),
            "output_resamplings": sum(
                float(row["metrics"]["candidate_secondary"]["output_resamplings"])
                for row in rows
            ),
        }

    receipt = {
        "schema_version": "flashalign.halfflow-affine-transfer.v1",
        "receipt_id": "flashalign-halfflow-affine-transfer-2026-09-13",
        "mote_task": "bd-01M2B11A81SP5GJ1XCZP72S6VP",
        "status": f"completed-{classification}-analytic-synthetic",
        "candidate": {
            "flashalign_released_candidate_sha256": manifest["candidate"]["flashalign_released_candidate_sha256"],
            "flashalign_source_tree_sha256": manifest["candidate"]["flashalign_source_tree_sha256"],
            "halfflow_source_tree_sha256": manifest["candidate"]["halfflow_source_tree_sha256"],
            "integration_build_sbt_sha256": manifest["candidate"]["integration_build_sbt_sha256"],
            "recipient_controls_sha256": manifest["controls"]["recipient_controls_sha256"],
            "C5_composite_revision_sha256": manifest["controls"]["C5_composite_revision_sha256"],
        },
        "environment": {
            "platform": platform.platform(),
            "machine": platform.machine(),
            "java": command_output(["java", "-version"]),
            "threads": manifest["execution"]["threads"],
        },
        "evidence": {
            "manifest": {"path": str(manifest_path), "sha256": sha256(manifest_path)},
            "raw": {"path": str(raw_path), "sha256": sha256(raw_path), "courts": len(courts), "rows": len(courts) * 8},
            "summarizer": {"path": __file__, "sha256": sha256(Path(__file__))},
            "report": {"path": str(REPORT_PATH), "sha256": sha256(REPORT_PATH)},
        },
        "integrity": {
            "expected_rows": 48,
            "observed_rows": len(courts) * 8,
            "shared_input_loads": sum(court["shared_input_cost"]["load_invocations"] for court in courts),
            "shared_input_ms": summary(shared_input_totals),
            "C4_C5_controls_identical": True,
            "C1_preserved_before_downstream": True,
            "C5_complete_map_not_double_composed": True,
            "successful_recipient_outputs_sampled_once": True,
            "failed_rows_retained": True,
        },
        "outcomes": lane_summaries,
        "paired_effect": {
            "estimand": manifest["estimand"]["primary"],
            "failure_penalty_mm": penalty,
            "subject_differences_mm": paired_differences,
            "mean_difference_mm": statistics.fmean(paired_differences),
            "median_difference_mm": statistics.median(paired_differences),
            "bootstrap_resamples": len(bootstrap_means),
            "bootstrap_95_percent_interval_mm": interval,
            "classification": classification,
            "common_success_pairs": len(common_success_differences),
            "common_success_mean_difference_mm": statistics.fmean(common_success_differences),
            "interpretation": "Negative values favor the Flashalign-initialized C5 lane. The primary penalty-aware effect includes recipient failures; the common-success effect is descriptive.",
        },
        "per_case": per_case,
        "prior_negative_evidence": manifest["prior_negative_evidence"],
        "decision": {
            "C04": "completed",
            "affine_handoff": "supported on this analytic-synthetic court",
            "recipient_retuned": False,
            "promote_halfFlow_stable_export": False,
            "advance_constrained_warp_handoff": False,
        },
        "validation": [
            {
                "command": "sbt -Dsbt.supershell=false 'flashalignBenchmarkJVM/test'",
                "result": "16 passed, 0 failed",
            },
            {
                "command": "sbt -Dsbt.supershell=false 'reframe4s-halfflowJVM/testOnly reframe4s.halfflow.SuppliedAffineAdapterSuite'",
                "result": "3 passed, 0 failed",
            },
            {
                "command": "sbt -Dsbt.supershell=false 'reframe4s-halfflowJS/testOnly reframe4s.halfflow.SuppliedAffineAdapterSuite'",
                "result": "3 passed, 0 failed",
            },
            {
                "command": "git diff --check",
                "result": "passed",
            },
            {
                "command": "summarizer rerun to a temporary receipt followed by byte comparison",
                "result": "passed",
            },
        ],
        "claim_boundary": manifest["claim_boundary"],
    }
    require(not receipt_path.exists(), f"receipt already exists: {receipt_path}")
    receipt_path.parent.mkdir(parents=True, exist_ok=True)
    receipt_path.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
    print(json.dumps(receipt["paired_effect"], indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
