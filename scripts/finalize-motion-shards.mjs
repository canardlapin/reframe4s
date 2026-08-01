#!/usr/bin/env node

import {
  closeSync,
  existsSync,
  fsyncSync,
  linkSync,
  openSync,
  readFileSync,
  unlinkSync,
  writeFileSync,
} from "node:fs";
import { dirname, relative, resolve } from "node:path";
import { pathToFileURL } from "node:url";

import {
  isDesignatedAccuracyRow,
  parsePlanText,
  regularFile,
  schemas,
  sha256File,
  sha256Text,
} from "./motion-shard-contract.mjs";
import {
  loadShardContext,
  readCorrectedPointer,
  validateShardCheckpoint,
} from "./execute-motion-shard.mjs";

function option(arguments_, name) {
  const index = arguments_.indexOf(name);
  if (index < 0 || index + 1 >= arguments_.length) {
    throw new Error(`${name} is required`);
  }
  return arguments_[index + 1];
}

function forceDirectory(path) {
  const descriptor = openSync(path, "r");
  try {
    fsyncSync(descriptor);
  } finally {
    closeSync(descriptor);
  }
}

function atomicCreateJson(path, value) {
  const text = `${JSON.stringify(value, null, 2)}\n`;
  if (regularFile(path)) {
    if (readFileSync(path, "utf8") !== text) {
      throw new Error(`existing finalization differs: ${path}`);
    }
    return;
  }
  const temporary = `${path}.tmp-${process.pid}-${Date.now()}`;
  writeFileSync(temporary, text, { encoding: "utf8", flag: "wx" });
  const descriptor = openSync(temporary, "r");
  try {
    fsyncSync(descriptor);
  } finally {
    closeSync(descriptor);
  }
  try {
    linkSync(temporary, path);
  } finally {
    if (existsSync(temporary)) unlinkSync(temporary);
  }
  forceDirectory(dirname(path));
}

function metricFor(row, validation, protocolSha256) {
  const path = resolve(row.value.output_directory, "metrics.json");
  if (validation.record.termination !== "success") {
    return regularFile(path)
      ? { path, sha256: sha256File(path), required: false }
      : null;
  }
  if (!regularFile(path)) {
    throw new Error(`successful designated row lacks metrics: ${row.rowId}`);
  }
  const metric = JSON.parse(readFileSync(path, "utf8"));
  if (
    metric.schemaVersion !== "reframe4s.motion-superiority.metrics/v1" ||
    metric.protocolSha256 !== protocolSha256 ||
    metric.runRecordSha256 !== validation.runRecordSha256 ||
    metric.subject !== validation.record.subject ||
    metric.scenario !== validation.record.scenario ||
    metric.court !== validation.record.court ||
    metric.implementation !== validation.record.implementation ||
    metric.phase !== validation.record.phase ||
    metric.repetition !== validation.record.repetition ||
    metric.allocatedCoreCount !==
      validation.record.allocatedCoreCount
  ) {
    throw new Error(`designated metric linkage differs: ${path}`);
  }
  return { path, sha256: sha256File(path), required: true };
}

