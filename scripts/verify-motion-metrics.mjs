#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const sha256 = /^[0-9a-f]{64}$/;
const gitRevision = /^[0-9a-f]{40}$/;
const implementations = new Set([
  "reframe4s_motion",
  "afni_3dvolreg",
  "nifreeze",
  "fsl_mcflirt",
]);
const courts = new Set([
  "common_resampler_estimation",
  "native_end_to_end",
]);
const summaryFields = [
  "physical_landmark_displacement_p95_mm",
  "corrected_image_nrmse",
  "relative_rotation_error_p95_degrees",
  "framewise_displacement_error_p95_mm",
  "boundary_shell_nrmse",
  "temporal_difference_nrmse",
  "edge_energy_log_error",
  "ringing_fraction",
];
const frameNullableFields = [
  "corrected_image_nrmse",
  "boundary_shell_nrmse",
  "temporal_difference_nrmse",
  "edge_energy_log_error",
  "ringing_fraction",
];

function finiteNonnegative(value) {
  return typeof value === "number" && Number.isFinite(value) && value >= 0;
}

function hashFile(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

function exactKeys(value, required, context) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return [`${context} must be an object`];
  }
  const actual = Object.keys(value).sort();
  const expected = [...required].sort();
  return actual.length === expected.length &&
    actual.every((name, index) => name === expected[index])
    ? []
    : [`${context} fields differ from the registered schema`];
}

export function validateMetricRecord({
  metric,
  run,
  scoringRow,
  lock,
  runRecordSha256,
}) {
  const errors = [];
  const topFields = [
    "schemaVersion",
    "definitionVersion",
    "protocolSha256",
    "scorerRevision",
    "runRecordSha256",
    "subject",
    "scenario",
    "stratum",
    "court",
    "implementation",
    "phase",
    "repetition",
    "allocatedCoreCount",
    "frames",
    "metrics",
  ];
  errors.push(...exactKeys(metric, topFields, "metric record"));
  if (metric.schemaVersion !== "reframe4s.motion-superiority.metrics/v1") {
    errors.push("metric record schemaVersion is invalid");
  }
  if (metric.definitionVersion !== "motion-metrics-v1") {
    errors.push("metric definition version is invalid");
  }
  if (
    !sha256.test(metric.protocolSha256 ?? "") ||
    metric.protocolSha256 !== lock.protocol?.sha256
  ) {
    errors.push("metric protocol hash differs from the admission lock");
  }
  if (
    !gitRevision.test(metric.scorerRevision ?? "") ||
    metric.scorerRevision !== lock.candidate?.revision
  ) {
    errors.push("metric scorer revision differs from the candidate lock");
  }
  if (
    metric.runRecordSha256 !== runRecordSha256 ||
    !sha256.test(metric.runRecordSha256 ?? "")
  ) {
    errors.push("metric run-record hash differs from the linked raw row");
  }
  for (const field of [
    "subject",
    "scenario",
    "court",
    "implementation",
    "phase",
    "repetition",
    "allocatedCoreCount",
  ]) {
    const runField =
      field === "allocatedCoreCount" ? "allocatedCoreCount" : field;
    if (metric[field] !== run[runField]) {
      errors.push(`metric ${field} differs from the linked raw row`);
    }
  }
  for (const field of [
    "subject",
    "scenario",
    "stratum",
    "court",
    "implementation",
    "phase",
    "repetition",
  ]) {
    const expected =
      field === "repetition"
        ? Number(scoringRow[field])
        : scoringRow[field];
    if (metric[field] !== expected) {
      errors.push(`metric ${field} differs from the scoring plan`);
    }
  }
  if (metric.allocatedCoreCount !== Number(scoringRow.allocated_core_count)) {
    errors.push("metric allocatedCoreCount differs from the scoring plan");
  }
  if (!courts.has(metric.court)) errors.push("metric court is invalid");
  if (!implementations.has(metric.implementation)) {
    errors.push("metric implementation is invalid");
  }
  if (
    metric.phase !== "measured" ||
    metric.repetition !== 0 ||
    metric.allocatedCoreCount !== 4
  ) {
    errors.push("metric record is not the registered primary accuracy run");
  }
  if (
    run.termination !== "success" ||
    run.exitStatus !== 0 ||
    run.poseSha256 === null ||
    run.correctedImageSha256 === null
  ) {
    errors.push("metric record is linked to an unsuccessful raw run");
  }
  if (!Array.isArray(metric.frames) || metric.frames.length < 2) {
    errors.push("metric frames are absent or incomplete");
  } else {
    for (const [index, frame] of metric.frames.entries()) {
      const frameFields = [
        "frame",
        "physical_landmark_displacement_p95_mm",
        "relative_rotation_error_degrees",
        "framewise_displacement_error_mm",
        ...frameNullableFields,
      ];
      errors.push(...exactKeys(frame, frameFields, `metric frame ${index}`));
      if (frame.frame !== index) {
        errors.push(`metric frame ${index} is not consecutive`);
      }
      for (const field of [
        "physical_landmark_displacement_p95_mm",
        "relative_rotation_error_degrees",
      ]) {
        if (!finiteNonnegative(frame[field])) {
          errors.push(`metric frame ${index} ${field} is not finite`);
        }
      }
      const nullable = [
        "framewise_displacement_error_mm",
        ...frameNullableFields,
      ];
      for (const field of nullable) {
        const expectedNull = index === 0;
        if (
          (expectedNull && frame[field] !== null) ||
          (!expectedNull && !finiteNonnegative(frame[field]))
        ) {
          errors.push(
            `metric frame ${index} ${field} violates the reference-frame contract`,
          );
        }
      }
    }
  }
  errors.push(...exactKeys(metric.metrics, summaryFields, "metric summary"));
  for (const field of summaryFields) {
    if (!finiteNonnegative(metric.metrics?.[field])) {
      errors.push(`metric summary ${field} is not finite`);
    }
  }
  return errors;
}

