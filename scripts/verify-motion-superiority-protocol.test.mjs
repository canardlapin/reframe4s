#!/usr/bin/env node

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import {
  admissionLockErrors,
  admissionErrors,
  parseProtocol,
  validateProtocol,
} from "./verify-motion-superiority-protocol.mjs";

const text = readFileSync("benchmarks/motion/protocol-v2.json", "utf8");
const protocol = parseProtocol(text);
const protocolSha256 = createHash("sha256").update(text).digest("hex");

assert.deepEqual(validateProtocol(protocol), []);
assert.ok(
  admissionErrors(protocol).some((error) =>
    error.includes("admission lock is absent"),
  ),
  "the frozen pre-execution protocol must fail claim admission",
);

const clone = () => structuredClone(protocol);

{
  const mutated = clone();
  mutated.claim.headlineComparators = ["afni_3dvolreg"];
  assert.ok(
    validateProtocol(mutated).some((error) =>
      error.includes("headline comparators"),
    ),
  );
}

{
  const mutated = clone();
  mutated.scenarioGeneration.scenarios[0].weight = 0.5;
  assert.ok(
    validateProtocol(mutated).some((error) =>
      error.includes("weights must sum to one"),
    ),
  );
}

{
  const mutated = clone();
  mutated.scenarioGeneration.scenarios[0].indexToWorld[10] = 0;
  assert.ok(
    validateProtocol(mutated).some((error) =>
      error.includes("affine must be invertible"),
    ),
  );
}

{
  const mutated = clone();
  mutated.metrics.find(
    (metric) => metric.id === "physical_landmark_displacement_p95_mm",
  ).practicalSuperiorityMargin = 0;
  assert.ok(
    validateProtocol(mutated).some((error) =>
      error.includes("positive superiority margin"),
    ),
  );
}

{
  const mutated = clone();
  mutated.execution.jvm.measuredRuns = 1;
  assert.ok(
    validateProtocol(mutated).some((error) =>
      error.includes("twenty measured runs"),
    ),
  );
}

{
  const mutated = clone();
  mutated.scenarioGeneration.referencePolicy.nativeCourt.nifreeze =
    "fixed_frame_0";
  assert.ok(
    validateProtocol(mutated).some((error) =>
      error.includes(
        "nativeCourt nifreeze reference policy must be median_leave_one_volume_out",
      ),
    ),
  );
}

{
  const mutated = clone();
  mutated.implementations.find(
    (implementation) => implementation.id === "reframe4s_motion",
  ).configuration.primaryEffectiveWorkerCount = 4;
  assert.ok(
    validateProtocol(mutated).some((error) =>
      error.includes(
        "reframe4s_motion primary worker counts differ",
      ),
    ),
  );
}

{
  const mutated = clone();
  mutated.statistics.cluster = "frame";
  assert.ok(
    validateProtocol(mutated).some((error) =>
      error.includes("subject clusters"),
    ),
  );
}

