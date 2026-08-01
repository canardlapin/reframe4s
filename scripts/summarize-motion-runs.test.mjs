#!/usr/bin/env node

import assert from "node:assert/strict";

import {
  aggregateCsv,
  summarizeRecords,
} from "./summarize-motion-runs.mjs";

const hash = "a".repeat(64);

function record(repetition, elapsed, termination = "success") {
  return {
    schemaVersion: "reframe4s.motion-superiority.run/v2",
    protocolSha256: hash,
    subject: "sub-01",
    scenario: "clean",
    court: "common_resampler_estimation",
    implementation: "reframe4s_motion",
    phase: "measured",
    repetition,
    order: 0,
    allocatedCoreCount: 4,
    requestedWorkerCount: 4,
    effectiveWorkerCount: 1,
    effectiveWorkerEvidence: "implementation_contract",
    referencePolicy: "fixed_frame_0",
    termination,
    stageTimes: {
      source: "instrumented",
      decodeSeconds: 0.1,
      prepareSeconds: null,
      estimateSeconds: 0.2,
      applySeconds: null,
      encodeSeconds: null,
      reportSeconds: 0.01,
      endToEndSeconds: elapsed,
    },
    peakRssBytes: termination === "success" ? 1024 + repetition : null,
    materializedOutputBytes: 4096 + repetition,
  };
}

const aggregate = summarizeRecords(
  [
    record(0, 3),
    record(1, 1),
    record(2, 2),
    record(3, 9, "process_failure"),
  ],
  "b".repeat(64),
);
assert.equal(aggregate.runCount, 4);
assert.equal(aggregate.successCount, 3);
assert.equal(aggregate.failureCount, 1);
assert.equal(aggregate.groups.length, 1);
assert.equal(aggregate.groups[0].plannedCount, 4);
assert.equal(aggregate.groups[0].medianEndToEndSeconds, 2);
assert.equal(aggregate.groups[0].p95EndToEndSeconds, 3);
assert.equal(aggregate.groups[0].stages.prepareSeconds.observedCount, 0);
assert.equal(aggregate.groups[0].stages.estimateSeconds.observedCount, 3);

const csv = aggregateCsv(aggregate);
assert(csv.includes("effective_worker_count"));
assert(csv.includes('"process_failure"') === false);
assert(csv.includes('"4","3","1"'));

assert.throws(
  () =>
    summarizeRecords(
      [
        record(0, 1),
        { ...record(1, 1), protocolSha256: "c".repeat(64) },
      ],
      "b".repeat(64),
    ),
  /expected one protocol hash/,
);

console.log("motion aggregate tests: ok");