export function finalizeShardSet(manifestPath, releaseDesignated) {
  const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
  if (manifest.schemaVersion !== schemas.manifest) {
    throw new Error("shard manifest schemaVersion is invalid");
  }
  const masterRows = parsePlanText(
    readFileSync(manifest.masterPlanPath, "utf8"),
  ).rows;
  const seen = new Set();
  const checkpoints = [];
  const runRecords = [];
  const metrics = [];
  const objects = new Map();
  const designatedDirectFiles = new Set();
  let designatedRows = 0;
  let failures = 0;

  for (const shard of manifest.shards) {
    const context = loadShardContext(manifestPath, shard.id);
    const validated = validateShardCheckpoint(context);
    checkpoints.push(
      `${shard.id}:${sha256File(context.checkpointPath)}`,
    );
    failures += validated.checkpoint.failureCount;
    for (const completed of validated.completed) {
      const { row, validation } = completed;
      if (seen.has(row.rowId)) {
        throw new Error(`row appears in more than one shard: ${row.rowId}`);
      }
      seen.add(row.rowId);
      runRecords.push(`${row.rowId}:${validation.runRecordSha256}`);
      const designated = isDesignatedAccuracyRow(
        row,
        context.definition,
      );
      if (validation.record.correctedImageSha256 !== null) {
        const pointer = readCorrectedPointer(
          row,
          context.definition,
          context.outputRoot,
          validation.record.correctedImageSha256,
        );
        if (pointer === null) {
          throw new Error(`row lacks corrected pointer: ${row.rowId}`);
        }
        objects.set(
          pointer.pointer.sha256,
          {
            sha256: pointer.pointer.sha256,
            bytes: pointer.pointer.bytes,
            path: relative(context.outputRoot, pointer.object),
          },
        );
        const direct = resolve(
          row.value.output_directory,
          "corrected.nii.gz",
        );
        if (!designated && regularFile(direct)) {
          throw new Error(
            `non-designated corrected file remains: ${row.rowId}`,
          );
        }
        if (designated) {
          designatedRows += 1;
          const metric = metricFor(
            row,
            validation,
            context.manifest.protocolSha256,
          );
          if (metric !== null) {
            metrics.push(`${row.rowId}:${metric.sha256}`);
          }
          if (regularFile(direct)) {
            designatedDirectFiles.add(direct);
          } else if (!releaseDesignated) {
            throw new Error(
              `designated corrected hard link is missing: ${row.rowId}`,
            );
          }
        }
      }
    }
  }

  if (seen.size !== masterRows.length) {
    throw new Error(
      `checkpoint coverage has ${seen.size} rows, expected ${masterRows.length}`,
    );
  }
  for (const row of masterRows) {
    if (!seen.has(row.rowId)) {
      throw new Error(`master-plan row is absent from checkpoints: ${row.rowId}`);
    }
  }
  if (releaseDesignated) {
    for (const direct of [...designatedDirectFiles].sort()) {
      unlinkSync(direct);
      forceDirectory(dirname(direct));
    }
  }
  const sortedObjects = [...objects.values()].sort((left, right) =>
    left.sha256.localeCompare(right.sha256),
  );
  return {
    schemaVersion: schemas.finalization,
    releaseExecutionSha256: manifest.releaseExecutionSha256,
    protocolSha256: manifest.protocolSha256,
    masterPlanSha256: manifest.masterPlanSha256,
    shardManifestSha256: sha256File(manifestPath),
    rowCount: seen.size,
    failureCount: failures,
    shardCount: checkpoints.length,
    checkpointSetSha256: sha256Text(checkpoints.sort().join("\n")),
    runRecordSetSha256: sha256Text(runRecords.sort().join("\n")),
    designatedMetricCount: metrics.length,
    designatedMetricSetSha256: sha256Text(metrics.sort().join("\n")),
    correctedObjectCount: sortedObjects.length,
    correctedObjectBytes: sortedObjects.reduce(
      (total, object) => total + object.bytes,
      0,
    ),
    correctedObjectSetSha256: sha256Text(
      sortedObjects
        .map((object) => `${object.sha256}:${object.bytes}:${object.path}`)
        .join("\n"),
    ),
    designatedHardLinksReleased: releaseDesignated,
    releasedDirectFileCount: releaseDesignated ? designatedRows : 0,
  };
}

function main(arguments_) {
  const manifestPath = resolve(option(arguments_, "--manifest"));
  const outputPath = resolve(option(arguments_, "--output"));
  if (!arguments_.includes("--release-designated")) {
    throw new Error(
      "--release-designated is required after scoring and receipt construction",
    );
  }
  const finalization = finalizeShardSet(manifestPath, true);
  atomicCreateJson(outputPath, finalization);
  process.stdout.write(
    `motion shard finalization: ${finalization.rowCount} rows, ` +
      `${finalization.shardCount} shards, ` +
      `${finalization.correctedObjectCount} corrected objects\n`,
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
