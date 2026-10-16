#!/usr/bin/env node

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import {
  validateAssetManifest,
  validatePlanAssets,
} from "./verify-motion-assets.mjs";

const protocol = JSON.parse(
  readFileSync("benchmarks/motion/protocol-v2.json", "utf8"),
);
const hash = "a".repeat(64);
const revision = "b".repeat(40);
const realRows = Array.from({ length: 12 }, (_, index) => ({
  subject: `sub-${String(index + 1).padStart(2, "0")}`,
  run: "run-01",
}));
const lock = { realAnatomy: { manifest: realRows } };

function file(path) {
  return { path, sha256: hash, bytes: 1024 };
}

function asset(subject, scenario, stratum, sourceSubject, sourceRun) {
  const prefix = `${subject}/${scenario}`;
  return {
    subject,
    scenario,
    stratum,
    input: file(`${prefix}/input.nii.gz`),
    motionFreeTruth: file(`${prefix}/truth.nii.gz`),
    truthPoses: file(`${prefix}/truth-poses.csv`),
    mask: file(`${prefix}/mask.nii.gz`),
    landmarks: file(`${prefix}/landmarks.csv`),
    provenance: {
      dataset: stratum === "synthetic" ? "analytic-v1" : "public-v1",
      datasetVersion: "1",
      sourceUrl: "https://example.invalid/source",
      license: "CC0-1.0",
      redistribution: "permitted",
      sourceSubject,
      sourceRun,
      generatorRevision: revision,
    },
  };
}

const scenarios = protocol.scenarioGeneration.scenarios.map(
  (scenario) => scenario.id,
);
const synthetic = protocol.data.syntheticSubjects.seeds.flatMap((seed) =>
  scenarios.map((scenario) =>
    asset(
      `synthetic-${seed}`,
      scenario,
      "synthetic",
      `seed-${seed}`,
      "generated",
    ),
  ),
);
const real = realRows.flatMap((row) =>
  scenarios.map((scenario) =>
    asset(
      `${row.subject}__${row.run}`,
      scenario,
      "real_anatomy",
      row.subject,
      row.run,
    ),
  ),
);
const manifest = {
  schemaVersion: "reframe4s.motion-superiority.assets/v1",
  protocolSha256: hash,
  assets: [...synthetic, ...real],
};

assert.equal(manifest.assets.length, 144);
assert.deepEqual(validateAssetManifest(manifest, protocol, lock), []);

const missing = structuredClone(manifest);
missing.assets.pop();
assert(
  validateAssetManifest(missing, protocol, lock).some((error) =>
    error.includes("does not cover every scenario"),
  ),
);

const duplicate = structuredClone(manifest);
duplicate.assets.push(structuredClone(duplicate.assets[0]));
assert(
  validateAssetManifest(duplicate, protocol, lock).some((error) =>
    error.includes("keys must be unique"),
  ),
);

const base = "/locked";
const plan = manifest.assets.map((row) => ({
  subject: row.subject,
  scenario: row.scenario,
  input: `${base}/${row.input.path}`,
  mask: `${base}/${row.mask.path}`,
}));
assert.deepEqual(
  validatePlanAssets(plan, manifest, `${base}/assets.json`),
  [],
);
const wrongPlan = structuredClone(plan);
wrongPlan[0].input = "/different/input.nii.gz";
assert(
  validatePlanAssets(wrongPlan, manifest, `${base}/assets.json`).some(
    (error) => error.includes("input differs"),
  ),
);

console.log("motion asset manifest tests: ok");
