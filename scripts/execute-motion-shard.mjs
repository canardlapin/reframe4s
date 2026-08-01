#!/usr/bin/env node

import {
  existsSync,
  linkSync,
  mkdirSync,
  openSync,
  closeSync,
  fsyncSync,
  readFileSync,
  renameSync,
  statSync,
  unlinkSync,
  writeFileSync,
} from "node:fs";
import { dirname, relative, resolve, sep } from "node:path";
import { spawnSync } from "node:child_process";
import { pathToFileURL } from "node:url";

import {
  encodePlanRows,
  forceFile,
  isDesignatedAccuracyRow,
  loadAndVerifyDefinition,
  parsePlanText,
  regularFile,
  schemas,
  sha256File,
  sha256Text,
} from "./motion-shard-contract.mjs";
import {
  validateCoverage,
  validateRunRecord,
} from "./verify-motion-run-records.mjs";

const artifactHashes = [
  ["stdout.txt", (record) => record.stdoutSha256],
  ["stderr.txt", (record) => record.stderrSha256],
  ["version-stdout.txt", (record) => record.tool.versionStdoutSha256],
  ["version-stderr.txt", (record) => record.tool.versionStderrSha256],
  ["poses.csv", (record) => record.poseSha256],
];

function option(arguments_, name, fallback) {
  const found = [];
  for (let index = 0; index < arguments_.length; index += 1) {
    if (arguments_[index] === name) {
      if (index + 1 >= arguments_.length) {
        throw new Error(`${name} requires a value`);
      }
      found.push(arguments_[index + 1]);
      index += 1;
    }
  }
  if (found.length > 1) throw new Error(`${name} may appear only once`);
  if (found.length === 0 && fallback === undefined) {
    throw new Error(`${name} is required`);
  }
  return found[0] ?? fallback;
}

function forceDirectory(path) {
  const descriptor = openSync(path, "r");
  try {
    fsyncSync(descriptor);
  } finally {
    closeSync(descriptor);
  }
}

function atomicWriteJson(path, value, replace = true) {
  mkdirSync(dirname(path), { recursive: true });
  const temporary = `${path}.tmp-${process.pid}-${Date.now()}`;
  writeFileSync(temporary, `${JSON.stringify(value, null, 2)}\n`, {
    encoding: "utf8",
    flag: "wx",
  });
  forceFile(temporary);
  if (replace) {
    renameSync(temporary, path);
  } else {
    try {
      linkSync(temporary, path);
    } finally {
      if (existsSync(temporary)) unlinkSync(temporary);
    }
  }
  forceDirectory(dirname(path));
}

function objectPath(outputRoot, definition, digest) {
  return resolve(
    outputRoot,
    definition.retention.correctedObjectDirectory,
    digest.slice(0, 2),
    digest,
  );
}

function ensureWithin(root, path, label) {
  const normalizedRoot = `${resolve(root)}${sep}`;
  const normalized = resolve(path);
  if (!normalized.startsWith(normalizedRoot)) {
    throw new Error(`${label} escapes the execution root`);
  }
  return normalized;
}

function verifyHash(path, expected, label) {
  if (!regularFile(path)) throw new Error(`${label} is missing: ${path}`);
  const actual = sha256File(path);
  if (actual !== expected) {
    throw new Error(`${label} hash differs: ${path}`);
  }
}

function pointerPath(row, definition) {
  return resolve(
    row.value.output_directory,
    definition.retention.pointerFile,
  );
}

export function readCorrectedPointer(
  row,
  definition,
  outputRoot,
  expectedHash,
) {
  const path = pointerPath(row, definition);
  if (!regularFile(path)) return null;
  const pointer = JSON.parse(readFileSync(path, "utf8"));
  if (
    pointer.schemaVersion !== schemas.pointer ||
    pointer.algorithm !== "sha256" ||
    pointer.sha256 !== expectedHash ||
    !Number.isInteger(pointer.bytes) ||
    pointer.bytes < 0 ||
    typeof pointer.object !== "string" ||
    pointer.designatedAccuracyOutput !==
      isDesignatedAccuracyRow(row, definition)
  ) {
    throw new Error(`invalid corrected-output pointer: ${path}`);
  }
  const object = ensureWithin(
    outputRoot,
    resolve(outputRoot, pointer.object),
    "corrected object",
  );
  const registered = objectPath(outputRoot, definition, expectedHash);
  if (object !== registered) {
    throw new Error(`corrected pointer has an unexpected object path: ${path}`);
  }
  verifyHash(object, expectedHash, "corrected object");
  if (statSync(object).size !== pointer.bytes) {
    throw new Error(`corrected object byte count differs: ${object}`);
  }
  return { path, pointer, object };
}

