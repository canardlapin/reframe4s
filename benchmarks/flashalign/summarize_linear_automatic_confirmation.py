#!/usr/bin/env python3
"""Adjudicate the sealed Flashalign automatic linear confirmation court."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import pathlib
from collections import Counter, defaultdict


FAILURE_PENALTY_MM = 100.0
EXACT_LIMITS_MM = {
    "exact-core": 0.1,
    "capture-range": 0.25,
    "partial-slab": 0.25,
}
RAW_COHORT = {
    "exact-core": "ordinary-same-subject",
    "capture-range": "large-initialization-error",
    "partial-slab": "partial-slab",
    "nonlinear-contrast": "dropout-or-low-signal",
}


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def percentile(values: list[float], probability: float) -> float:
    if not values:
        return math.nan
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, math.ceil(probability * len(ordered)) - 1))
    return ordered[index]


def nonnegative_work(row: dict[str, object]) -> bool:
    return all(isinstance(value, int) and value >= 0 for value in row["work"].values())


def valid_timing(row: dict[str, object]) -> bool:
    return all(
        isinstance(value, (int, float)) and math.isfinite(value) and value >= 0.0
        for value in row["timing_ms"].values()
    )


def run(args: argparse.Namespace) -> None:
    manifest = json.loads(args.manifest.read_text())
    descriptor_path = pathlib.Path(manifest["fixtures"]["descriptor"]["path"])
    descriptor = json.loads(descriptor_path.read_text())
    raw_rows = [
        json.loads(line)
        for line in args.raw.read_text().splitlines()
        if line.strip()
    ]
    expected = {case["case_id"]: case for case in descriptor["cases"]}
    counts = Counter(row["case_id"] for row in raw_rows)
    missing = sorted(set(expected) - set(counts))
    unexpected = sorted(set(counts) - set(expected))
    duplicates = sorted(case_id for case_id, count in counts.items() if count != 1)
    violations = []

    if sha256(descriptor_path) != manifest["fixtures"]["descriptor"]["sha256"]:
        violations.append("descriptor hash mismatch")
    if sha256(pathlib.Path(manifest["fixtures"]["case_table"]["path"])) != manifest["fixtures"]["case_table"]["sha256"]:
        violations.append("case-table hash mismatch")
    if sha256(pathlib.Path(manifest["runner"]["path"])) != manifest["runner"]["sha256"]:
        violations.append("runner hash mismatch")
    if manifest["candidate_executed_before_seal"]:
        violations.append("manifest records candidate execution before seal")

    by_group: dict[tuple[str, str], list[dict[str, object]]] = defaultdict(list)
    for row in raw_rows:
        case = expected.get(row["case_id"])
        if case is None:
            continue
        prefix = row["case_id"]
        if row["schema_version"] != "1.1.0":
            violations.append(f"{prefix}: unexpected schema version")
        if row["cohort"] != RAW_COHORT[case["cohort"]]:
            violations.append(f"{prefix}: cohort mismatch")
        if row["method"]["model"] != case["model"]:
            violations.append(f"{prefix}: model mismatch")
        if row["method"]["revision"] != manifest["candidate"]["flashalign_source_tree_sha256"]:
            violations.append(f"{prefix}: candidate revision mismatch")
        if row["inputs"]["moving"] != case["moving"]["sha256"] or row["inputs"]["fixed"] != case["fixed"]["sha256"]:
            violations.append(f"{prefix}: input hash mismatch")
        if row["initialization"]["id"] != "automatic-coherent-header-identity-v1":
            violations.append(f"{prefix}: initialization mismatch")
        if not nonnegative_work(row):
            violations.append(f"{prefix}: invalid work counts")
        if not valid_timing(row):
            violations.append(f"{prefix}: invalid timing")
        success = row["outcome"]["status"] == "success"
        rms = row["metrics"]["landmark_rms_mm"]
        if success:
            if not isinstance(rms, (int, float)) or not math.isfinite(rms):
                violations.append(f"{prefix}: success lacks finite landmark RMS")
        else:
            if row["outcome"]["failure_detail"] is None:
                violations.append(f"{prefix}: failure lacks detail")
            if row["work"]["linearizations"] > 0 and row["last_checkpoint"] is None:
                violations.append(f"{prefix}: post-linearization failure lacks checkpoint")
        by_group[case["cohort"], case["model"]].append(row)

    summaries = []
    gates = []
    for cohort in descriptor["cohorts"]:
        for model in descriptor["models"]:
            rows = by_group[cohort, model]
            errors = [
                float(row["metrics"]["landmark_rms_mm"])
                if row["outcome"]["status"] == "success"
                and row["metrics"]["landmark_rms_mm"] is not None
                else FAILURE_PENALTY_MM
                for row in rows
            ]
            summary = {
                "cohort": cohort,
                "model": model,
                "denominator": len(rows),
                "successes": sum(row["outcome"]["status"] == "success" for row in rows),
                "median_adjudicated_rms_mm": percentile(errors, 0.5),
                "p95_adjudicated_rms_mm": percentile(errors, 0.95),
                "maximum_adjudicated_rms_mm": max(errors) if errors else None,
                "statuses": dict(Counter(row["outcome"]["status"] for row in rows)),
            }
            summaries.append(summary)
            if cohort in EXACT_LIMITS_MM:
                limit = EXACT_LIMITS_MM[cohort]
                passed = len(rows) == len(descriptor["subjects"]) and all(
                    row["outcome"]["status"] == "success"
                    and row["metrics"]["landmark_rms_mm"] is not None
                    and row["metrics"]["landmark_rms_mm"] <= limit
                    for row in rows
                )
                gates.append(
                    {
                        "cohort": cohort,
                        "model": model,
                        "required_rows": len(descriptor["subjects"]),
                        "maximum_landmark_rms_mm": limit,
                        "passed": passed,
                    }
                )

    complete = (
        len(raw_rows) == len(expected)
        and not missing
        and not unexpected
        and not duplicates
        and not violations
    )
    accuracy_passed = complete and len(gates) == 6 and all(gate["passed"] for gate in gates)
    receipt = {
        "schema_version": "flashalign.linear-automatic-confirmation.v1",
        "receipt_id": "flashalign-linear-automatic-confirmation-execution-v1",
        "status": "passed" if accuracy_passed else "failed",
        "seal": {"path": str(args.manifest), "sha256": sha256(args.manifest)},
        "raw": {"path": str(args.raw), "sha256": sha256(args.raw), "rows": len(raw_rows)},
        "candidate": manifest["candidate"],
        "adjudication": {
            "failure_penalty_mm": FAILURE_PENALTY_MM,
            "exact_limits_mm": EXACT_LIMITS_MM,
            "nonlinear_contrast": "descriptive approximation distribution; excluded from exact-recovery gates",
        },
        "completeness": {
            "expected_rows": len(expected),
            "observed_rows": len(raw_rows),
            "missing": missing,
            "unexpected": unexpected,
            "duplicates": duplicates,
            "violations": violations,
            "passed": complete,
        },
        "summaries": summaries,
        "exact_recovery_gates": gates,
        "accuracy_passed": accuracy_passed,
        "claim_boundary": "This adjudicates the sealed analytic-synthetic automatic linear court. It does not establish acquired-MRI, anatomical, clinical, cost, publication, or cross-method superiority claims.",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
    print(json.dumps({"output": str(args.output), "sha256": sha256(args.output), "status": receipt["status"]}))


def self_test() -> None:
    assert percentile([4.0, 1.0, 3.0, 2.0], 0.5) == 2.0
    assert percentile([4.0, 1.0, 3.0, 2.0], 0.95) == 4.0
    assert set(EXACT_LIMITS_MM) == {"exact-core", "capture-range", "partial-slab"}
    assert set(RAW_COHORT.values()) == {
        "ordinary-same-subject",
        "large-initialization-error",
        "partial-slab",
        "dropout-or-low-signal",
    }
    print("linear automatic confirmation summarizer self-test passed")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--manifest", type=pathlib.Path)
    parser.add_argument("--raw", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        if args.manifest is None or args.raw is None or args.output is None:
            parser.error("--manifest, --raw and --output are required")
        run(args)


if __name__ == "__main__":
    main()
