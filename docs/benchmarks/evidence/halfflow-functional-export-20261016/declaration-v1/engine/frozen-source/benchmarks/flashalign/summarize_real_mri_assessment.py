#!/usr/bin/env python3
"""Summarize every predeclared MRI pair without dropping failed fits."""
import argparse
import csv
from datetime import datetime, timezone
import json
from pathlib import Path

from real_image_court import sha, write_json


def read(path):
    return json.loads(path.read_text()) if path.exists() else None


def improvement(baseline, candidate):
    assert baseline["input_sha256"] == candidate["input_sha256"], "different input images"
    assert baseline["anatomical_labels"] == candidate["anatomical_labels"], "different label identities"
    rows = {}
    for direction in ("fixed-to-moving", "moving-to-fixed"):
        a, b = (r["directions"][direction]["anatomy"] for r in (baseline, candidate))
        assert [x["label"] for x in a["labels"]] == [x["label"] for x in b["labels"]], "different label sets"
        complete = a["label_count"] > 0 and a["missing_labels"] == b["missing_labels"] == 0
        dice_gain = b["macro_dice"] - a["macro_dice"]
        hd_gain = a["median_hd95_mm"] - b["median_hd95_mm"] if complete else None
        rows[direction] = {"dice_gain": dice_gain, "hd95_reduction_mm": hd_gain,
                           "pass": bool(complete and dice_gain > 0 and hd_gain > 0)}
    return {"pass": all(r["pass"] for r in rows.values()), "directions": rows}


def compact(report):
    return {direction: {
        "macro_dice": row["anatomy"]["macro_dice"], "median_hd95_mm": row["anatomy"]["median_hd95_mm"],
        "label_count": row["anatomy"]["label_count"], "missing_labels": row["anatomy"]["missing_labels"],
        **{key: row[key] for key in ("ncc", "grid_jacobian_min", "grid_jacobian_nonpositive",
            "brain_jacobian_min", "brain_sampling_coverage", "brain_roundtrip_coverage",
            "brain_roundtrip_max_mm", "reload_max_abs", "geometry_pass")}
    } for direction, row in report["directions"].items()}


