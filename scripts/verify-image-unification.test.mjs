import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";
import { fileURLToPath } from "node:url";

const script = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  "verify-image-unification.mjs",
);

test("accepts one-owner dense, sparse, and component-image fixtures", () => {
  withFixture((root) => {
    writeTargetSources(root);
    fs.writeFileSync(path.join(root, "build.sbt"), "// immutable dependency\n");

    const result = runAudit(root);
    assert.equal(result.status, 0, result.stdout + result.stderr);
    assert.equal(JSON.parse(result.stdout).passed, true);
  });
});

test("reports parallel owners, raw access, and a mutable source composite", () => {
  withFixture((root) => {
    writeTargetSources(root);
    write(
      root,
      "modules/image/shared/src/main/scala/scalafim/image/Legacy.scala",
      [
        "package scalafim.image",
        "final class NDArray[A]",
        "final class NeuroImage[A, D]",
        "trait NeuroImageView[A, D]",
        "final class NeuroSpace",
        "final case class GridSpec()",
        "final case class DenseVectorField()",
        "object DenseFieldKernels",
        "object Consumer { val sample = image.values.data(0) }",
      ].join("\n"),
    );
    fs.writeFileSync(
      path.join(root, "build.sbt"),
      "// Temporary source-composite admission route\n",
    );

    const result = runAudit(root);
    assert.equal(result.status, 1, result.stdout + result.stderr);
    const report = JSON.parse(result.stdout);
    assert.equal(report.passed, false);
    assert.equal(report.findingCounts["parallel-ndarray-owner"], 1);
    assert.equal(report.findingCounts["parallel-neuroimage-owner"], 1);
    assert.equal(report.findingCounts["parallel-neuroimage-view"], 1);
    assert.equal(report.findingCounts["parallel-neurospace-owner"], 1);
    assert.equal(report.findingCounts["parallel-gridspec-owner"], 1);
    assert.equal(report.findingCounts["parallel-component-field-owner"], 1);
    assert.equal(report.findingCounts["duplicate-dense-field-kernels"], 1);
    assert.equal(report.findingCounts["raw-image-buffer-access"], 1);
    assert.equal(report.findingCounts["temporary-source-composite"], 1);
  });
});

function writeTargetSources(root) {
  write(
    root,
    "modules/image/shared/src/main/scala/scalafim/image/NeuroVol.scala",
    "final class NeuroVol[A](val sampled: image4s.Sampled[F,D,A,Role,R])\n",
  );
  write(
    root,
    "modules/image/shared/src/main/scala/scalafim/image/NeuroVec.scala",
    "final class NeuroVec[A](val sampled: image4s.Sampled[F,D,A,Role,R])\n",
  );
  write(
    root,
    "modules/image/shared/src/main/scala/scalafim/image/SparseNeuroVec.scala",
    [
      "final class SparseNeuroVec[A](",
      "  val data: RavelArray[A, Rank[2]],",
      "  val support: SparseSupport",
      ")",
    ].join("\n"),
  );
  write(
    root,
    "modules/image/shared/src/main/scala/scalafim/image/MorphismFields.scala",
    "type VectorField[F,D,R] = image4s.ComponentImage[F,D,R]\n",
  );
}

function runAudit(root) {
  return spawnSync(
    process.execPath,
    [script, "--scalafim", root, "--summary-only"],
    { encoding: "utf8" },
  );
}

function withFixture(body) {
  const root = fs.mkdtempSync(
    path.join(os.tmpdir(), "image-unification-audit-"),
  );
  try {
    body(root);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

function write(root, relative, source) {
  const target = path.join(root, relative);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, source);
}
