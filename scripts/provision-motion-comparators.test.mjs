#!/usr/bin/env node

import assert from "node:assert/strict";

import { provisioningErrors } from "./provision-motion-comparators.mjs";

const hash = "a".repeat(64);
const lock = {
  implementations: [
    {
      id: "afni_3dvolreg",
      digest: `sha256:${hash}`,
    },
    {
      id: "nifreeze",
      digest: `sha256:${hash}`,
      dependencyLockSha256: hash,
    },
    {
      id: "fsl_mcflirt",
      packageLockSha256: hash,
    },
  ],
};
const observations = {
  images: {
    afni_3dvolreg: {
      exitStatus: 0,
      repoDigests: [`afni@sha256:${hash}`],
    },
    nifreeze: {
      exitStatus: 0,
      repoDigests: [`nifreeze@sha256:${hash}`],
    },
  },
  fileHashes: {
    nifreeze: hash,
    fsl_mcflirt: hash,
  },
};
assert.deepEqual(provisioningErrors(lock, observations), []);

const changed = structuredClone(observations);
changed.images.afni_3dvolreg.repoDigests = [
  `afni@sha256:${"b".repeat(64)}`,
];
assert(
  provisioningErrors(lock, changed).some((error) =>
    error.includes("local image digest differs"),
  ),
);

const changedFsl = structuredClone(observations);
changedFsl.fileHashes.fsl_mcflirt = "c".repeat(64);
assert(
  provisioningErrors(lock, changedFsl).some((error) =>
    error.includes("FSL package lock differs"),
  ),
);

console.log("motion comparator provisioning tests: ok");