export function validateCompletedRow(row, definition, outputRoot) {
  const directory = resolve(row.value.output_directory);
  const runPath = resolve(directory, "run.json");
  if (!regularFile(runPath)) {
    throw new Error(`completed row lacks run.json: ${row.rowId}`);
  }
  const record = JSON.parse(readFileSync(runPath, "utf8"));
  const errors = validateRunRecord(record);
  errors.push(...validateCoverage([record], [row.value]));
  if (errors.length > 0) {
    throw new Error(
      `completed row ${row.rowId} is invalid:\n- ${errors.join("\n- ")}`,
    );
  }
  for (const [name, expected] of artifactHashes) {
    const digest = expected(record);
    if (digest !== null) {
      verifyHash(resolve(directory, name), digest, name);
    }
  }
  if (record.correctedImageSha256 !== null) {
    const corrected = resolve(directory, "corrected.nii.gz");
    const direct = regularFile(corrected);
    const pointer = readCorrectedPointer(
      row,
      definition,
      outputRoot,
      record.correctedImageSha256,
    );
    if (!direct && pointer === null) {
      const retained = objectPath(
        outputRoot,
        definition,
        record.correctedImageSha256,
      );
      verifyHash(
        retained,
        record.correctedImageSha256,
        "unregistered corrected object",
      );
    }
    if (direct) {
      verifyHash(
        corrected,
        record.correctedImageSha256,
        "corrected output",
      );
    }
  }
  return {
    record,
    runPath,
    runRecordSha256: sha256File(runPath),
  };
}

export function retainCorrectedOutput(
  row,
  record,
  definition,
  outputRoot,
) {
  const digest = record.correctedImageSha256;
  if (digest === null) return null;
  const directory = resolve(row.value.output_directory);
  const corrected = resolve(directory, "corrected.nii.gz");
  const target = objectPath(outputRoot, definition, digest);
  const designated = isDesignatedAccuracyRow(row, definition);
  let pointer = readCorrectedPointer(
    row,
    definition,
    outputRoot,
    digest,
  );

  if (pointer === null) {
    mkdirSync(dirname(target), { recursive: true });
    if (regularFile(target)) {
      verifyHash(target, digest, "existing corrected object");
      if (regularFile(corrected)) {
        verifyHash(corrected, digest, "corrected output");
        unlinkSync(corrected);
      }
    } else {
      verifyHash(corrected, digest, "corrected output");
      renameSync(corrected, target);
      forceFile(target);
      forceDirectory(dirname(target));
    }
    const value = {
      schemaVersion: schemas.pointer,
      algorithm: "sha256",
      sha256: digest,
      bytes: statSync(target).size,
      object: relative(outputRoot, target),
      designatedAccuracyOutput: designated,
    };
    atomicWriteJson(pointerPath(row, definition), value, false);
    pointer = {
      path: pointerPath(row, definition),
      pointer: value,
      object: target,
    };
  }

  if (designated) {
    if (!regularFile(corrected)) {
      linkSync(pointer.object, corrected);
      forceDirectory(directory);
    }
    verifyHash(corrected, digest, "designated corrected output");
  } else if (regularFile(corrected)) {
    verifyHash(corrected, digest, "non-designated corrected output");
    unlinkSync(corrected);
    forceDirectory(directory);
  }
  return pointer;
}

function attemptLedgerPath(outputRoot, row) {
  return resolve(outputRoot, "state", `${row.rowId}.json`);
}

function readAttemptLedger(outputRoot, row) {
  const path = attemptLedgerPath(outputRoot, row);
  if (!regularFile(path)) {
    return {
      schemaVersion: "reframe4s.motion.row-attempts/v1",
      rowId: row.rowId,
      quarantines: [],
    };
  }
  const value = JSON.parse(readFileSync(path, "utf8"));
  if (
    value.schemaVersion !== "reframe4s.motion.row-attempts/v1" ||
    value.rowId !== row.rowId ||
    !Array.isArray(value.quarantines)
  ) {
    throw new Error(`invalid row-attempt ledger: ${path}`);
  }
  return value;
}

