#!/usr/bin/env node

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import { analyzeMotionCourt } from "./analyze-motion-court.mjs";

const protocol = JSON.parse(
  readFileSync("benchmarks/motion/protocol-v2.json", "utf8"),
);
const scenarios = protocol.scenarioGeneration.scenarios.map(
  (scenario) => scenario.id,
);
const implementations = [
  "reframe4s_motion",
  "afni_3dvolreg",
  "nifreeze",
  "fsl_mcflirt",
];
const syntheticSubjects = protocol.data.syntheticSubjects.seeds.map(
  (seed) => `synthetic-${seed}`,
);
const realSubjects = Array.from(
  { length: 12 },
  (_, index) => `real-${index + 1}`,
);
const assets = {
  assets: [
    ...syntheticSubjects.map((subject) => ({
      subject,
      stratum: "synthetic",
    })),
    ...realSubjects.map((subject) => ({
      subject,
      stratum: "real_anatomy",
    })),
  ],
};
const subjects = [
  ...syntheticSubjects.map((subject) => [subject, "synthetic"]),
  ...realSubjects.map((subject) => [subject, "real_anatomy"]),
];
const accuracy = {
  reframe4s_motion: [0.1, 0.03, 0.1, 0.1],
  afni_3dvolreg: [0.3, 0.06, 0.2, 0.2],
  nifreeze: [0.25, 0.055, 0.18, 0.18],
  fsl_mcflirt: [0.2, 0.045, 0.15, 0.15],
};
const fidelity = {
  reframe4s_motion: [0.05, 0.05, 0.05, 0.005],
  afni_3dvolreg: [0.08, 0.08, 0.09, 0.009],
  nifreeze: [0.07, 0.07, 0.08, 0.008],
  fsl_mcflirt: [0.06, 0.06, 0.07, 0.007],
};
const metricRecords = subjects.flatMap(([subject, stratum]) =>
  scenarios.flatMap((scenario) =>
    implementations.flatMap((implementation) => [
      {
        subject,
        stratum,
        scenario,
        court: "common_resampler_estimation",
        implementation,
        metrics: {
          physical_landmark_displacement_p95_mm:
            accuracy[implementation][0],
          corrected_image_nrmse: accuracy[implementation][1],
          relative_rotation_error_p95_degrees:
            accuracy[implementation][2],
          framewise_displacement_error_p95_mm:
            accuracy[implementation][3],
          boundary_shell_nrmse: fidelity[implementation][0],
          temporal_difference_nrmse: fidelity[implementation][1],
          edge_energy_log_error: fidelity[implementation][2],
          ringing_fraction: fidelity[implementation][3],
        },
      },
      {
        subject,
        stratum,
        scenario,
        court: "native_end_to_end",
        implementation,
        metrics: {
          physical_landmark_displacement_p95_mm:
            accuracy[implementation][0],
          corrected_image_nrmse: accuracy[implementation][1],
          relative_rotation_error_p95_degrees:
            accuracy[implementation][2],
          framewise_displacement_error_p95_mm:
            accuracy[implementation][3],
          boundary_shell_nrmse: fidelity[implementation][0],
          temporal_difference_nrmse: fidelity[implementation][1],
          edge_energy_log_error: fidelity[implementation][2],
          ringing_fraction: fidelity[implementation][3],
        },
      },
    ]),
  ),
);
const elapsed = {
  reframe4s_motion: 1.0,
  afni_3dvolreg: 1.3,
  nifreeze: 1.2,
  fsl_mcflirt: 1.15,
};
const runs = subjects.flatMap(([subject]) =>
  scenarios.flatMap((scenario) =>
    ["common_resampler_estimation", "native_end_to_end"].flatMap((court) =>
      implementations.flatMap((implementation) =>
        Array.from({ length: 20 }, (_, repetition) => ({
          subject,
          scenario,
          court,
          implementation,
          phase: "measured",
          repetition,
          allocatedCoreCount: 4,
          termination: "success",
          peakRssBytes: 1024,
          stageTimes: {
            endToEndSeconds: elapsed[implementation],
          },
        })),
      ),
    ),
  ),
);
const analysis = analyzeMotionCourt({
  protocol,
  assets,
  runs,
  metricRecords,
  settings: {
    bootstrapDraws: 200,
    signFlipDraws: 200,
  },
});
assert.deepEqual(analysis.errors, []);
assert.equal(analysis.headline.length, 8);
assert(analysis.headline.every((result) => result.passed));
assert.equal(analysis.context.length, 4);
assert.equal(analysis.syntheticCoPrimaryGuardrails.length, 32);
assert.equal(analysis.syntheticGuardrails.length, 56);
assert.equal(analysis.realCoPrimaryGuardrails.length, 32);
assert.equal(analysis.realGuardrails.length, 56);
assert(analysis.absoluteGates.every((result) => result.passed));
assert.equal(analysis.decision.status, "passed");

const missing = analyzeMotionCourt({
  protocol,
  assets,
  runs: runs.slice(1),
  metricRecords,
  settings: {
    bootstrapDraws: 200,
    signFlipDraws: 200,
  },
});
assert.equal(missing.decision.status, "inadmissible");
assert(missing.errors.some((error) => error.includes("timing group")));

const lossRecords = structuredClone(metricRecords);
for (const record of lossRecords) {
  if (
    record.stratum === "synthetic" &&
    record.implementation === "reframe4s_motion"
  ) {
    record.metrics.corrected_image_nrmse = 0.09;
  }
}
const loss = analyzeMotionCourt({
  protocol,
  assets,
  runs,
  metricRecords: lossRecords,
  settings: {
    bootstrapDraws: 200,
    signFlipDraws: 200,
  },
});
assert.equal(loss.decision.status, "failed");
assert(
  loss.headline.some(
    (result) =>
      result.metric === "corrected_image_nrmse" && !result.passed,
  ),
);

console.log("motion court analysis tests: ok");
