#!/usr/bin/env python3
"""Replay C04 controls with a separately identified exporter and topology trace.

Creates temporary benchmark sources, never edits the sealed runner or its data.
The baseline exporter is read from its immutable Git revision. Current mode calls
production inspect/admit directly. Output directories must not already exist.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path('/Users/bbuchsbaum/code/scala/reframe4s')
RUNNER = Path('benchmarks/flashalign/runner/src/main/scala/reframe4s/benchmark/flashalign/FlashalignHalfFlowResidual.scala')
EXPORTER = Path('modules/reframe4s-halfflow/shared/src/main/scala/reframe4s/halfflow/ForwardMidpointExport.scala')
BASE = 'fa015c38a1b481096646d2c857191c327fc7a9e5'
MANIFEST = 'benchmarks/flashalign/manifests/flashalign-halfflow-affine-transfer-v1.json'
SEAL = 'b150d86bd0e707e91faa72d7d9d4caa539c7d07165fd70fd9b647c2fbcdd6989'
RUNNER_SHA = 'e9388b9d6c4363508c46c24445e06ae9dcf09dee738dec8cc19dd7ee60d47e91'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['baseline', 'current'])
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    original = (ROOT / RUNNER).read_bytes()
    assert sha(original) == RUNNER_SHA, 'frozen recipient runner has changed'
    assert sha((ROOT / MANIFEST).read_bytes()) == SEAL
    paths = sorted((ROOT / 'modules/reframe4s-halfflow/shared/src/main/scala').rglob('*.scala'))
    sources = {str(p.relative_to(ROOT)): sha(p.read_bytes()) for p in paths}
    source_id = sha(json.dumps(sources, sort_keys=True).encode())
    runner = original.decode()
    names = set(re.findall(r'^(?:(?:private(?:\[\w+\])?|final) )*(?:case class|class|trait|object|def) (\w+)', runner, re.M))
    for name in sorted(names, key=len, reverse=True):
        runner = re.sub(r'\b' + name + r'\b', 'ExportProbe' + name, runner)
    runner = runner.replace('ForwardMidpointExporter', 'ExportTopologyProbe')
    runner = runner.replace('"flashalign-halfflow-affine-transfer-v1"', f'"halfflow-export-topology-v1-{args.mode}"')
    runner = runner.replace('dae5bac9521388a97159dfd1c0b85d32b5193eaf80e97a4632e622ba91f11172', source_id)
    runner = runner.replace('accumulated.flatMap { completed =>', 'accumulated.flatMap { completed =>\n          println(s"DIAG_CASE\\t${pair.id}")')
    runner = runner.replace('case Right(optimized) =>\n', 'case Right(optimized) =>\n                println(s"DIAG_LANE\\t${if upstreamComplete then "C5" else "C4"}")\n')
    old = ''
    if args.mode == 'baseline':
        old = subprocess.check_output(['git', 'show', f'{BASE}:{EXPORTER}'], cwd=ROOT).decode()
    exporter = 'ForwardMidpointExporter'
    refiner = 'ResidualInverseRefiner'
    diagnostic = r'''
package reframe4s.halfflow
import reframe4s.halfflow.internal.*
object ExportTopologyProbe:
  private var captureIndex = 0
  def build[W,F,M](state: ForwardMidpoint[W,F,M], config: ResidualInverseConfig): Either[ForwardExportError, ForwardMidpointExport[F,M]] =
    diagnose("fixed-arm", state.fixed.residual)
    diagnose("moving-arm", state.moving.residual)
    val fixed = REFINER.refine(state.fixed.residual, config)
    val moving = REFINER.refine(state.moving.residual, config)
    val captureDirectory = java.nio.file.Paths.get("/Users/bbuchsbaum/code/scala/reframe4s/benchmarks/flashalign/local-results/hfq-cell-fix-20261015/c04-replay-v1/fields").resolve(f"call-$captureIndex%02d")
    captureIndex += 1
    java.nio.file.Files.createDirectories(captureDirectory)
    dump(captureDirectory.resolve("fixed-forward"), state.fixed.residual)
    dump(captureDirectory.resolve("moving-forward"), state.moving.residual)
    dump(captureDirectory.resolve("fixed-inverse"), fixed.inverse)
    dump(captureDirectory.resolve("moving-inverse"), moving.inverse)
    println(s"DIAG_DUMP\t$captureDirectory")
    diagnose("fixed-inverse", fixed.inverse)
    diagnose("moving-inverse", moving.inverse)
    EXPORTER.inspect(state, config).flatMap { candidate =>
      diagnose("endpoint-forward", candidate.transform.forward)
      diagnose("endpoint-backward", candidate.transform.backward)
      for (label, report) <- Vector("fixed" -> candidate.fixedResidualInverse, "moving" -> candidate.movingResidualInverse) do
        println(s"DIAG_INVERSE\t$label\t${report.maximumInteriorMm}\t${report.maximumInteriorVox}\t${math.max(report.forwardThenInverse.allMaximumMm, report.inverseThenForward.allMaximumMm)}\t${report.forwardThenInverse.validFraction}\t${report.inverseThenForward.validFraction}")
      println(s"DIAG_ROUNDTRIP\t${candidate.endpointRoundTrip}")
      EXPORTER.admit(candidate, config)
    }
  private def diagnose[A,B](label: String, pull: DensePull[A,B]): Unit =
    val g = pull.from.grid
    val ds = new Array[Double](g.nVoxels)
    val vs = new Array[Boolean](g.nVoxels)
    val red = JacobianReduction()
    HalfFlowKernels.jacobianDeterminantsReduceInto(pull.sourceCoordinates, ds, vs, DenseFieldSampler(g), pull.validity, red)
    val bad = ds.indices.filter(i => vs(i) && ds(i) <= 0).map { i =>
      s"(${i%g.extentX},${(i/g.extentX)%g.extentY},${i/(g.extentX*g.extentY)}):${ds(i)}"
    }
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val stream = new java.io.DataOutputStream(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest))
    for i <- 0 until g.nVoxels do
      stream.writeBoolean(pull.validity.contains(i))
      for component <- 0 until 3 do stream.writeDouble(pull.sourceCoordinates.linearComponent(i, component))
    stream.close()
    val hash = digest.digest().map(byte => f"${byte & 255}%02x").mkString
    println(s"DIAG_TOPOLOGY\t$label\t${red.minimumOrNaN}\t${red.nonPositive}\t${red.evaluated}\t$hash\t${bad.mkString(",")}")
  private def dump[A,B](path: java.nio.file.Path, pull: DensePull[A,B]): Unit =
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
'''.replace('EXPORTER', exporter).replace('REFINER', refiner)
    diagnostic = diagnostic.replace('import reframe4s.halfflow.internal.*\n', '')
    with tempfile.TemporaryDirectory(prefix='halfflow-export-probe-') as temp:
        temp = Path(temp)
        court = temp / 'Court.scala'
        probe = temp / 'Probe.scala'
        court.write_text(runner)
        probe.write_text(diagnostic)
        baseline_source = temp / 'ForwardMidpointExportBaseline.scala'
        if old:
            baseline_source.write_text(old)
        receipt = dict(mode=args.mode, baseline_revision=BASE if old else None,
                       source_id=source_id, source_sha256=sources, runner_sha256=RUNNER_SHA,
                       exporter_override_sha256=sha(old.encode()) if old else None,
                       generated_court_sha256=sha(court.read_bytes()), generated_probe_sha256=sha(probe.read_bytes()),
                       original_manifest_sha256=SEAL, original_controls_unchanged=True,
                       scope='Diagnostic replay; separate from the sealed C04 estimand and release evidence.')
        (output / 'provenance.json').write_text(json.dumps(receipt, indent=2) + '\n')
        command = ['sbt', '-Dsbt.supershell=false',
                   f'set flashalignBenchmarkJVM / Compile / unmanagedSources ++= Seq(file("{court}"), file("{probe}"))',
                   f'flashalignBenchmarkJVM/runMain reframe4s.benchmark.flashalign.ExportProbeFlashalignHalfFlowComparisonCourt {output / "rows.jsonl"} {MANIFEST} {SEAL}']
        if old:
            command.insert(2, f'set LocalProject("reframe4s-halfflowJVM") / Compile / unmanagedSources ~= (_.filterNot(_.getName == "ForwardMidpointExport.scala") :+ file("{baseline_source}"))')
        with (output / 'execution.log').open('w') as log:
            result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        assert sources == {str(p.relative_to(ROOT)): sha(p.read_bytes()) for p in paths}, 'source changed during execution'
        if result.returncode:
            raise SystemExit(f'probe failed; see {output / "execution.log"}')
        rows = [json.loads(line) for line in (output / 'rows.jsonl').read_text().splitlines()]
        receipt['rows_sha256'] = sha((output / 'rows.jsonl').read_bytes())
        receipt['execution_log_sha256'] = sha((output / 'execution.log').read_bytes())
        receipt['outcomes'] = {c['case_id']: {r['lane']: dict(status=r['status'], rms_mm=r['metrics']['landmark_rms_mm']) for r in c['rows'] if r['lane'] in ['C1', 'C4', 'C5']} for c in rows}
        (output / 'provenance.json').write_text(json.dumps(receipt, indent=2) + '\n')
        print(json.dumps(receipt['outcomes'], indent=2))


if __name__ == '__main__':
    main()
