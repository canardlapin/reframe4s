#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

import {
  readPlan,
  validateCoverage,
  validateRunRecord,
} from "./verify-motion-run-records.mjs";

const stageNames = [
  "decodeSeconds",
  "prepareSeconds",
  "estimateSeconds",
  "applySeconds",
  "encodeSeconds",
  "reportSeconds",
];

function orderedQuantile(values, probability) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((left, right) => left - right);
  if (probability === 0.5 && sorted.length % 2 === 0) {
    const upper = sorted.length / 2;
    return (sorted[upper - 1] + sorted[upper]) / 2;
  }
  const index = Math.max(0, Math.ceil(probability * sorted.length) - 1);
  return sorted[index];
}

function groupKey(record) {
  return [
    record.subject,
    record.scenario,
    record.court,
    record.implementation,
    record.phase,
    record.allocatedCoreCount,
    record.requestedWorkerCount,
    record.effectiveWorkerCount,
    record.effectiveWorkerEvidence,
    record.referencePolicy,
  ].join("\u001f");
}

export function summarizeRecords(records, planSha256) {
  const protocolHashes = [...new Set(records.map((record) => record.protocolSha256))];
  if (protocolHashes.length !== 1) {
    throw new Error(
      `expected one protocol hash, found ${protocolHashes.length}`,
    );
  }
  const grouped = new Map();
  for (const record of records) {
    const key = groupKey(record);
    const rows = grouped.get(key) ?? [];
    rows.push(record);
    grouped.set(key, rows);
  }
  const groups = [...grouped.values()]
    .map((rows) => {
      const first = rows[0];
      const successful = rows.filter((row) => row.termination === "success");
      const stageSummary = Object.fromEntries(
        stageNames.map((name) => {
          const values = successful
            .map((row) => row.stageTimes[name])
            .filter((value) => value !== null);
          return [
            name,
            {
              observedCount: values.length,
              medianSeconds: orderedQuantile(values, 0.5),
              p95Seconds: orderedQuantile(values, 0.95),
            },
          ];
        }),
      );
      const elapsed = successful.map(
        (row) => row.stageTimes.endToEndSeconds,
      );
      const rss = successful
        .map((row) => row.peakRssBytes)
        .filter((value) => value !== null);
      const outputBytes = successful.map(
        (row) => row.materializedOutputBytes,
      );
      return {
        subject: first.subject,
        scenario: first.scenario,
        court: first.court,
        implementation: first.implementation,
        phase: first.phase,
        allocatedCoreCount: first.allocatedCoreCount,
        requestedWorkerCount: first.requestedWorkerCount,
        effectiveWorkerCount: first.effectiveWorkerCount,
        effectiveWorkerEvidence: first.effectiveWorkerEvidence,
        referencePolicy: first.referencePolicy,
        plannedCount: rows.length,
        successCount: successful.length,
        failureCount: rows.length - successful.length,
        medianEndToEndSeconds: orderedQuantile(elapsed, 0.5),
        p95EndToEndSeconds: orderedQuantile(elapsed, 0.95),
        peakRssObservedCount: rss.length,
        p95PeakRssBytes: orderedQuantile(rss, 0.95),
        p95MaterializedOutputBytes: orderedQuantile(outputBytes, 0.95),
        stages: stageSummary,
      };
    })
    .sort((left, right) =>
      [
        left.subject,
        left.scenario,
        left.court,
        left.implementation,
        left.phase,
        left.allocatedCoreCount,
      ]
        .join("\u001f")
        .localeCompare(
          [
            right.subject,
            right.scenario,
            right.court,
            right.implementation,
            right.phase,
            right.allocatedCoreCount,
          ].join("\u001f"),
        ),
    );
  return {
    schemaVersion: "reframe4s.motion-superiority.aggregate/v1",
    protocolSha256: protocolHashes[0],
    planSha256,
    runCount: records.length,
    successCount: records.filter((record) => record.termination === "success")
      .length,
    failureCount: records.filter((record) => record.termination !== "success")
      .length,
    groups,
  };
}

function csvCell(value) {
  const text = value === null ? "" : String(value);
  return `"${text.replaceAll('"', '""')}"`;
}

export function aggregateCsv(aggregate) {
  const header = [
    "subject",
    "scenario",
    "court",
    "implementation",
    "phase",
    "allocated_core_count",
    "requested_worker_count",
    "effective_worker_count",
    "effective_worker_evidence",
    "reference_policy",
    "planned_count",
    "success_count",
    "failure_count",
    "median_end_to_end_seconds",
    "p95_end_to_end_seconds",
    "peak_rss_observed_count",
    "p95_peak_rss_bytes",
    "p95_materialized_output_bytes",
  ];
  const rows = aggregate.groups.map((group) =>
    [
      group.subject,
      group.scenario,
      group.court,
      group.implementation,
      group.phase,
      group.allocatedCoreCount,
      group.requestedWorkerCount,
      group.effectiveWorkerCount,
      group.effectiveWorkerEvidence,
      group.referencePolicy,
      group.plannedCount,
      group.successCount,
      group.failureCount,
      group.medianEndToEndSeconds,
      group.p95EndToEndSeconds,
      group.peakRssObservedCount,
      group.p95PeakRssBytes,
      group.p95MaterializedOutputBytes,
    ]
      .map(csvCell)
      .join(","),
  );
  return `${[header.join(","), ...rows].join("\n")}\n`;
}

function option(arguments_, name) {
  const index = arguments_.indexOf(name);
  if (index < 0 || index + 1 >= arguments_.length) {
    throw new Error(`missing required option ${name}`);
  }
  return arguments_[index + 1];
}

function main(arguments_) {
  const planPath = option(arguments_, "--plan");
  const outputJson = option(arguments_, "--output-json");
  const outputCsv = option(arguments_, "--output-csv");
  const admission = arguments_.includes("--admission");
  const planText = readFileSync(planPath, "utf8");
  const plan = readPlan(planPath);
  const paths = plan.map((row) => join(row.output_directory, "run.json"));
  const records = paths.map((path) => JSON.parse(readFileSync(path, "utf8")));
  const errors = records.flatMap((record, index) =>
    validateRunRecord(record, admission).map(
      (error) => `${paths[index]}: ${error}`,
    ),
  );
  errors.push(...validateCoverage(records, plan));
  if (errors.length > 0) {
    throw new Error(errors.join("\n"));
  }
  const planSha256 = createHash("sha256").update(planText).digest("hex");
  const aggregate = summarizeRecords(records, planSha256);
  writeFileSync(outputJson, `${JSON.stringify(aggregate, null, 2)}\n`);
  writeFileSync(outputCsv, aggregateCsv(aggregate));
  process.stdout.write(
    `summarized ${aggregate.runCount} rows with ${aggregate.failureCount} failures\n`,
  );
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
