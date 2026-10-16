#!/usr/bin/env node

import {
  closeSync,
  fsyncSync,
  mkdirSync,
  openSync,
  readFileSync,
  writeFileSync,
} from "node:fs";
import { resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { pathToFileURL } from "node:url";

import {
  encodeScoringPlan,
} from "./prepare-motion-scoring-plan.mjs";
import {
  readScoringPlan,
  validateMetricRecord,
} from "./verify-motion-metrics.mjs";
import {
  isDesignatedAccuracyRow,
  regularFile,
  sha256File,
} from "./motion-shard-contract.mjs";
import {
  loadShardContext,
  validateShardCheckpoint,
} from "./execute-motion-shard.mjs";

function option(arguments_, name) {
  const index = arguments_.indexOf(name);
  if (index < 0 || index + 1 >= arguments_.length) {
    throw new Error(`${name} is required`);
  }
  return arguments_[index + 1];
}

function scoringKey(path) {
  return resolve(path);
}

export function scoringRowsForShard(context, scoringRows) {
  const expected = new Map(
    context.rows
      .filter((row) => isDesignatedAccuracyRow(row, context.definition))
      .map((row) => [
        scoringKey(resolve(row.value.output_directory, "run.json")),
        row,
      ]),
  );
  const selected = scoringRows.filter((row) =>
    expected.has(scoringKey(row.run_record)),
  );
  if (selected.length !== expected.size) {
    throw new Error(
      `shard scoring coverage has ${selected.length} rows, expected ` +
        expected.size,
    );
  }
  const seen = new Set();
  for (const row of selected) {
    const key = scoringKey(row.run_record);
    if (seen.has(key)) {
      throw new Error(`duplicate shard scoring row: ${key}`);
    }
    seen.add(key);
  }
  return selected;
}

function validateOne(row, lock) {
  const runPath = resolve(row.run_record);
  const metricPath = resolve(row.output);
  const run = JSON.parse(readFileSync(runPath, "utf8"));
  if (run.termination !== "success") {
    return { successful: false, run, metric: null };
  }
  if (!regularFile(metricPath)) {
    return { successful: true, run, metric: null };
  }
  const metric = JSON.parse(readFileSync(metricPath, "utf8"));
  const errors = validateMetricRecord({
    metric,
    run,
    scoringRow: row,
    lock,
    runRecordSha256: sha256File(runPath),
  });
  if (errors.length > 0) {
    throw new Error(
      `existing shard metric is invalid ${metricPath}:\n- ` +
        errors.join("\n- "),
    );
  }
  return { successful: true, run, metric };
}

export async function scoreShard(
  context,
  scoringRows,
  lock,
  runPending,
  dependencies = {},
) {
  const verifyCheckpoint =
    dependencies.validateCheckpoint ?? validateShardCheckpoint;
  const inspect = dependencies.validateRow ?? validateOne;
  verifyCheckpoint(context);
  const selected = scoringRowsForShard(context, scoringRows);
  const pending = selected.filter((row) => {
    const state = inspect(row, lock);
    return state.successful && state.metric === null;
  });
  if (pending.length > 0) {
    const path = resolve(
      context.outputRoot,
      "state",
      `${context.shard.id}-scoring-${Date.now()}.tsv`,
    );
    mkdirSync(resolve(context.outputRoot, "state"), { recursive: true });
    writeFileSync(path, encodeScoringPlan(pending), {
      encoding: "utf8",
      flag: "wx",
    });
    const descriptor = openSync(path, "r");
    try {
      fsyncSync(descriptor);
    } finally {
      closeSync(descriptor);
    }
    await runPending(path, pending);
  }
  let successful = 0;
  let failed = 0;
  for (const row of selected) {
    const state = inspect(row, lock);
    if (state.successful) {
      successful += 1;
      if (state.metric === null) {
        throw new Error(`successful row remains unscored: ${row.output}`);
      }
    } else {
      failed += 1;
    }
  }
  return {
    selected: selected.length,
    successful,
    failed,
    newlyScored: pending.length,
  };
}

function productionScorer(lockPath, protocolPath) {
  return (pendingPath) => {
    const result = spawnSync(
      process.execPath,
      [
        resolve("scripts/invoke-motion-runner.mjs"),
        "--lock",
        lockPath,
        "--",
        "score-plan",
        "--plan",
        pendingPath,
        "--protocol",
        protocolPath,
      ],
      { stdio: "inherit" },
    );
    if (result.error) throw result.error;
    if (result.status !== 0) {
      throw new Error(`shard scoring runner exited ${result.status}`);
    }
  };
}

async function main(arguments_) {
  const manifestPath = resolve(option(arguments_, "--manifest"));
  const shardId = option(arguments_, "--shard");
  const scoringPlanPath = resolve(option(arguments_, "--scoring-plan"));
  const lockPath = resolve(option(arguments_, "--lock"));
  const context = loadShardContext(manifestPath, shardId);
  const lock = JSON.parse(readFileSync(lockPath, "utf8"));
  const result = await scoreShard(
    context,
    readScoringPlan(scoringPlanPath),
    lock,
    productionScorer(lockPath, context.manifest.protocolPath),
  );
  process.stdout.write(
    `motion shard scoring ${shardId}: ${result.successful} scored, ` +
      `${result.failed} failed raw rows, ${result.newlyScored} new metrics\n`,
  );
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  main(process.argv.slice(2)).catch((error) => {
    process.stderr.write(`${String(error?.message ?? error)}\n`);
    process.exitCode = 1;
  });
}