export function quarantineIncompleteRow(
  row,
  definition,
  outputRoot,
) {
  const directory = resolve(row.value.output_directory);
  if (!existsSync(directory) || regularFile(resolve(directory, "run.json"))) {
    return;
  }
  const ledger = readAttemptLedger(outputRoot, row);
  const attempt = ledger.quarantines.length + 1;
  const target = resolve(
    outputRoot,
    "quarantine",
    row.rowId,
    `attempt-${String(attempt).padStart(2, "0")}`,
  );
  mkdirSync(dirname(target), { recursive: true });
  renameSync(directory, target);
  forceDirectory(dirname(target));
  const updated = {
    ...ledger,
    quarantines: [
      ...ledger.quarantines,
      {
        attempt,
        path: relative(outputRoot, target),
      },
    ],
  };
  atomicWriteJson(attemptLedgerPath(outputRoot, row), updated);
  if (attempt >= definition.resume.maximumAttemptsPerRow) {
    throw new Error(
      `row ${row.rowId} exhausted its registered attempt limit`,
    );
  }
}

function checkpointValue(context, completed) {
  const rows = completed.map(({ row, validation, pointer }) => ({
    rowId: row.rowId,
    runRecordSha256: validation.runRecordSha256,
    termination: validation.record.termination,
    correctedImageSha256:
      validation.record.correctedImageSha256,
    correctedPointerSha256:
      pointer === null ? null : sha256File(pointer.path),
  }));
  return {
    schemaVersion: schemas.checkpoint,
    releaseExecutionSha256: context.manifest.releaseExecutionSha256,
    protocolSha256: context.manifest.protocolSha256,
    masterPlanSha256: context.manifest.masterPlanSha256,
    shardId: context.shard.id,
    shardPlanSha256: context.shard.planSha256,
    rowCount: rows.length,
    failureCount: rows.filter((row) => row.termination !== "success").length,
    rows,
  };
}

export function loadShardContext(manifestPath, shardId) {
  const resolvedManifest = resolve(manifestPath);
  const manifest = JSON.parse(readFileSync(resolvedManifest, "utf8"));
  if (manifest.schemaVersion !== schemas.manifest) {
    throw new Error("shard manifest schemaVersion is invalid");
  }
  const outputRoot = resolve(manifest.outputRoot);
  if (outputRoot !== dirname(resolvedManifest)) {
    throw new Error("shard manifest outputRoot differs from its directory");
  }
  const loaded = loadAndVerifyDefinition(manifest.releaseExecutionPath);
  if (loaded.sha256 !== manifest.releaseExecutionSha256) {
    throw new Error("release execution hash differs from shard manifest");
  }
  if (
    loaded.definition.protocol.sha256 !== manifest.protocolSha256 ||
    sha256File(manifest.protocolPath) !== manifest.protocolSha256
  ) {
    throw new Error("protocol hash differs from shard manifest");
  }
  if (sha256File(manifest.masterPlanPath) !== manifest.masterPlanSha256) {
    throw new Error("master plan hash differs from shard manifest");
  }
  const shard = manifest.shards.find((value) => value.id === shardId);
  if (!shard) throw new Error(`unknown shard: ${shardId}`);
  const shardPlanPath = ensureWithin(
    outputRoot,
    resolve(outputRoot, shard.planFile),
    "shard plan",
  );
  if (sha256File(shardPlanPath) !== shard.planSha256) {
    throw new Error(`shard plan hash differs: ${shard.id}`);
  }
  const rows = parsePlanText(readFileSync(shardPlanPath, "utf8")).rows;
  if (rows.length !== shard.rowCount) {
    throw new Error(`shard row count differs: ${shard.id}`);
  }
  return {
    manifest,
    manifestPath: resolvedManifest,
    outputRoot,
    definition: loaded.definition,
    shard,
    shardPlanPath,
    rows,
    checkpointPath: ensureWithin(
      outputRoot,
      resolve(outputRoot, shard.checkpointFile),
      "checkpoint",
    ),
  };
}

