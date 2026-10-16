#!/usr/bin/env node

import assert from "node:assert/strict";

import { receiptErrors } from "./verify-motion-receipt.mjs";

const receipt = {
  claimDecision: {
    passed: true,
    completePassRuleSatisfied: true,
    status: "passed",
  },
  hypotheses: {
    headline: [{ passed: true }],
  },
  failures: {
    analysisErrors: [],
  },
};
assert.deepEqual(receiptErrors(receipt, structuredClone(receipt)), []);

const tampered = structuredClone(receipt);
tampered.claimDecision.passed = false;
assert(
  receiptErrors(tampered, receipt).some((error) =>
    error.includes("fresh deterministic"),
  ),
);

const hiddenLoss = structuredClone(receipt);
hiddenLoss.hypotheses.headline[0].passed = false;
assert(
  receiptErrors(hiddenLoss, hiddenLoss).some((error) =>
    error.includes("failed headline"),
  ),
);

console.log("motion receipt validator tests: ok");
