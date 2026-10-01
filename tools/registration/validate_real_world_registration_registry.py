#!/usr/bin/env python3
"""Validate and render the real-world registration evidence registry.

The registry is the canonical inventory. Existing receipts remain the sources
of truth for observations; JSON-pointer assertions prevent the inventory from
silently drifting away from those receipts.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_REGISTRY = (
    ROOT / "docs" / "benchmarks" / "manifests" / "real-world-registration-v1.json"
)
SCHEMA = "reframe4s-real-world-registration-registry-v1"
DATA_CLASSES = {"analytic-synthetic", "acquired"}
EVIDENCE_CLASSES = {
    "analytic-engineering",
    "acquired-smoke",
    "acquired-diagnostic",
    "acquired-qualification",
}
RUN_STATUSES = {"completed", "blocked-before-execution"}
DECISIONS = {"pass", "fail", "descriptive", "blocked"}
VISUAL_QA_MANIFEST_SCHEMA = "reframe4s-real-world-registration-visual-qa-manifest-v1"
VISUAL_QA_RECEIPT_SCHEMA = "reframe4s-real-world-registration-visual-qa-receipt-v1"
VISUAL_QA_REVIEW_SCHEMA = "reframe4s-real-world-registration-visual-qa-review-v1"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def json_pointer(document: Any, pointer: str) -> Any:
    require(pointer.startswith("/"), f"JSON pointer must start with '/': {pointer}")
    current = document
    for raw_token in pointer[1:].split("/"):
        token = raw_token.replace("~1", "/").replace("~0", "~")
        if isinstance(current, list):
            require(token.isdigit(), f"list pointer token is not an index: {pointer}")
            index = int(token)
            require(index < len(current), f"list pointer is out of range: {pointer}")
            current = current[index]
        else:
            require(isinstance(current, dict), f"pointer crosses a scalar: {pointer}")
            require(token in current, f"pointer is absent: {pointer}")
            current = current[token]
    return current


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def validate_hashed_repository_artifact(reference: dict[str, Any], label: str) -> None:
    raw_path = reference.get("path")
    require(isinstance(raw_path, str) and raw_path, f"{label}: path missing")
    require(not Path(raw_path).is_absolute(), f"{label}: path must be relative")
    path = ROOT / raw_path
    require(path.is_file(), f"{label}: artifact is absent: {raw_path}")
    if "bytes" in reference:
        require(path.stat().st_size == reference["bytes"], f"{label}: byte size changed")
    expected_sha = reference.get("sha256")
    require(isinstance(expected_sha, str) and len(expected_sha) == 64, f"{label}: SHA-256 missing")
    require(file_sha256(path) == expected_sha, f"{label}: SHA-256 changed: {raw_path}")


def validate_visual_qa(registry: dict[str, Any]) -> None:
    """Keep method ownership separate from fixture storage and comparator provenance."""
    court_owners = {court["id"]: court["owner"] for court in registry["courts"]}
    bundles = (
        ("visualQa", "reframe4s-methods", "reframe4s", "HalfFlow", {
            "basinbridge-public-t1-to-mni": ["sub-1002-to-MNI152Lin"],
            "basinbridge-sub18-to-mni": ["sub18-to-MNI2009cAsym"],
        }, {
            "directReframeCases": 2,
            "directReframeMethodOutputsRendered": 0,
            "directReframeMissingOrRejectedOutputsRetained": 2,
            "detailedPlates": 2,
            "contactSheets": 0,
        }, "benchmarks/registration/visual-qa/v1"),
        ("externalVisualQa", "external-hodgeflow-reference", "external-hodgeflow", "HodgeFlow", {
            "hodgeflow-openneuro-ds000102-t1-to-mni": [f"ds000102_sub-{i:02d}" for i in range(1, 7)],
            "hodgeflow-ibsr18-labeled-t1": [f"IBSR_{i:02d}_to_IBSR_{i+1:02d}" for i in range(1, 18, 2)],
        }, {
            "externalHodgeFlowCases": 15,
            "externalHodgeFlowMethodOutputsRendered": 9,
            "externalHodgeFlowMetricRowsWithoutLocalMethodOutput": 6,
            "detailedPlates": 15,
            "contactSheets": 2,
        }, "benchmarks/registration/external-references/hodgeflow/visual-qa/v1"),
    )
    for key, scope, owner, method, expected_cases, expected_coverage, output_root in bundles:
        qa = registry.get(key, {})
        expected_status = (
            "no-reviewable-final-method-outputs" if key == "visualQa"
            else "reviewed-with-retained-artifact-gaps"
        )
        require(qa.get("status") == expected_status, f"{key}: status drifted")
        require(qa.get("scope") == scope, f"{key}: scope drifted")
        for field in ("manifest", "receipt", "review", "report"):
            raw_path = qa.get(field)
            require(isinstance(raw_path, str) and raw_path, f"{key}: {field} path missing")
            require(not Path(raw_path).is_absolute(), f"{key}: {field} path must be relative")
            require((ROOT / raw_path).is_file(), f"{key}: {field} is absent: {raw_path}")

        manifest = json.loads((ROOT / qa["manifest"]).read_text(encoding="utf-8"))
        receipt = json.loads((ROOT / qa["receipt"]).read_text(encoding="utf-8"))
        review = json.loads((ROOT / qa["review"]).read_text(encoding="utf-8"))
        for document, schema in (
            (manifest, VISUAL_QA_MANIFEST_SCHEMA),
            (receipt, VISUAL_QA_RECEIPT_SCHEMA),
            (review, VISUAL_QA_REVIEW_SCHEMA),
        ):
            require(document.get("schema") == schema, f"{key}: schema drifted")
            require(document.get("scope") == scope, f"{key}: document ownership scope drifted")
        for field in ("receipt", "review", "report"):
            require(manifest.get(field) == qa[field], f"{key}: {field} path disagrees")
        require(manifest.get("outputRoot") == output_root, f"{key}: output root crosses ownership boundary")
        require(qa["receipt"] == output_root + "/receipt.json", f"{key}: receipt outside bundle")
        require(qa["review"] == output_root + "/review.json", f"{key}: review outside bundle")
        require(receipt.get("renderingContract") == manifest.get("contract"), f"{key}: display contract drifted")
        require(manifest.get("contract", {}).get("methodOwnership"), f"{key}: method ownership contract missing")
        specs = manifest.get("courts", [])
        require(len(specs) == len(expected_cases), f"{key}: manifest court count drifted")
        require({spec.get("courtId") for spec in specs} == set(expected_cases), f"{key}: manifest includes wrong method courts")
        for spec in specs:
            court_id = spec["courtId"]
            require(spec.get("methodOwner") == owner and spec.get("methodId") == method, f"{key}: manifest method ownership drifted")
            require(court_owners.get(court_id, "").startswith("reframe4s") == (owner == "reframe4s"), f"{key}: registry method ownership disagrees")
            declared = spec.get("caseIds", spec.get("pairIds", [spec.get("caseId")]))
            require(declared == expected_cases[court_id], f"{key}: manifest case denominator drifted")

        coverage = receipt.get("coverage", {})
        require(coverage == expected_coverage, f"{key}: coverage changed or mixed method owners")
        for field, expected in expected_coverage.items():
            if field in qa:
                require(qa[field] == expected, f"{key}: registry coverage drifted: {field}")
        wrong_prefix = "externalHodgeFlow" if key == "visualQa" else "directReframe"
        require(not any(field.startswith(wrong_prefix) for field in qa), f"{key}: registry mixes method counts")
        cases = receipt.get("cases", [])
        expected_pairs = {(court, case) for court, ids in expected_cases.items() for case in ids}
        require(len(cases) == len(expected_pairs), f"{key}: receipt case count drifted")
        require({(case.get("courtId"), case.get("caseId")) for case in cases} == expected_pairs, f"{key}: receipt case ownership drifted")

        require(receipt["manifest"].get("path") == qa["manifest"], f"{key}: receipt names another manifest")
        require(receipt["generator"].get("path") == "tools/registration/generate_real_world_registration_visual_qa.R", f"{key}: generator path drifted")
        validate_hashed_repository_artifact(receipt["manifest"], f"{key}: manifest receipt")
        validate_hashed_repository_artifact(receipt["generator"], f"{key}: generator receipt")
        artifacts = {}
        for case in cases:
            require(case.get("methodOwner") == owner and case.get("methodId") == method, f"{key}: case method ownership drifted")
            if owner == "reframe4s":
                expected_method_status = {
                    "basinbridge-public-t1-to-mni": "bridge-stage-only-no-exported-volume",
                    "basinbridge-sub18-to-mni": "rejected-fine-topology-and-export",
                }
                require(case.get("methodStatus") == expected_method_status[case["courtId"]], f"{key}: missing or rejected method output was promoted")
                require(not any(source.get("role") in {"registration-result", "hodgeflow-warp", "hodgeflow-affine"} for source in case.get("sources", [])), f"{key}: external method substituted into direct case")
            outputs = case.get("outputs", [])
            require(len(outputs) == 1 and outputs[0].get("role") == "detailed-plate", f"{key}: detailed plate missing")
            for output in outputs:
                artifacts[output["path"]] = output
        court_outputs = receipt.get("courtOutputs", {})
        require(isinstance(court_outputs, dict), f"{key}: court outputs must be an object")
        require(set(court_outputs) == (set(expected_cases) if owner != "reframe4s" else set()), f"{key}: contact sheet ownership drifted")
        contacts = [output for outputs in court_outputs.values() for output in outputs]
        require(len(contacts) == expected_coverage["contactSheets"], f"{key}: contact sheet count drifted")
        for output in contacts:
            require(output.get("role") == "contact-sheet", f"{key}: invalid contact sheet")
            artifacts[output["path"]] = output
        for path, artifact in artifacts.items():
            require(Path(path).is_relative_to(output_root) and ".." not in Path(path).parts, f"{key}: artifact outside owned output root")
            validate_hashed_repository_artifact(artifact, f"{key}: rendered image")
        require({str(path.relative_to(ROOT)) for path in (ROOT / output_root).rglob("*.png")} == set(artifacts), f"{key}: unregistered or misplaced images in bundle")

        require(review.get("receipt", {}).get("path") == qa["receipt"], f"{key}: review names another receipt")
        validate_hashed_repository_artifact(review["receipt"], f"{key}: reviewed receipt")
        reviewed = review.get("reviewedArtifacts", [])
        require(len(reviewed) >= (2 if owner == "reframe4s" else 13), f"{key}: review coverage narrowed")
        for artifact in reviewed:
            require(artifact.get("path") in artifacts, f"{key}: reviewed image belongs to another bundle")
            validate_hashed_repository_artifact(artifact, f"{key}: reviewed image")
        findings = review.get("courtFindings", [])
        require(len(findings) == len(expected_cases) and {f.get("courtId") for f in findings} == set(expected_cases), f"{key}: review findings cross method ownership")


def validate_source(court_id: str, source: dict[str, Any]) -> None:
    kind = source.get("kind")
    require(kind in {"repository", "external"}, f"{court_id}: invalid source kind")
    raw_path = source.get("path")
    require(isinstance(raw_path, str) and raw_path, f"{court_id}: source path missing")
    require(not Path(raw_path).is_absolute(), f"{court_id}: source path must be relative")

    if kind == "external":
        root_env = source.get("rootEnv")
        require(isinstance(root_env, str) and root_env, f"{court_id}: external rootEnv missing")
        configured_root = os.environ.get(root_env)
        if configured_root:
            external_path = Path(configured_root) / raw_path
            require(
                external_path.exists(),
                f"{court_id}: configured external source is absent: ${root_env}/{raw_path}",
            )
            expected_sha = source.get("sha256")
            if expected_sha:
                require(external_path.is_file(), f"{court_id}: hashed external source is not a file")
                actual_sha = hashlib.sha256(external_path.read_bytes()).hexdigest()
                require(
                    actual_sha == expected_sha,
                    f"{court_id}: external source hash changed: ${root_env}/{raw_path}",
                )
        require(not source.get("assertions"), f"{court_id}: external source cannot carry JSON assertions")
        return

    path = ROOT / raw_path
    require(path.is_file(), f"{court_id}: repository source is absent: {raw_path}")
    assertions = source.get("assertions", [])
    if not assertions:
        return
    require(path.suffix == ".json", f"{court_id}: assertions require a JSON source: {raw_path}")
    document = json.loads(path.read_text(encoding="utf-8"))
    for assertion in assertions:
        pointer = assertion.get("pointer")
        require("equals" in assertion, f"{court_id}: assertion lacks equals: {pointer}")
        actual = json_pointer(document, pointer)
        expected = assertion["equals"]
        require(
            actual == expected,
            f"{court_id}: {raw_path}{pointer} is {actual!r}, expected {expected!r}",
        )


def validate(registry: dict[str, Any]) -> None:
    require(registry.get("schema") == SCHEMA, "unexpected registry schema")
    vocabulary = registry.get("vocabulary", {})
    require(
        set(vocabulary.get("evidenceClasses", {})) == EVIDENCE_CLASSES,
        "evidence-class vocabulary drifted",
    )
    require(
        set(vocabulary.get("runStatuses", {})) == RUN_STATUSES,
        "run-status vocabulary drifted",
    )
    require(
        set(vocabulary.get("decisions", {})) == DECISIONS,
        "decision vocabulary drifted",
    )

    admission = registry.get("halfFlowAdmission", {})
    require(admission.get("requiredPairs") == 20, "HalfFlow required-pair gate changed")
    require(
        admission.get("requiredIndependentDatasets") == 2,
        "HalfFlow required-dataset gate changed",
    )
    require(
        admission.get("currentlyAdmittedPairs") == 0,
        "registry may not admit pairs without a new frozen qualification",
    )
    require((ROOT / admission.get("policy", "")).is_file(), "HalfFlow policy path is absent")
    validate_visual_qa(registry)

    courts = registry.get("courts")
    require(isinstance(courts, list) and courts, "registry has no courts")
    ids: set[str] = set()
    acquired_direct_cases = 0
    for court in courts:
        court_id = court.get("id")
        require(isinstance(court_id, str) and court_id, "court id missing")
        require(court_id not in ids, f"duplicate court id: {court_id}")
        ids.add(court_id)
        require(court.get("dataClass") in DATA_CLASSES, f"{court_id}: invalid data class")
        require(
            court.get("evidenceClass") in EVIDENCE_CLASSES,
            f"{court_id}: invalid evidence class",
        )
        require(court.get("runStatus") in RUN_STATUSES, f"{court_id}: invalid run status")
        require(court.get("decision") in DECISIONS, f"{court_id}: invalid decision")
        for field in ("title", "owner", "task", "evaluation", "topology", "headline"):
            require(isinstance(court.get(field), str) and court[field], f"{court_id}: {field} missing")
        require(court.get("limitations"), f"{court_id}: limitations missing")
        require(
            court.get("countsTowardHalfFlowAdmission") is False,
            f"{court_id}: an existing diagnostic was promoted into the HalfFlow gate",
        )
        require(court.get("admissionReason"), f"{court_id}: admission reason missing")

        counts = court.get("counts", {})
        for field in (
            "casesDeclared",
            "casesExecuted",
            "methodRuns",
            "retainedMethodFailures",
        ):
            value = counts.get(field)
            require(
                isinstance(value, int) and not isinstance(value, bool) and value >= 0,
                f"{court_id}: invalid count {field}",
            )
        require(
            counts["casesExecuted"] <= counts["casesDeclared"],
            f"{court_id}: executed cases exceed declared cases",
        )
        require(
            counts["retainedMethodFailures"] <= counts["methodRuns"],
            f"{court_id}: retained failures exceed method runs",
        )
        if court["runStatus"] == "blocked-before-execution":
            require(counts["casesExecuted"] == 0, f"{court_id}: blocked court executed cases")
            require(court["decision"] == "blocked", f"{court_id}: blocked run has non-blocked decision")
        if court["dataClass"] == "analytic-synthetic":
            require(
                court["evidenceClass"] == "analytic-engineering",
                f"{court_id}: synthetic data received acquired evidence class",
            )
        else:
            require(
                court["evidenceClass"].startswith("acquired-"),
                f"{court_id}: acquired data received analytic evidence class",
            )
            if court["owner"].startswith("reframe4s"):
                acquired_direct_cases += counts["casesExecuted"]

        sources = court.get("sources")
        require(isinstance(sources, list) and sources, f"{court_id}: sources missing")
        for source in sources:
            validate_source(court_id, source)
        reproduce = court.get("reproduce")
        require(isinstance(reproduce, list), f"{court_id}: reproduce must be a list")

    require(
        "c04-flashalign-halfflow-affine-transfer" in ids,
        "synthetic C04 boundary is absent",
    )
    require("basinbridge-public-t1-to-mni" in ids, "real-data smoke court is absent")
    require("basinbridge-sub18-to-mni" in ids, "sub18 negative diagnostic is absent")
    require(
        "flashalign-nonlinear-acquired-qualification" in ids,
        "blocked Flashalign nonlinear court is absent",
    )
    require(acquired_direct_cases == 2, "direct reframe4s acquired-case inventory changed")

    next_court = registry.get("nextCourt", {})
    require(len(next_court.get("lanes", [])) == 4, "next-court lane set is incomplete")
    require(
        "complete retained denominator and typed failure reasons"
        in next_court.get("requiredOutputs", []),
        "next court can silently lose failures",
    )


def markdown_link(document_path: Path, source_path: str) -> str:
    relative = os.path.relpath(ROOT / source_path, document_path.parent)
    return Path(relative).as_posix()


def render(registry: dict[str, Any], document_path: Path) -> str:
    vocabulary = registry["vocabulary"]
    admission = registry["halfFlowAdmission"]
    visual_qa = registry["visualQa"]
    courts = sorted(registry["courts"], key=lambda court: not court["owner"].startswith("reframe4s"))
    acquired = [court for court in courts if court["dataClass"] == "acquired"]
    direct = [court for court in acquired if court["owner"].startswith("reframe4s")]
    direct_blocked = sum(court["runStatus"] == "blocked-before-execution" for court in direct)

    lines = [
        "# Real-world registration evidence",
        "",
        "<!-- Generated by tools/registration/validate_real_world_registration_registry.py. -->",
        "",
        "This is the canonical entry point for reframe4s HalfFlow/BasinBridge and Flashalign",
        "registration evidence. External HodgeFlow references are recorded separately below",
        "and do not validate either implementation in this repository.",
        "Existing manifests and receipts remain the observation records; the registry links",
        "them, assigns a common evidence class, and keeps failed or blocked cases visible.",
        "",
        "The C04 Flashalign-to-HalfFlow result is listed because it is easily confused with",
        "a real-data court. It uses analytic-synthetic images and makes no acquired-T1 claim.",
        "",
        "## Inventory at a glance",
        "",
        f"- Direct reframe4s acquired-image entries: **{len(direct)}**, covering **{sum(c['counts']['casesExecuted'] for c in direct)} executed cases** and **{direct_blocked} blocked court**.",
        "- Flashalign acquired nonlinear registrations: **0 executed rows**.",
        f"- HalfFlow admission: **{admission['currentlyAdmittedPairs']} of {admission['requiredPairs']} pairs** across **0 of {admission['requiredIndependentDatasets']} required datasets**.",
        "",
        "The admission count remains zero because a successful stage smoke test and a retained",
        "failed diagnostic are observations, but neither is a complete qualifying anatomical pair.",
        "",
        "## Image-level QA",
        "",
        f"The [HalfFlow and Flashalign visual QA report]({markdown_link(document_path, visual_qa['report'])})",
        "retains the two acquired HalfFlow input/failure plates and links the existing",
        "Flashalign rigid image with its analytic-synthetic scope explicitly stated.",
        "",
        f"- Acquired HalfFlow final method outputs reviewed: **{visual_qa['directReframeMethodOutputsRendered']} of {visual_qa['directReframeCases']} cases**.",
        "- Flashalign acquired nonlinear qualification: **blocked before execution**.",
        "",
        "Neither acquired HalfFlow case supplies a visual pass. Input images, the saved",
        "ANTs comparator, and external HodgeFlow registrations cannot fill that gap.",
        "",
        "## Common status vocabulary",
        "",
        "| Evidence class | Meaning |",
        "| --- | --- |",
    ]
    for name, meaning in vocabulary["evidenceClasses"].items():
        lines.append(f"| `{name}` | {meaning} |")
    lines.extend(
        [
            "",
            "| Run status | Meaning |",
            "| --- | --- |",
        ]
    )
    for name, meaning in vocabulary["runStatuses"].items():
        lines.append(f"| `{name}` | {meaning} |")
    lines.extend(
        [
            "",
            "| Decision | Meaning |",
            "| --- | --- |",
        ]
    )
    for name, meaning in vocabulary["decisions"].items():
        lines.append(f"| `{name}` | {meaning} |")

    lines.extend(
        [
            "",
            "## Reframe4s court registry",
            "",
            "| Court | Data | Task | Evidence | Cases | Run | Decision | HalfFlow admission |",
            "| --- | --- | --- | --- | ---: | --- | --- | --- |",
        ]
    )
    for court in courts:
        if not court["owner"].startswith("reframe4s"):
            continue
        counts = court["counts"]
        lines.append(
            f"| [{court['title']}](#{court['id']}) | {court['dataClass']} | {court['task']} | "
            f"`{court['evidenceClass']}` | {counts['casesExecuted']}/{counts['casesDeclared']} | "
            f"`{court['runStatus']}` | `{court['decision']}` | no |"
        )

    lines.extend(["", "## Reframe4s court details", ""])
    external_heading_added = False
    for court in courts:
        if not court["owner"].startswith("reframe4s") and not external_heading_added:
            external_qa = registry["externalVisualQa"]
            lines.extend([
                "## External HodgeFlow references",
                "",
                "These courts ran the adjacent HodgeFlow implementation. They provide no",
                "HalfFlow or Flashalign method validation and are excluded from the primary QA bundle.",
                "",
                f"The [separate external visual report]({markdown_link(document_path, external_qa['report'])}) retains "
                f"**{external_qa['externalHodgeFlowMethodOutputsRendered']} of {external_qa['externalHodgeFlowCases']}** "
                "locally reviewable method outputs and six missing-transform cases.",
                "",
            ])
            external_heading_added = True
        counts = court["counts"]
        lines.extend(
            [
                f"<a id=\"{court['id']}\"></a>",
                "",
                f"### {court['title']}",
                "",
                court["headline"],
                "",
                f"- **Owner:** {court['owner']}",
                f"- **Execution:** {counts['casesExecuted']} of {counts['casesDeclared']} cases; "
                f"{counts['methodRuns']} method run{'s' if counts['methodRuns'] != 1 else ''}; "
                f"{counts['retainedMethodFailures']} retained method failure{'s' if counts['retainedMethodFailures'] != 1 else ''}",
                f"- **Evaluation:** {court['evaluation']}",
                f"- **Topology/export:** {court['topology']}",
                f"- **Admission:** Does not count. {court['admissionReason']}",
                "- **Limits:** " + " ".join(court["limitations"]),
                "- **Evidence:**",
            ]
        )
        for source in court["sources"]:
            if source["kind"] == "repository":
                target = markdown_link(document_path, source["path"])
                lines.append(f"  - [`{source['path']}`]({target})")
            else:
                lines.append(f"  - `${source['rootEnv']}/{source['path']}`")
        if court["reproduce"]:
            lines.extend(["", "**Reproduce:**", ""])
            for command in court["reproduce"]:
                lines.extend(["```text", command, "```"])
        elif court["runStatus"] == "blocked-before-execution":
            lines.extend(["", "**Reproduce:** No execution command exists because the court is blocked before execution."])
        else:
            lines.extend(["", "**Reproduce:** Follow the frozen protocol in the external evidence tree; no reframe4s command is registered."])
        lines.append("")

    next_court = registry["nextCourt"]
    lines.extend(
        [
            "## One next court",
            "",
            next_court["minimumPurpose"],
            "",
            "Use these frozen lanes for every pair:",
            "",
        ]
    )
    for index, lane in enumerate(next_court["lanes"], start=1):
        lines.append(f"{index}. {lane}")
    lines.extend(["", "Retain these outputs:", ""])
    for output in next_court["requiredOutputs"]:
        lines.append(f"- {output}")
    lines.extend(
        [
            "",
            next_court["promotionBoundary"],
            "",
            "## Maintenance",
            "",
            "Edit the registry, then regenerate and validate this document:",
            "",
            "```text",
            "python3 tools/registration/validate_real_world_registration_registry.py --write-doc",
            "python3 tools/registration/validate_real_world_registration_registry.py",
            "```",
            "",
            "Repository-local JSON assertions bind headline facts to their original receipts.",
            "External evidence is checked for existence when `HODGEFLOW_ROOT` is set, but remains",
            "owned and qualified by that project.",
            "",
        ]
    )
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--registry", type=Path, default=DEFAULT_REGISTRY)
    parser.add_argument("--write-doc", action="store_true")
    arguments = parser.parse_args()
    registry_path = arguments.registry if arguments.registry.is_absolute() else ROOT / arguments.registry
    registry = json.loads(registry_path.read_text(encoding="utf-8"))
    validate(registry)

    raw_document = registry.get("generatedDocument")
    require(isinstance(raw_document, str) and raw_document, "generated-document path missing")
    document_path = ROOT / raw_document
    rendered = render(registry, document_path)
    if arguments.write_doc:
        document_path.write_text(rendered, encoding="utf-8")
        print(f"wrote {document_path.relative_to(ROOT)}")
    else:
        require(document_path.is_file(), f"generated document is absent: {raw_document}")
        require(
            document_path.read_text(encoding="utf-8") == rendered,
            f"generated document is stale: run {Path(__file__).relative_to(ROOT)} --write-doc",
        )
    print(
        "real-world registration registry valid: "
        f"{len(registry['courts'])} courts, "
        f"{registry['visualQa']['directReframeCases']} direct acquired cases; "
        f"{registry['externalVisualQa']['externalHodgeFlowCases']} external reference cases"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
