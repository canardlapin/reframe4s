#!/usr/bin/env node

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import {
  definitionErrors,
  evaluateGate,
  parseDiagnostics,
  sbtInvocation,
} from "./run-motion-laptop-gate.mjs";

const definition = JSON.parse(
  readFileSync("benchmarks/motion/execution-tiers-v1.json", "utf8"),
);
assert.deepEqual(definitionErrors(definition), []);
assert.equal(definition.laptopRegression.claimAuthority, "none");
assert.equal(definition.laptopRegression.externalDatasets, false);
assert.equal(definition.laptopRegression.externalComparators, false);
assert.equal(definition.laptopRegression.niftiOutputs, false);
assert.equal(
  definition.releaseSuperiority.executionLocation,
  "qualified-larger-host",
);
assert.equal(definition.releaseSuperiority.status, "deferred");

const output = `
[info] MIG-423 six-DOF diagnostic: E_identity=0.02, E_truth=0.0000183, E_recovered=0.0000181, translation_error_mm=0.001716758, rotation_error_deg=0.043979019, baseline_translation_error_mm=0.01, baseline_rotation_error_deg=0.08
[info] MIG-423 capture court: scenarios=5, capturedSuccesses=5, baselineSuccesses=5, records=[omitted]
[info] MIG-414 pair JVM baseline: voxels=9261, evaluations=11, smallAllocated=200000 B, largeAllocated=211696 B, observedPeakHeapDelta=0 B, median=4839792 ns, p95=5083166 ns, throughput=21.049 MVox/s, checksum=1.0, environment=OpenJDK 64-Bit Server VM 25.0.1; Mac OS X aarch64
[info] MIG-414 application JVM baseline: samples=147456, allocated=2371552 B, payload=2359296 B, fixedOverhead=12256 B, observedPeakHeapDelta=4194304 B, median=2700916 ns, p95=2821916 ns, throughput=54.594 MSamples/s, valueChecksum=1.0, validityChecksum=1.0
[info] MIG-422 normal-kernel JVM baseline: smallSamples=512, largeSamples=32768, repetitions=4, smallAllocated=88 B, largeAllocated=88 B, median=1750000 ns, throughput=74.863 MSamples/s, checksum=32259.615
[info] suite one finished: 0 failed, 0 ignored, 9 total
[info] suite two finished: 0 failed, 0 ignored, 1 total
[info] suite three finished: 0 failed, 0 ignored, 2 total
[info] suite four finished: 0 failed, 0 ignored, 1 total
[info] Passed: Total 13, Failed 0, Errors 0, Passed 13
`;
const diagnostics = parseDiagnostics(output);
assert.equal(diagnostics.sixDof.translation_error_mm, 0.001716758);
assert.equal(diagnostics.sixDof.rotation_error_deg, 0.043979019);
assert.equal(diagnostics.capture.capturedSuccesses, 5);
assert.equal(diagnostics.capture.captured, undefined);
assert.equal(diagnostics.pair.largeAllocated, 211696);
assert.equal(diagnostics.application.fixedOverhead, 12256);
assert.equal(diagnostics.kernel.smallAllocated, 88);
assert.deepEqual(diagnostics.testSummary, {
  total: 13,
  failed: 0,
  errors: 0,
  passed: 13,
  ignored: 0,
});
assert.equal(
  diagnostics.runtime,
  "OpenJDK 64-Bit Server VM 25.0.1; Mac OS X aarch64",
);

const execution = {
  exitCode: 0,
  freeDiskBytesBefore: 2 ** 30,
  netBuildGrowthBytes: 1024,
  logComplete: true,
  originalLogBytes: 4096,
};
const accepted = evaluateGate(definition, diagnostics, execution);
assert.equal(accepted.passed, true);
assert(accepted.checks.every((check) => check.pass));

const missing = evaluateGate(
  definition,
  parseDiagnostics(output.replace("MIG-414 pair JVM baseline:", "missing:")),
  execution,
);
assert.equal(missing.passed, false);
assert(
  missing.checks.some(
    (check) => check.id === "diagnostic-pair" && !check.pass,
  ),
);

const ignoredOutput = output.replace(
  "suite four finished: 0 failed, 0 ignored, 1 total",
  "suite four finished: 0 failed, 1 ignored, 1 total",
);
const rejectedIgnored = evaluateGate(
  definition,
  parseDiagnostics(ignoredOutput),
  execution,
);
assert.equal(rejectedIgnored.passed, false);
assert(
  rejectedIgnored.checks.some(
    (check) => check.id === "test-count" && !check.pass,
  ),
);

const slow = structuredClone(diagnostics);
slow.application.throughput = 9.999;
const rejectedSlow = evaluateGate(definition, slow, execution);
assert.equal(rejectedSlow.passed, false);
assert(
  rejectedSlow.checks.some(
    (check) => check.id === "application-throughput" && !check.pass,
  ),
);

const inaccurate = structuredClone(diagnostics);
inaccurate.sixDof.rotation_error_deg = 0.050001;
const rejectedAccuracy = evaluateGate(definition, inaccurate, execution);
assert.equal(rejectedAccuracy.passed, false);
assert(
  rejectedAccuracy.checks.some(
    (check) => check.id === "six-dof-rotation" && !check.pass,
  ),
);

const oversized = evaluateGate(definition, diagnostics, {
  ...execution,
  logComplete: false,
  originalLogBytes:
    definition.laptopRegression.resourceBudget.maximumPersistedLogBytes + 1,
});
assert.equal(oversized.passed, false);
assert(
  oversized.checks.some(
    (check) => check.id === "complete-bounded-log" && !check.pass,
  ),
);

const invocation = sbtInvocation(definition);
assert.equal(invocation.executable, "sbt");
assert(invocation.arguments.includes("-J-Xmx2048M"));
for (const suite of definition.laptopRegression.suites) {
  assert(invocation.arguments.at(-1).includes(suite));
}

const weakened = structuredClone(definition);
weakened.laptopRegression.claimAuthority =
  "speed-and-accuracy-superiority";
assert(
  definitionErrors(weakened).includes(
    "laptopRegression.claimAuthority must be none",
  ),
);

console.log("motion laptop gate tests: ok");
