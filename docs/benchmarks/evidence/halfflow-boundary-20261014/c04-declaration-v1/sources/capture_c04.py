#!/usr/bin/env python3
"""Capture frozen C04 residuals and inverses through the existing diagnostic replay."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import signal
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[3]


def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def write(path,value):path.write_text(json.dumps(value,indent=2)+'\n')


def replace_once(text,before,after):
    if text.count(before)!=1:raise ValueError('Diagnostic source template changed: '+before[:70])
    return text.replace(before,after)


def freeze(output):
    if output.exists():raise FileExistsError(output)
    base=ROOT/'benchmarks/flashalign/diagnose_halfflow_export.py'
    script=base.read_text()
    script=replace_once(script,'ROOT = Path(__file__).resolve().parents[2]','ROOT = Path('+repr(str(ROOT))+')')
    recipient='benchmarks/flashalign/runner/src/main/scala/reframe4s/benchmark/flashalign/FlashalignHalfFlowResidual.scala'
    historical_revision='0c397be7bcf395962a6b3fb3af70012a3c6a5efc'
    historical=subprocess.check_output(['git','show',historical_revision+':'+recipient],cwd=ROOT)
    historical_digest=hashlib.sha256(historical).hexdigest()
    if historical_digest!='fca8fde2928f02599e66173e7b701941a40292abab91ce016e0a95ddfb8b9405':
        raise ValueError('Historical recipient identity changed')
    current_digest=sha(ROOT/recipient)
    script=replace_once(script,"RUNNER_SHA = '"+historical_digest+"'","RUNNER_SHA = '"+current_digest+"'")
    script=replace_once(script,'object ExportTopologyProbe:\n','object ExportTopologyProbe:\n  private var captureIndex = 0\n')
    path=json.dumps(str(output/'fields'))
    insertion=f'''    val captureDirectory = java.nio.file.Paths.get({path}).resolve(f"call-$captureIndex%02d")
    captureIndex += 1
    java.nio.file.Files.createDirectories(captureDirectory)
    dump(captureDirectory.resolve("fixed-forward"), state.fixed.residual)
    dump(captureDirectory.resolve("moving-forward"), state.moving.residual)
    dump(captureDirectory.resolve("fixed-inverse"), fixed.inverse)
    dump(captureDirectory.resolve("moving-inverse"), moving.inverse)
    println(s"DIAG_DUMP\\t$captureDirectory")
'''
    needle='    val moving = REFINER.refine(state.moving.residual, config)\n'
    script=replace_once(script,needle,needle+insertion)
    method='''  private def dump[A,B](path: java.nio.file.Path, pull: DensePull[A,B]): Unit =
    val grid = pull.from.grid
    val stream = new java.io.DataOutputStream(java.nio.file.Files.newOutputStream(java.nio.file.Paths.get(path.toString + ".f64")))
    for component <- 0 until 3; index <- 0 until grid.nVoxels do
      stream.writeDouble(pull.sourceCoordinates.linearComponent(index, component))
    stream.close()
    val validity = new java.io.DataOutputStream(java.nio.file.Files.newOutputStream(java.nio.file.Paths.get(path.toString + ".valid-u8")))
    for index <- 0 until grid.nVoxels do validity.writeBoolean(pull.validity.contains(index))
    validity.close()
    val affine = (for row <- 0 until 4; column <- 0 until 4 yield grid.affine(row,column)).mkString(",")
    val metadata = s"""{"shape":[${grid.extentX},${grid.extentY},${grid.extentZ}],"affine":[$affine],"storage":"big-endian-f64-planar-x-fast"}"""
    val _ = java.nio.file.Files.writeString(java.nio.file.Paths.get(path.toString + ".json"), metadata)
'''
    script=replace_once(script,"'''.replace('EXPORTER', exporter)",method+"'''.replace('EXPORTER', exporter)")
    output.mkdir(parents=True)
    adapter_patch=subprocess.check_output(['git','diff',historical_revision,'HEAD','--',recipient],cwd=ROOT)
    (output/'runner-adapter.patch').write_bytes(adapter_patch)
    generated=output/'source-capture-replay.py';generated.write_text(script)
    paths=list((ROOT/'modules').rglob('*.scala'))+list((ROOT/'benchmarks/flashalign/runner/src').rglob('*.scala'))
    paths=[p for p in paths if 'target' not in p.parts]
    paths += [base,Path(__file__).resolve(),ROOT/'build.sbt']
    inputs=[ROOT/'benchmarks/flashalign/manifests/flashalign-halfflow-affine-transfer-v1.json',
        ROOT/'benchmarks/flashalign/diagnostics/halfflow-export-topology-v1/comparison.json']
    write(output/'protocol.json',dict(schema='hfq-c04-frozen-field-capture-v1',created_at=datetime.now(timezone.utc).isoformat(),
        sources={str(p):sha(p) for p in sorted(paths)},inputs={str(p):sha(p) for p in inputs},
        generated_source_sha256=sha(generated),timeout_seconds=3600,
        historical_runner_revision=historical_revision,historical_runner_sha256=historical_digest,
        current_runner_sha256=current_digest,runner_adapter_patch_sha256=sha(output/'runner-adapter.patch'),
        runner_adaptation='The original helper expects the pre-canonical-provider runner. Use the existing current canonical adapter '
            'with its explicit Git diff; do not reconstruct removed private geometry. Historical attribution requires exact forward residual hash replay.',
        scope='Replay the existing six-case C04 diagnostic, capturing both residuals and the current refiner inverses. '
            'No numerical or admission source changes. Map calls are linked to DIAG_CASE and DIAG_LANE in the log. '
            'Historical r24 attribution requires exact residual hash replay; changed upstream/C5 outcomes stay explicit.',
        historical_r24_case='r24-exact-core-affine',qualification_claim=False,runtime_comparison_allowed=False))
    print('FROZEN C04 diagnostic field capture; production sources unchanged',flush=True)


def run(output):
    p=json.loads((output/'protocol.json').read_text())
    for group in ['sources','inputs']:
        for path,digest in p[group].items():
            if sha(path)!=digest:raise ValueError('Capture identity changed: '+path)
    script=output/'source-capture-replay.py'
    if sha(script)!=p['generated_source_sha256']:raise ValueError('Generated diagnostic source changed')
    receipt=output/'receipt.json'
    if receipt.exists() or (output/'driver.log').exists():raise FileExistsError('Prior attempt retained')
    with (output/'driver.log').open('x') as stream:
        process=subprocess.Popen([sys.executable,str(script),'current',str(output/'replay')],
            cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT,start_new_session=True)
        try:code=process.wait(timeout=p['timeout_seconds'])
        except subprocess.TimeoutExpired:
            __import__('os').killpg(process.pid,signal.SIGTERM)
            try:process.wait(timeout=5)
            except subprocess.TimeoutExpired:__import__('os').killpg(process.pid,signal.SIGKILL);process.wait()
            code=124
    unchanged=all(sha(path)==digest for group in ['sources','inputs'] for path,digest in p[group].items())
    files={str(path.relative_to(output)):sha(path) for path in (output/'fields').rglob('*') if path.is_file()}
    write(receipt,dict(exit_code=code,source_and_inputs_unchanged=unchanged,field_artifact_sha256=files,
        protocol_sha256=sha(output/'protocol.json'),driver_log_sha256=sha(output/'driver.log'),
        qualification_claim=False,runtime_comparison_allowed=False))
    print('CAPTURE TERMINAL',code,'sources unchanged',unchanged,'field files',len(files),flush=True)
    if code or not unchanged:raise SystemExit(code or 1)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['freeze','run']);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();(freeze if args.command=='freeze' else run)(args.output.resolve())
