#!/usr/bin/env node

import assert from "node:assert/strict";

import {
  encodeWorkloads,
  workloadRows,
} from "./prepare-motion-workloads.mjs";

const manifest = {
  assets: [
    {
      subject: "b",
      scenario: "s",
      input: { path: "b/input.nii.gz" },
      mask: { path: "b/mask.nii.gz" },
    },
    {
      subject: "a",
      scenario: "s",
      input: { path: "a/input.nii.gz" },
      mask: { path: "a/mask.nii.gz" },
    },
  ],
};
const rows = workloadRows(manifest, "/locked/assets.json");
assert.deepEqual(rows.map((row) => row.subject), ["a", "b"]);
assert.equal(rows[0].input, "/locked/a/input.nii.gz");
assert(encodeWorkloads(rows).startsWith("subject\tscenario\tinput\tmask\n"));

console.log("motion workload tests: ok");
