#!/usr/bin/env node

import assert from "node:assert/strict";

import { validateMetricRecord } from "./verify-motion-metrics.mjs";

const hash = "a".repeat(64);
const revision = "b".repeat(40);
const scoringRow = {
  subject: "synthetic-41001",
  scenario: "clean_small_isotropic",
  stratum: "synthetic",
  court: "common_resampler_estimation",
  implementation: "reframe4s_motion",
  phase: "measured",
  repetition: "0",
  allocated_core_count: "4",
};
const run = {
  subject: scoringRow.subject,
  scenario: scoringRow.scenario,
  court: scoringRow.court,
  implementation: scoringRow.implementation,
  phase: "measured",
  repetition: 0,
  allocatedCoreCount: 4,
  termination: "success",
  exitStatus: 0,
  poseSha256: hash,
  correctedImageSha256: hash,
};
const frame = (index) => ({
  frame: index,
  physical_landmark_displacement_p95_mm: 0.1,
  relative_rotation_error_degrees: 0.1,
  framewise_displacement_error_mm: index === 0 ? null : 0.1,
  corrected_image_nrmse: index === 0 ? null : 0.01,
  boundary_shell_nrmse: index === 0 ? null : 0.01,
  temporal_difference_nrmse: index === 0 ? null : 0.01,
  edge_energy_log_error: index === 0 ? null : 0.01,
  ringing_fraction: index === 0 ? null : 0.001,
});
const metrics = Object.fromEntries(
  [
    "physical_landmark_displacement_p95_mm",
    "corrected_image_nrmse",
    "relative_rotation_error_p95_degrees",
    "framewise_displacement_error_p95_mm",
    "boundary_shell_nrmse",
    "temporal_difference_nrmse",
    "edge_energy_log_error",
    "ringing_fraction",
  ].map((name) => [name, 0.1]),
);
const metric = {
  schemaVersion: "reframe4s.motion-superiority.metrics/v1",
  definitionVersion: "motion-metrics-v1",
  protocolSha256: hash,
  scorerRevision: revision,
  runRecordSha256: hash,
  subject: scoringRow.subject,
  scenario: scoringRow.scenario,
  stratum: scoringRow.stratum,
  court: scoringRow.court,
  implementation: scoringRow.implementation,
  phase: "measured",
  repetition: 0,
  allocatedCoreCount: 4,
  frames: [frame(0), frame(1)],
  metrics,
};
const values = {
  metric,
  run,
  scoringRow,
  lock: {
    protocol: { sha256: hash },
    candidate: { revision },
  },
  runRecordSha256: hash,
};
assert.deepEqual(validateMetricRecord(values), []);

const nonFinite = structuredClone(metric);
nonFinite.metrics.corrected_image_nrmse = Number.NaN;
assert(
  validateMetricRecord({ ...values, metric: nonFinite }).some((error) =>
    error.includes("not finite"),
  ),
);

const relabeled = structuredClone(metric);
relabeled.implementation = "nifreeze";
assert(
  validateMetricRecord({ ...values, metric: relabeled }).some((error) =>
    error.includes("linked raw row"),
  ),
);

const scoredReference = structuredClone(metric);
scoredReference.frames[0].corrected_image_nrmse = 0;
assert(
  validateMetricRecord({
    ...values,
    metric: scoredReference,
  }).some((error) => error.includes("reference-frame contract")),
);

console.log("motion metric-record tests: ok");
