#!/usr/bin/env node

import assert from "node:assert/strict";

import {
  buildScoringRows,
  encodeScoringPlan,
} from "./prepare-motion-scoring-plan.mjs";

const implementations = [
  "reframe4s_motion",
  "afni_3dvolreg",
  "nifreeze",
  "fsl_mcflirt",
];
const courts = [
  "common_resampler_estimation",
  "native_end_to_end",
];
const asset = {
  subject: "synthetic-41001",
  scenario: "clean_small_isotropic",
  stratum: "synthetic",
  motionFreeTruth: { path: "truth.nii.gz" },
  truthPoses: { path: "truth-poses.csv" },
  mask: { path: "mask.nii.gz" },
  landmarks: { path: "landmarks.csv" },
};
const plan = courts.flatMap((court) =>
  implementations.flatMap((implementation, order) => [
    {
      subject: asset.subject,
      scenario: asset.scenario,
      court,
      implementation,
      phase: "measured",
      repetition: "0",
      order: String(order),
      allocated_core_count: "4",
      reference_index: "0",
      output_directory: `/results/${court}/${implementation}`,
    },
    {
      subject: asset.subject,
      scenario: asset.scenario,
      court,
      implementation,
      phase: "warmup",
      repetition: "0",
      order: String(order),
      allocated_core_count: "4",
      reference_index: "0",
      output_directory: `/warmup/${court}/${implementation}`,
    },
  ]),
);
const result = buildScoringRows(
  plan,
  { assets: [asset] },
  "/assets/manifest.json",
);
assert.deepEqual(result.errors, []);
assert.equal(result.rows.length, 8);
assert.equal(
  result.rows[0].truth,
  "/assets/truth.nii.gz",
);
assert(encodeScoringPlan(result.rows).startsWith("subject\tscenario\t"));

const missing = buildScoringRows(
  plan.slice(2),
  { assets: [asset] },
  "/assets/manifest.json",
);
assert(missing.errors.some((error) => error.includes("expected 8")));

console.log("motion scoring-plan tests: ok");
