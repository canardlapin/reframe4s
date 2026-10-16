#!/usr/bin/env python3
"""Run the predeclared acquired-MRI assessment and retain every command outcome.

Use the Python environment and compiled JVM classpath described by the protocol.
Stages are restartable: completed commands, including failures, are never retried.
Large images/maps stay in the caller's ignored output directory.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tarfile
import time

import nibabel as nib
import numpy as np

from real_image_court import sha, write_json

ROOT = Path(__file__).resolve().parents[2]
COURT = ROOT / "benchmarks/flashalign/real_image_court.py"


def source_identity(protocol):
    snapshot = json.loads(subprocess.check_output(
        ["node", "scripts/halfflow-candidate.mjs"], cwd=ROOT))
    files = snapshot["files"] | {
        str(p.relative_to(ROOT)): sha(p)
        for p in (Path(__file__).resolve(), COURT, protocol)
    }
    fingerprint = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()
    return {"base_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
            "sha256": fingerprint, "files": files}


def execute(command, destination, source, environment):
    """A failed process is retained as evidence, not silently retried or accepted."""
    # Lane names can contain decimal parameters; append, never replace, suffixes.
    receipt = Path(str(destination) + ".json")
    log = Path(str(destination) + ".log")
    if receipt.exists():
        old = json.loads(receipt.read_text())
        assert old["command"] == command and old["source_sha256"] == source["sha256"], "resume identity mismatch"
        return old
    destination.parent.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    metadata = {"command": command, "source_sha256": source["sha256"],
                "started_at": datetime.now(timezone.utc).isoformat()}
    print("START", destination.relative_to(destination.parents[2]), flush=True)
    with log.open("x") as stream:
        try:
            result = subprocess.run(command, cwd=ROOT, env=environment, stdout=stream,
                                    stderr=subprocess.STDOUT, timeout=3600)
            metadata["exit_code"] = result.returncode
        except subprocess.TimeoutExpired:
            metadata["exit_code"] = 124
            metadata["failure"] = "Declared one-hour process limit exceeded"
    metadata.update(seconds=time.monotonic() - started, log_sha256=sha(log))
    write_json(receipt, metadata)
    print("DONE", destination.name, "exit", metadata["exit_code"], "seconds", round(metadata["seconds"], 2), flush=True)
    return metadata


def prepare(args, protocol, source, environment):
    archive = args.archive.resolve()
    assert sha(archive) == protocol["dataset"]["archive_sha256"], "archive identity mismatch"
    selected = args.output / "inputs"
    assert not selected.exists(), "preparation requires fresh inputs"
    subjects = sorted({pair[key] for pair in protocol["dataset"]["pairs"] for key in ("moving", "fixed")})
    with tarfile.open(archive, "r:gz") as tar:
        members = tar.getmembers()
        available = sorted({m.name.split("/")[-2] for m in members if m.isfile() and m.name.endswith("/t1weighted_brain.nii.gz")})
        assert subjects == available[:len(subjects)], "protocol does not match lexical selection"
        for subject in subjects:
            directory = selected / subject
            directory.mkdir(parents=True)
            for name in ("t1weighted_brain.nii.gz", "labels.DKT31.manual.nii.gz"):
                matches = [m for m in members if m.isfile() and m.name.endswith("/" + subject + "/" + name)]
                assert len(matches) == 1, (subject, name)
                with tar.extractfile(matches[0]) as stream, (directory / name).open("xb") as target:
                    shutil.copyfileobj(stream, target)
            image = nib.load(directory / "t1weighted_brain.nii.gz")
            mask = nib.Nifti1Image(np.asarray(image.get_fdata() > 0, dtype=np.uint8), image.affine)
            mask.set_sform(image.affine, code=1)
            mask.set_qform(image.affine, code=1)
            nib.save(mask, directory / "positive-intensity-mask.nii.gz")
    write_json(args.output / "selection.json", {"subjects": subjects, "archive_sha256": sha(archive),
        "files": {str(p.relative_to(args.output)): sha(p) for p in sorted(selected.rglob("*.nii.gz"))}})
    for pair in protocol["dataset"]["pairs"]:
        directory = args.output / pair["id"]
        directory.mkdir()
        command = [sys.executable, str(COURT), "prepare", "--spacing-mm", str(protocol["preparation"]["spacing_mm"]),
                   "--output", str(directory / "data")]
        for name in ("moving", "fixed"):
            subject = selected / pair[name]
            command += ["--" + name, str(subject / "t1weighted_brain.nii.gz"),
                        "--" + name + "-mask", str(subject / "positive-intensity-mask.nii.gz")]
        result = execute(command, directory / "commands" / "prepare", source, environment)
        assert result["exit_code"] == 0, "image preparation failed"


def fit(args, protocol, source, environment):
    classpath = args.classpath.read_text().strip()
    assert classpath and all(Path(p).exists() for p in classpath.split(os.pathsep)), "invalid JVM classpath"
    jobs = []
    for pair in protocol["dataset"]["pairs"]:
        directory = args.output / pair["id"]
        data = directory / "data"
        for lane, mode in (("flashalign-default", "affine"),
                           ("flashalign-halfflow", "fine-taper-boundary"),
                           ("halfflow-identity", "identity-fine-taper-boundary")):
            command = [args.java, "-Xmx6G", "-XX:ActiveProcessorCount=2", "-cp", classpath,
                       "reframe4s.halfflow.RealImageRegistration", str(data / "moving.nii.gz"),
                       str(data / "fixed.nii.gz"), str(data / "fixed-mask.nii.gz"), str(directory / lane), mode]
            jobs.append((command, directory / "commands" / lane))
        jobs.append(([sys.executable, str(COURT), "ants", "--data", str(data), "--output", str(directory / "ants")],
                     directory / "commands" / "ants"))
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        results = list(pool.map(lambda job: execute(*job, source, environment), jobs))
    write_json(args.output / "fit-process-summary.json", {
        "completed_commands": len(results), "nonzero_exits": sum(r["exit_code"] != 0 for r in results),
        "note": "Process completion is not registration acceptance; independent evaluation follows."})


def evaluate(args, protocol, source, environment):
    jobs = []
    for pair in protocol["dataset"]["pairs"]:
        directory = args.output / pair["id"]
        common = ["--data", str(directory / "data")]
        for name in ("moving", "fixed"):
            common += ["--" + name + "-labels", str(args.output / "inputs" / pair[name] / "labels.DKT31.manual.nii.gz")]
        for lane in ("flashalign-default", "flashalign-halfflow", "halfflow-identity"):
            for mapping in ("identity", "affine", "halfflow", "halfflow-rejected"):
                if not (directory / lane / (mapping + "-fixed-to-moving.json")).exists():
                    continue
                command = [sys.executable, str(COURT), "evaluate", *common,
                           "--output", str(directory / lane), "--lane", mapping]
                jobs.append((command, directory / "commands" / ("evaluate-" + lane + "-" + mapping)))
        if (directory / "ants/result.json").exists():
            jobs.append(([sys.executable, str(COURT), "evaluate-ants", *common, "--output", str(directory / "ants")],
                         directory / "commands" / "evaluate-ants"))
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        results = list(pool.map(lambda job: execute(*job, source, environment), jobs))
    write_json(args.output / "evaluation-process-summary.json", {"completed_commands": len(results),
        "nonzero_exits": sum(r["exit_code"] != 0 for r in results),
        "note": "Missing outputs remain failed or unexecuted method stages in the four-pair denominator."})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", choices=("prepare", "fit", "evaluate"))
    parser.add_argument("--protocol", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--classpath", type=Path)
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--workers", type=int, default=2)
    args = parser.parse_args()
    args.output, args.protocol = args.output.resolve(), args.protocol.resolve()
    assert 1 <= args.workers <= 2, "at most two simultaneous memory-intensive jobs"
    args.output.mkdir(parents=True, exist_ok=True)
    protocol = json.loads(args.protocol.read_text())
    source = source_identity(args.protocol)
    source_path = args.output / "source.json"
    if source_path.exists():
        assert json.loads(source_path.read_text()) == source, "source changed between assessment stages"
    else:
        write_json(source_path, source)
        shutil.copyfile(args.protocol, args.output / "protocol.json")
        write_json(args.output / "environment.json", {
            "python": sys.version, "platform": platform.platform(),
            "java": subprocess.check_output([args.java, "-version"], stderr=subprocess.STDOUT, text=True).strip(),
            "packages": {d.metadata["Name"]: d.version for d in importlib.metadata.distributions()},
            "workers": args.workers, "native_threads_per_process": 1})
    environment = os.environ.copy()
    environment.update(ITK_GLOBAL_DEFAULT_NUMBER_OF_THREADS="1", OPENBLAS_NUM_THREADS="1", OMP_NUM_THREADS="1",
                       MPLCONFIGDIR=str(args.output / "matplotlib-cache"), PYTHONUNBUFFERED="1")
    {"prepare": prepare, "fit": fit, "evaluate": evaluate}[args.stage](args, protocol, source, environment)


if __name__ == "__main__":
    main()
