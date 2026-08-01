#!/usr/bin/env node

import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { resolve } from "node:path";

import {
  scoreShard,
  scoringRowsForShard,
} from "./score-motion-shard.mjs";

const outputRoot = mkdtempSync(resolve(tmpdir(), "motion-shard-score-"));
const definition = {
  retention: {
    designatedAccuracyOutput: {
      phase: "measured",
      repetition: 0,
      allocatedCoreCount: 4,
    },
  },
};
function executionRow(name, phase = "measured") {
  return {
    rowId: name.repeat(64).slice(0, 64),
    value: {
      phase,
      repetition: "0",
      allocated_core_count: "4",
      output_directory: resolve(outputRoot, name),
    },
  };
}
const successRow = executionRow("a");
const failureRow = executionRow("b");
const warmupRow = executionRow("c", "warmup");
const context = {
  outputRoot,
  shard: { id: "shard-0000" },
  definition,
  rows: [successRow, failureRow, warmupRow],
};
function scoringRow(row) {
  return {
    run_record: resolve(row.value.output_directory, "run.json"),
    output: resolve(row.value.output_directory, "metrics.json"),
  };
}
const scoringRows = [scoringRow(successRow), scoringRow(failureRow)];
assert.deepEqual(
  scoringRowsForShard(context, scoringRows),
  scoringRows,
);
assert.throws(
  () => scoringRowsForShard(context, scoringRows.slice(1)),
  /has 1 rows, expected 2/,
);
assert.throws(
  () =>
    scoringRowsForShard(context, [
      scoringRows[0],
      scoringRows[0],
    ]),
  /duplicate shard scoring row/,
);

const scored = new Set();
let runnerCalls = 0;
const inspect = (row) => {
  if (row === scoringRows[1]) {
    return { successful: false, metric: null };
  }
  return {
    successful: true,
    metric: scored.has(row.output) ? {} : null,
  };
};
const first = await scoreShard(
  context,
  scoringRows,
  {},
  async (_path, pending) => {
    runnerCalls += 1;
    assert.deepEqual(pending, [scoringRows[0]]);
    pending.forEach((row) => scored.add(row.output));
  },
  {
    validateCheckpoint: () => {},
    validateRow: inspect,
  },
);
assert.deepEqual(first, {
  selected: 2,
  successful: 1,
  failed: 1,
  newlyScored: 1,
});
const second = await scoreShard(
  context,
  scoringRows,
  {},
  async () => {
    throw new Error("idempotent scoring must not rerun");
  },
  {
    validateCheckpoint: () => {},
    validateRow: inspect,
  },
);
assert.equal(second.newlyScored, 0);
assert.equal(runnerCalls, 1);

console.log("motion shard scoring tests: ok");
