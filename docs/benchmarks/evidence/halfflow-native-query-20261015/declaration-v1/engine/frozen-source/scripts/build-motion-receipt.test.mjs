#!/usr/bin/env node

import assert from "node:assert/strict";

import {
  buildReceipt,
  renderAudit,
} from "./build-motion-receipt.mjs";

const hash = "a".repeat(64);
const revision = "b".repeat(40);
const protocol = {
  version: "2.0.0",
  protocolId: "motion-superiority-v2",
  claim: {
    acceptedLanguage: "accepted",
    unmetLanguage: "unmet",
  },
  execution: {
    randomization: { seed: 6201 },
  },
};
const lock = {
  schemaVersion: "reframe4s.motion-superiority.lock/v2",
  candidate: {
    revision,
    artifactSha256: hash,
  },
  environmentLockSha256: hash,
  dataLockSha256: hash,
  hardware: { hostId: "host" },
  implementations: [],
  workers: [],
  runner: {},
};
const baseAnalysis = {
  settings: {},
  courtMapping: {},
  headline: [],
  context: [],
  syntheticCoPrimaryGuardrails: [],
  syntheticGuardrails: [],
  realCoPrimaryGuardrails: [],
  realGuardrails: [],
  absoluteGates: [],
  implementationFailureGates: [],
  memory: [],
  errors: [],
  decision: {
    admissible: true,
    passed: true,
    status: "passed",
  },
};
const receipt = buildReceipt({
  protocol,
  protocolSha256: hash,
  lock,
  assetsSha256: hash,
  planSha256: hash,
  scoringPlanSha256: hash,
  executionAggregateSha256: hash,
  runs: [],
  metricRecords: [],
  analysis: baseAnalysis,
});
assert.equal(receipt.claimDecision.status, "passed");
assert.equal(receipt.permittedLanguage, "accepted");
assert(renderAudit(receipt).includes("Decision: **passed**"));

const failed = buildReceipt({
  protocol,
  protocolSha256: hash,
  lock,
  assetsSha256: hash,
  planSha256: hash,
  scoringPlanSha256: hash,
  executionAggregateSha256: hash,
  runs: [],
  metricRecords: [],
  analysis: {
    ...baseAnalysis,
    headline: [
      {
        comparator: "nifreeze",
        metric: "corrected_image_nrmse",
        passed: false,
      },
    ],
    decision: {
      admissible: true,
      passed: false,
      status: "failed",
    },
  },
});
assert.equal(failed.permittedLanguage, "unmet");
assert.equal(failed.failures.visibleLosses.length, 1);

const inadmissible = buildReceipt({
  protocol,
  protocolSha256: hash,
  lock,
  assetsSha256: hash,
  planSha256: hash,
  scoringPlanSha256: hash,
  executionAggregateSha256: hash,
  runs: [],
  metricRecords: [],
  analysis: {
    ...baseAnalysis,
    errors: ["missing comparator"],
    decision: {
      admissible: false,
      passed: false,
      status: "inadmissible",
    },
  },
});
assert(inadmissible.permittedLanguage.startsWith("No motion superiority"));

console.log("motion receipt tests: ok");