export function readScoringPlan(path) {
  const lines = readFileSync(path, "utf8").trimEnd().split(/\r?\n/);
  const names = lines.shift().split("\t");
  return lines.map((line) => {
    const fields = line.split("\t");
    return Object.fromEntries(
      names.map((name, index) => [name, fields[index]]),
    );
  });
}

export function validateMetricPlan(scoringRows) {
  const errors = [];
  const keys = new Set();
  for (const row of scoringRows) {
    const key = [
      row.subject,
      row.scenario,
      row.court,
      row.implementation,
    ].join("\u0000");
    if (keys.has(key)) errors.push(`duplicate scoring-plan row ${key}`);
    keys.add(key);
  }
  return errors;
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
  const lockPath = option(arguments_, "--lock");
  const lock = JSON.parse(readFileSync(lockPath, "utf8"));
  const rows = readScoringPlan(planPath);
  const errors = validateMetricPlan(rows);
  for (const row of rows) {
    const metricPath = resolve(row.output);
    const runPath = resolve(row.run_record);
    try {
      const metric = JSON.parse(readFileSync(metricPath, "utf8"));
      const run = JSON.parse(readFileSync(runPath, "utf8"));
      errors.push(
        ...validateMetricRecord({
          metric,
          run,
          scoringRow: row,
          lock,
          runRecordSha256: hashFile(runPath),
        }).map((error) => `${metricPath}: ${error}`),
      );
    } catch (error) {
      errors.push(`${metricPath}: ${String(error?.message ?? error)}`);
    }
  }
  if (errors.length > 0) {
    for (const error of errors) process.stderr.write(`${error}\n`);
    process.exitCode = 1;
  } else {
    process.stdout.write(
      `motion metric records: ok (${rows.length} primary rows)\n`,
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
