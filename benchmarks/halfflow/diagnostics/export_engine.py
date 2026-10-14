#!/usr/bin/env python3
"""Freeze a build used only for offline export, with no new fitting attempts."""
import argparse
from datetime import datetime, timezone
from pathlib import Path
import shutil
from trajectory_study import ROOT, read, write, sha, verify, freeze_engine, verify_engine


def freeze(output, captures, classpath):
    if output.exists():
        raise FileExistsError(output)
    original, _ = verify(captures)
    output.mkdir(parents=True)
    protocol=dict(schema='hfq-offline-export-build-v1',created_at=datetime.now(timezone.utc).isoformat(),
        cases=original['cases'],lanes=[],build_only=True,
        purpose='Build snapshot for offline checkpoint reconstruction; no registration fits or new algorithm policies',
        capture_protocol_sha256=sha(captures/'engine/protocol.json'),
        qualification_claim=False,runtime_comparison_allowed=False)
    write(output/'declared-protocol.json',protocol)
    engine=output/'engine'
    freeze_engine(engine,output/'declared-protocol.json',classpath)
    verify_engine(engine)
    extra={}
    for path in [Path(__file__),ROOT/'benchmarks/halfflow/reference/measure_process.py']:
        relative=path.relative_to(ROOT)
        target=engine/'frozen-source'/relative
        target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(path,target);extra[str(relative)]=sha(target)
    write(output/'checkpoint-manifest.json',dict(schema='hfq-offline-build-capture-binding-v1',
        capture_protocol_sha256=sha(captures/'engine/protocol.json'),build_only=True))
    write(output/'diagnostic-seal.json',dict(schema='hfq-offline-export-build-seal-v1',
        engine_freeze_sha256=sha(engine/'freeze.json'),extra_sources=extra,
        checkpoint_manifest_sha256=sha(output/'checkpoint-manifest.json'),build_only=True))
    verify(output)
    print('FROZEN offline export build; no new registration fits',flush=True)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for key in ['output','captures','classpath']:p.add_argument('--'+key,type=Path,required=True)
    a=p.parse_args();freeze(a.output.resolve(),a.captures.resolve(),a.classpath.resolve())
