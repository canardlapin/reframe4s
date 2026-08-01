#!/usr/bin/env node

import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

import { readPlan } from "./verify-motion-run-records.mjs";

const header = [
  "subject",
  "scenario",
  "stratum",
  "court",
  "implementation",
  "phase",
  "repetition",
  "allocated_core_count",
  "reference_index",
  "run_record",
  "corrected",
  "poses",
  "truth",
  "truth_poses",
  "mask",
  "landmarks",
  "output",
];

function assetKey(value) {
  return `${value.subject}\u0000${value.scenario}`;
}

function executionKey(value) {
  return [
    value.subject,
    value.scenario,
    value.court,
    value.implementation,
  ].join("\u0000");
}

export function buildScoringRows(planRows, manifest, manifestPath) {
  const errors = [];
  const assetBase = dirname(resolve(manifestPath));
  const assets = new Map(
    (manifest.assets ?? []).map((asset) => [assetKey(asset), asset]),
  );
  const selected = planRows.filter(
    (row) =>
      row.phase === "measured" &&
      row.repetition === "0" &&
      row.allocated_core_count === "4",
  );
  const seen = new Set();
  const rows = [];
  for (const row of selected) {
    const key = executionKey(row);
    if (seen.has(key)) {
      errors.push(`duplicate primary scoring row ${key}`);
      continue;
    }
    seen.add(key);
    const asset = assets.get(assetKey(row));
    if (!asset) {
      errors.push(`primary scoring row ${key} has no locked asset`);
      continue;
    }
    const outputDirectory = resolve(row.output_directory);
    const assetPath = (field) => resolve(assetBase, asset[field].path);
    rows.push({
      subject: row.subject,
      scenario: row.scenario,
      stratum: asset.stratum,
      court: row.court,
      implementation: row.implementation,
      phase: row.phase,
      repetition: row.repetition,
      allocated_core_count: row.allocated_core_count,
      reference_index: row.reference_index,
      run_record: resolve(outputDirectory, "run.json"),
      corrected: resolve(outputDirectory, "corrected.nii.gz"),
      poses: resolve(outputDirectory, "poses.csv"),
      truth: assetPath("motionFreeTruth"),
      truth_poses: assetPath("truthPoses"),
      mask: assetPath("mask"),
      landmarks: assetPath("landmarks"),
      output: resolve(outputDirectory, "metrics.json"),
    });
  }
  const expected = (manifest.assets ?? []).length * 2 * 4;
  if (rows.length !== expected) {
    errors.push(
      `primary scoring plan has ${rows.length} rows, expected ${expected}`,
    );
  }
  return { rows, errors };
}

export function encodeScoringPlan(rows) {
  const lines = rows.map((row) =>
    header.map((name) => row[name]).join("\t"),
  );
  return `${[header.join("\t"), ...lines].join("\n")}\n`;
}

function option(arguments_, name) {
  const index = arguments_.indexOf(name);
  if (index < 0 || index + 1 >= arguments_.length) {
    throw new Error(`${name} is required`);
  }
  return arguments_[index + 1];
}

function main(arguments_) {
  const planPath = option(arguments_, "--plan");
  const assetsPath = option(arguments_, "--assets");
  const outputPath = option(arguments_, "--output");
  const manifest = JSON.parse(readFileSync(assetsPath, "utf8"));
  const result = buildScoringRows(readPlan(planPath), manifest, assetsPath);
  if (result.errors.length > 0) {
    for (const error of result.errors) process.stderr.write(`${error}\n`);
    process.exitCode = 1;
  } else {
    writeFileSync(outputPath, encodeScoringPlan(result.rows), {
      encoding: "utf8",
      flag: "wx",
    });
    process.stdout.write(
      `motion scoring plan: wrote ${result.rows.length} rows\n`,
    );
  }
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  try {
    main(process.argv.slice(2));
  } catch (error) {
    process.stderr.write(`${String(error?.message ?? error)}\n`);
    process.exitCode = 1;
  }
}