export function validateShardCheckpoint(context) {
  if (!regularFile(context.checkpointPath)) {
    throw new Error(`shard checkpoint is missing: ${context.shard.id}`);
  }
  const checkpoint = JSON.parse(
    readFileSync(context.checkpointPath, "utf8"),
  );
  if (
    checkpoint.schemaVersion !== schemas.checkpoint ||
    checkpoint.releaseExecutionSha256 !==
      context.manifest.releaseExecutionSha256 ||
    checkpoint.protocolSha256 !== context.manifest.protocolSha256 ||
    checkpoint.masterPlanSha256 !== context.manifest.masterPlanSha256 ||
    checkpoint.shardId !== context.shard.id ||
    checkpoint.shardPlanSha256 !== context.shard.planSha256 ||
    checkpoint.rowCount !== context.rows.length ||
    !Array.isArray(checkpoint.rows) ||
    checkpoint.rows.length !== context.rows.length
  ) {
    throw new Error(`shard checkpoint contract differs: ${context.shard.id}`);
  }
  const completed = context.rows.map((row, index) => {
    const validation = validateCompletedRow(
      row,
      context.definition,
      context.outputRoot,
    );
    const registered = checkpoint.rows[index];
    if (
      registered.rowId !== row.rowId ||
      registered.runRecordSha256 !== validation.runRecordSha256 ||
      registered.termination !== validation.record.termination ||
      registered.correctedImageSha256 !==
        validation.record.correctedImageSha256
    ) {
      throw new Error(`checkpoint row differs: ${row.rowId}`);
    }
    const pointer =
      validation.record.correctedImageSha256 === null
        ? null
        : readCorrectedPointer(
            row,
            context.definition,
            context.outputRoot,
            validation.record.correctedImageSha256,
          );
    const pointerHash = pointer === null ? null : sha256File(pointer.path);
    if (registered.correctedPointerSha256 !== pointerHash) {
      throw new Error(`checkpoint pointer differs: ${row.rowId}`);
    }
    return { row, validation, pointer };
  });
  const failures = completed.filter(
    ({ validation }) => validation.record.termination !== "success",
  ).length;
  if (failures !== checkpoint.failureCount) {
    throw new Error(`checkpoint failure count differs: ${context.shard.id}`);
  }
  return { checkpoint, completed };
}

export async function executeShard(context, runPending) {
  if (regularFile(context.checkpointPath)) {
    return {
      alreadyComplete: true,
      ...validateShardCheckpoint(context),
    };
  }
  const pending = [];
  for (const row of context.rows) {
    const directory = resolve(row.value.output_directory);
    if (regularFile(resolve(directory, "run.json"))) {
      validateCompletedRow(row, context.definition, context.outputRoot);
    } else {
      quarantineIncompleteRow(row, context.definition, context.outputRoot);
      pending.push(row);
    }
  }
  if (pending.length > 0) {
    const pendingPath = resolve(
      context.outputRoot,
      "state",
      `${context.shard.id}-pending-${Date.now()}.tsv`,
    );
    writeFileSync(pendingPath, encodePlanRows(pending), {
      encoding: "utf8",
      flag: "wx",
    });
    forceFile(pendingPath);
    await runPending(pendingPath, pending);
  }
  const completed = context.rows.map((row) => {
    const validation = validateCompletedRow(
      row,
      context.definition,
      context.outputRoot,
    );
    const pointer = retainCorrectedOutput(
      row,
      validation.record,
      context.definition,
      context.outputRoot,
    );
    const revalidated = validateCompletedRow(
      row,
      context.definition,
      context.outputRoot,
    );
    return { row, validation: revalidated, pointer };
  });
  const checkpoint = checkpointValue(context, completed);
  atomicWriteJson(context.checkpointPath, checkpoint, false);
  return {
    alreadyComplete: false,
    checkpoint,
    completed,
  };
}

function productionRunner(lockPath, protocolPath) {
  return (pendingPath) => {
    const result = spawnSync(
      process.execPath,
      [
        resolve("scripts/invoke-motion-runner.mjs"),
        "--lock",
        resolve(lockPath),
        "--",
        "execute-plan",
        "--plan",
        pendingPath,
        "--protocol",
        resolve(protocolPath),
      ],
      { stdio: "inherit" },
    );
    if (result.error) throw result.error;
    return { status: result.status };
  };
}

async function main(arguments_) {
  const manifestPath = resolve(option(arguments_, "--manifest"));
  const shardId = option(arguments_, "--shard");
  const lockPath = resolve(option(arguments_, "--lock"));
  const context = loadShardContext(manifestPath, shardId);
  const result = await executeShard(
    context,
    productionRunner(lockPath, context.manifest.protocolPath),
  );
  process.stdout.write(
    `motion shard ${shardId}: ` +
      `${result.alreadyComplete ? "already complete" : "checkpointed"}, ` +
      `${result.checkpoint.rowCount} rows, ` +
      `${result.checkpoint.failureCount} recorded failures\n`,
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
