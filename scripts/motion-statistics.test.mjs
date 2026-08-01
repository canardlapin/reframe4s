#!/usr/bin/env node

import assert from "node:assert/strict";

import {
  SplitMix64,
  holmAdjust,
  mean,
  oneSidedSignFlipPValue,
  pairedContrast,
  quantile,
  sampleStandardDeviation,
  studentizedLowerBound,
} from "./motion-statistics.mjs";

assert.equal(mean([1, 2, 3]), 2);
assert.equal(sampleStandardDeviation([1, 2, 3]), 1);
assert.equal(quantile([0, 10], 0.25), 2.5);

const first = new SplitMix64(7301);
const second = new SplitMix64(7301);
assert.deepEqual(
  Array.from({ length: 10 }, () => first.nextUint64()),
  Array.from({ length: 10 }, () => second.nextUint64()),
);

const values = Array.from({ length: 24 }, (_, index) => 0.2 + index * 0.002);
const lower1 = studentizedLowerBound(values, 0.95, 1000, 7301);
const lower2 = studentizedLowerBound(values, 0.95, 1000, 7301);
assert.equal(lower1, lower2);
assert(lower1 < mean(values));
assert(lower1 > 0.19);

const p = oneSidedSignFlipPValue(values, 0.05, 10000, 7302);
assert(p < 0.01);

const adjusted = holmAdjust([
  { id: "a", pValue: 0.01 },
  { id: "b", pValue: 0.04 },
  { id: "c", pValue: 0.03 },
]);
assert.deepEqual(
  adjusted.map((entry) => entry.adjustedPValue),
  [0.03, 0.06, 0.06],
);

const contrast = pairedContrast({
  values,
  margin: 0.05,
  confidence: 0.95,
  bootstrapDraws: 1000,
  bootstrapSeed: 7301,
  signFlipDraws: 10000,
  signFlipSeed: 7302,
});
assert(contrast.lowerConfidenceBound > contrast.margin);
assert(contrast.pValue < 0.01);

console.log("motion statistics tests: ok");
