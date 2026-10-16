#!/usr/bin/env node

import assert from "node:assert/strict";

import { preflightErrors } from "./preflight-motion-court.mjs";

const hash = "a".repeat(64);
const revision = "b".repeat(40);
const hardware = {
  hostId: "host",
  cpuModel: "cpu",
  physicalCores: 4,
  cpuSet: "0,2,4,6",
  simultaneousMultithreadingExcluded: true,
  operatingSystem: "linux",
  kernel: "kernel",
  firmware: "firmware",
  cpuGovernor: "performance",
  thermalPolicy: "locked",
  swapDisabled: true,
  memoryBytes: 1024,
  filesystem: "ext4",
  pageCachePolicy: "warm-identical",
  jvmVersion: "java",
  nodeVersion: "v24",
};
const lock = {
  protocol: { sha256: hash },
  candidate: {
    clean: true,
    revision,
    artifactSha256: hash,
    versionStdoutSha256: hash,
    versionStderrSha256: hash,
  },
  implementations: [
    "afni_3dvolreg",
    "nifreeze",
    "fsl_mcflirt",
  ].map((id) => ({
    id,
    versionStdoutSha256: hash,
    versionStderrSha256: hash,
  })),
  benchmarkAssets: { manifestSha256: hash },
  dataLockSha256: hash,
  environmentLockSha256: hash,
  hardware,
};
const versionProbes = Object.fromEntries(
    [
      "reframe4s_motion",
      "afni_3dvolreg",
      "nifreeze",
      "fsl_mcflirt",
    ].map((id) => [
      id,
      {
        exitStatus: 0,
        stdoutSha256: hash,
        stderrSha256: hash,
      },
    ]),
);
const values = {
  lock,
  protocolSha256: hash,
  gitRevision: revision,
  gitClean: true,
  candidateArtifactSha256: hash,
  assetManifestSha256: hash,
  environmentManifestSha256: hash,
  hardware,
  versionProbes,
};

assert.deepEqual(preflightErrors(values), []);
assert(
  preflightErrors({ ...values, gitClean: false }).some((error) =>
    error.includes("not clean"),
  ),
);
assert(
  preflightErrors({
    ...values,
    versionProbes: {
      ...values.versionProbes,
      afni_3dvolreg: {
        ...values.versionProbes.afni_3dvolreg,
        stdoutSha256: "c".repeat(64),
      },
    },
  }).some((error) => error.includes("version stdout differs")),
);
assert(
  preflightErrors({
    ...values,
    hardware: { ...hardware, kernel: "other" },
  }).some((error) => error.includes("kernel differs")),
);
assert(
  preflightErrors({
    ...values,
    executableErrors: ["missing comparator"],
  }).includes("missing comparator"),
);

console.log("motion preflight tests: ok");
