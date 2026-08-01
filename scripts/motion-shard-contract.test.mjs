#!/usr/bin/env node

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import {
  balancedBlocks,
  buildShardEntries,
  encodePlanRows,
  parsePlanText,
  planHeader,
  releaseExecutionErrors,
} from "./motion-shard-contract.mjs";

const registered = JSON.parse(
  readFileSync("benchmarks/motion/release-execution-v1.json", "utf8"),
);
assert.deepEqual(releaseExecutionErrors(registered), []);

function fixtureDefinition() {
  const definition = structuredClone(registered);
  definition.masterPlan.expectedRows = 8;
  definition.sharding.balancedBlocksPerShard = 1;
  definition.sharding.expectedShards = 2;
  return definition;
}

function planRow(block, order, implementation) {
  const values = {
    subject: `sub-${block}`,
    scenario: "clean",
    input: "/assets/input.nii.gz",
    mask: "/assets/mask.nii.gz",
    court: "common_resampler_estimation",
    implementation,
    phase: "measured",
    repetition: "0",
    order: String(order),
    allocated_core_count: "4",
    requested_worker_count: "4",
    effective_worker_count:
      implementation === "nifreeze" ? "4" : "1",
    effective_worker_evidence:
      implementation === "reframe4s_motion"
        ? "implementation_contract"
        : implementation === "nifreeze"
          ? "locked_adapter_configuration"
          : "locked_tool_probe",
    reference_policy: "fixed_frame_0",
    reference_index: "0",
    timeout_seconds: "60",
    output_directory: `/results/${block}/${implementation}`,
  };
  return planHeader.map((name) => values[name]).join("\t");
}

const implementations = registered.masterPlan.requiredImplementations;
const text = `${
  planHeader.join("\t")
}\n${[0, 1]
  .flatMap((block) =>
    implementations.map((implementation, order) =>
      planRow(block, order, implementation),
    ),
  )
  .join("\n")}\n`;
const parsed = parsePlanText(text);
assert.equal(parsed.rows.length, 8);
assert.equal(new Set(parsed.rows.map((row) => row.rowId)).size, 8);
assert.equal(encodePlanRows(parsed.rows), text);
assert.equal(balancedBlocks(parsed.rows, fixtureDefinition()).length, 2);

const shards = buildShardEntries(parsed.rows, fixtureDefinition());
assert.equal(shards.length, 2);
assert.equal(shards[0].id, "shard-0000");
assert.equal(shards[0].rowCount, 4);
assert.equal(shards[1].firstRowIndex, 4);

const missing = parsed.rows.filter(
  (row) => row.value.implementation !== "fsl_mcflirt",
);
assert.throws(
  () => balancedBlocks(missing, fixtureDefinition()),
  /has 6 rows, expected 8/,
);

const mixed = parsed.rows.map((row) => structuredClone(row));
mixed[3].value.subject = "changed";
assert.throws(
  () => balancedBlocks(mixed, fixtureDefinition()),
  /mixes run contexts/,
);

const duplicate = parsed.rows.map((row) => structuredClone(row));
duplicate[3].value.implementation = "nifreeze";
assert.throws(
  () => balancedBlocks(duplicate, fixtureDefinition()),
  /lacks a required implementation/,
);

const unsafe = structuredClone(registered);
unsafe.resume.completedRunRecordRetry = "allowed";
unsafe.sharding.executeSequentiallyOnOneHost = false;
const unsafeErrors = releaseExecutionErrors(unsafe);
assert(unsafeErrors.some((error) => error.includes("must be forbidden")));
assert(unsafeErrors.some((error) => error.includes("must be true")));

console.log("motion shard contract tests: ok");