{
  const hex = (value) => value.repeat(64);
  const manifest = Array.from({ length: 12 }, (_, index) => ({
    dataset: "public-dataset-v1",
    datasetVersion: "1",
    subject: `sub-${String(index + 1).padStart(2, "0")}`,
    run: "run-01",
    sourceUrl: "https://example.invalid/dataset",
    license: "CC0-1.0",
    redistribution: "permitted",
    sha256: hex("a"),
    bytes: 1024,
    shape: [48, 48, 32, 60],
    indexToWorld: [
      2, 0, 0, -48,
      0, 2, 0, -48,
      0, 0, 2, -32,
      0, 0, 0, 1,
    ],
    trSeconds: 2,
  }));
  const lock = {
    schemaVersion: "reframe4s.motion-superiority.lock/v2",
    protocol: {
      id: protocol.protocolId,
      version: protocol.version,
      sha256: protocolSha256,
    },
    resolvedBlockers: protocol.admission.blockers.map((blocker) => blocker.id),
    candidate: {
      clean: true,
      revision: "b".repeat(40),
      artifactPath: "artifacts/reframe4s-motion.jar",
      artifactSha256: hex("c"),
      versionStdoutSha256: hex("4"),
      versionStderrSha256: hex("5"),
    },
    implementations: protocol.implementations
      .filter((implementation) => implementation.id !== "reframe4s_motion")
      .map((implementation) => ({
        id: implementation.id,
        sourcePin: implementation.pin.value,
        resolved: true,
        digest:
          implementation.id === "fsl_mcflirt"
            ? undefined
            : `sha256:${hex("d")}`,
        imageReference:
          implementation.id === "fsl_mcflirt"
            ? undefined
            : `registry.example/${implementation.id}@sha256:${hex("d")}`,
        platform:
          implementation.id === "fsl_mcflirt"
            ? undefined
            : "linux/amd64",
        packageLockSha256:
          implementation.id === "fsl_mcflirt" ? hex("e") : undefined,
        packageLockPath:
          implementation.id === "fsl_mcflirt"
            ? "locks/fsl-packages.txt"
            : undefined,
        dependencyLockPath:
          implementation.id === "nifreeze"
            ? "locks/nifreeze.lock"
            : undefined,
        dependencyLockSha256:
          implementation.id === "nifreeze" ? hex("6") : undefined,
        licenseAccepted:
          implementation.id === "fsl_mcflirt" ? true : undefined,
        versionStdoutSha256: hex("f"),
        versionStderrSha256: hex("0"),
      })),
    workers: protocol.implementations.map((implementation) => ({
      id: implementation.id,
      primary: {
        allocatedCoreCount:
          implementation.configuration.primaryAllocatedCoreCount,
        requestedWorkerCount:
          implementation.configuration.primaryRequestedWorkerCount,
        effectiveWorkerCount:
          implementation.configuration.primaryEffectiveWorkerCount,
      },
      sensitivity: {
        allocatedCoreCount: 1,
        requestedWorkerCount: 1,
        effectiveWorkerCount: 1,
      },
      evidence: "locked test evidence",
      evidenceSha256: hex("9"),
    })),
    realAnatomy: {
      manifest,
      manifestSha256: hex("1"),
    },
    benchmarkAssets: {
      manifestPath: "benchmarks/motion/assets.json",
      manifestSha256: hex("8"),
    },
    runner: {
      workingDirectory: ".",
      containerEngine: "/locked/bin/docker",
      affinityExecutable: "/locked/bin/taskset",
      javaExecutable: "/locked/bin/java",
      candidateClasspath: "/locked/reframe4s-motion-runner.jar",
      afniExecutable: "/locked/bin/3dvolreg",
      mcflirtExecutable: "/locked/bin/mcflirt",
      pythonExecutable: "/locked/bin/python",
      nifreezeWrapper: "/locked/bin/run_nifreeze.py",
    },
    hardware: {
      hostId: "benchmark-host-1",
      cpuModel: "test-cpu",
      physicalCores: 8,
      cpuSet: "0,2,4,6",
      simultaneousMultithreadingExcluded: true,
      operatingSystem: "linux",
      kernel: "test-kernel",
      firmware: "test-firmware",
      cpuGovernor: "performance",
      thermalPolicy: "no-throttling",
      swapDisabled: true,
      memoryBytes: 68719476736,
      filesystem: "local-ext4",
      pageCachePolicy: "warm-identical",
      jvmVersion: "test-jvm",
      nodeVersion: "test-node",
    },
    environmentManifestPath: "benchmarks/motion/environment.json",
    environmentLockSha256: hex("2"),
    dataLockSha256: hex("8"),
  };
  assert.deepEqual(admissionLockErrors(protocol, lock, protocolSha256), []);

  const incomplete = structuredClone(lock);
  incomplete.realAnatomy.manifest.length = 11;
  assert.ok(
    admissionLockErrors(protocol, incomplete, protocolSha256).some((error) =>
      error.includes("requires 12"),
    ),
  );

  const wrongPin = structuredClone(lock);
  wrongPin.implementations.find(
    (implementation) => implementation.id === "nifreeze",
  ).sourcePin = "changed-after-results";
  assert.ok(
    admissionLockErrors(protocol, wrongPin, protocolSha256).some((error) =>
      error.includes("source pin differs"),
    ),
  );
}

process.stdout.write("motion superiority protocol validator tests: ok\n");