def summarize(study):
    protocol, source = read(study / "protocol.json"), read(study / "source.json")
    assert read(study / "fit-process-summary.json")["completed_commands"] == 16, "incomplete fit denominator"
    command_evidence = {}
    for path in sorted(study.glob("*/commands/*.json")):
        value = read(path)
        assert value["source_sha256"] == source["sha256"], "mixed fitted source candidates"
        assert sha(path.with_suffix(".log")) == value["log_sha256"], "modified command log"
        command_evidence[str(path.relative_to(study))] = sha(path)
    rows = []
    for pair in protocol["dataset"]["pairs"]:
        directory = study / pair["id"]
        inputs = {name: sha(directory / "data" / (name + ".nii.gz"))
                  for name in ("fixed", "moving", "fixed-mask", "moving-mask")}

        def load_report(lane, mapping):
            path = directory / lane / (mapping + "-evaluation.json")
            report = read(path)
            if report:
                assert report["input_sha256"] == inputs, "report/input mismatch"
                for name in ("moving", "fixed"):
                    assert report["anatomical_labels"][name]["sha256"] == sha(
                        study / "inputs" / pair[name] / "labels.DKT31.manual.nii.gz"), "report/label mismatch"
                if mapping.startswith("halfflow"):
                    expected_mode = "identity-fine-taper-boundary" if lane == "halfflow-identity" else "fine-taper-boundary"
                    assert report["recipe"] == {"mode": expected_mode,
                        "plan_sha256": protocol["halfflow_recipe"]["plan_sha256"]}, "different HalfFlow recipe"
            return report

        baseline = load_report("flashalign-default", "identity")
        assert baseline is not None, "missing identity baseline evaluation"
        affine_long = load_report("flashalign-halfflow", "affine")
        rows.append({"pair": pair["id"], "method": "identity", "status": "baseline",
                     "directions": compact(baseline)})
        for method, lane, mapping in (
            ("flashalign-default", "flashalign-default", "affine"),
            ("flashalign-long", "flashalign-halfflow", "affine"),
            ("halfflow-identity", "halfflow-identity", "halfflow"),
            ("flashalign-halfflow", "flashalign-halfflow", "halfflow")):
            report = load_report(lane, mapping)
            command = read(directory / "commands" / (lane + ".json"))
            accepted_output = report is not None
            if mapping == "halfflow" and report is None:
                report = load_report(lane, "halfflow-rejected")
            # A failed nonlinear stage does not erase a successful affine stage.
            fit_completed = bool(accepted_output and (mapping == "affine" or command["exit_code"] == 0))
            if mapping == "halfflow" and report:
                fit_completed = fit_completed and report["native_export_accepted"]
            reference = affine_long if method == "flashalign-halfflow" else baseline
            gain = improvement(reference, report) if reference and report else None
            geometry = bool(report and report["geometry_pass"])
            anatomy = bool(gain and gain["pass"])
            status = ("fit-failed" if not fit_completed else "geometry-failed" if not geometry
                      else "anatomy-failed" if not anatomy else "passed-declared-checks")
            failure_path = directory / lane / "failure.txt"
            rows.append({"pair": pair["id"], "method": method, "status": status,
                "fit_completed": fit_completed, "geometry_pass": geometry, "anatomy_pass": anatomy,
                "passes_declared_checks": fit_completed and geometry and anatomy,
                "failure": failure_path.read_text().splitlines()[0] if not fit_completed and failure_path.exists() else None,
                "runner_process_seconds": command["seconds"],
                "report_kind": None if not report else report["lane"],
                "directions": compact(report) if report else None, "anatomical_improvement": gain})
        ants = read(directory / "ants/anatomy-evaluation.json")
        ants_command = read(directory / "commands/ants.json")
        settings = read(directory / "ants/settings.json")
        if settings:
            assert settings["fixed_sha256"] == inputs["fixed"] and settings["moving_sha256"] == inputs["moving"]
        if ants:
            assert settings is not None and ants_command["exit_code"] == 0, "reference report without successful fit"
        rows.append({"pair": pair["id"], "method": "ants", "status": "reference-completed" if ants else "reference-failed",
            "runner_process_seconds": ants_command["seconds"], "geometry_pass": None,
            "directions": {row["target"] + "-to-" + row["source"]: {k: row[k] for k in
                ("macro_dice", "median_hd95_mm", "label_count", "missing_labels", "ncc")}
                for row in ants.values()} if ants else None})
        reference_directions = rows[-1]["directions"]
        if reference_directions:
            for row in rows:
                if row["pair"] != pair["id"] or row["method"] == "ants" or not row["directions"]:
                    continue
                row["ants_gap"] = {}
                for direction, values in row["directions"].items():
                    reference = reference_directions[direction]
                    distances = values["median_hd95_mm"], reference["median_hd95_mm"]
                    row["ants_gap"][direction] = {
                        "ants_minus_method_dice": reference["macro_dice"] - values["macro_dice"],
                        "method_minus_ants_hd95_mm": None if None in distances else distances[0] - distances[1]}
    counts = {}
    for method in ("flashalign-default", "flashalign-long", "halfflow-identity", "flashalign-halfflow"):
        cohort = [r for r in rows if r["method"] == method]
        counts[method] = {"pairs": len(cohort), **{key: sum(row[key] for row in cohort) for key in
            ("fit_completed", "geometry_pass", "anatomy_pass", "passes_declared_checks")}}
    return {"schema_version": 1, "recorded_at": datetime.now(timezone.utc).isoformat(),
        "source_sha256": source["sha256"], "base_commit": source["base_commit"],
        "protocol_sha256": sha(study / "protocol.json"), "analysis_source_sha256": sha(Path(__file__)),
        "counts": counts, "rows": rows, "command_receipt_hashes": command_evidence,
        "criterion_limit": "Passing the declared checks establishes valid geometry and relative anatomical improvement, with no minimum absolute Dice or maximum HD95. It does not establish useful absolute alignment.",
        "scope": protocol["scope"], "reference_limit": "ANTs is an anatomical reference; its geometry was not independently audited in this assessment.",
        "timing_limit": "Concurrent execution with at most two fitting processes; combined and extended-affine rows share one pipeline process time. This is not a matched-runtime benchmark."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--study", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = summarize(args.study)
    write_json(args.output, result)
    with args.output.with_suffix(".csv").open("w", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(["pair", "method", "status", "forward_dice", "forward_hd95_mm", "reverse_dice", "reverse_hd95_mm"])
        for row in result["rows"]:
            directions = row.get("directions") or {}
            forward, reverse = (directions.get(k, {}) for k in ("fixed-to-moving", "moving-to-fixed"))
            writer.writerow([row["pair"], row["method"], row["status"], forward.get("macro_dice"),
                forward.get("median_hd95_mm"), reverse.get("macro_dice"), reverse.get("median_hd95_mm")])
    print(json.dumps(result["counts"], indent=2))


if __name__ == "__main__":
    main()
