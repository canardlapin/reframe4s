import { createHash } from "node:crypto";
import {
  closeSync,
  existsSync,
  fsyncSync,
  lstatSync,
  openSync,
  readFileSync,
  readSync,
} from "node:fs";
import { resolve } from "node:path";

export const planHeader = [
  "subject",
  "scenario",
  "input",
  "mask",
  "court",
  "implementation",
  "phase",
  "repetition",
  "order",
  "allocated_core_count",
  "requested_worker_count",
  "effective_worker_count",
  "effective_worker_evidence",
  "reference_policy",
  "reference_index",
  "timeout_seconds",
  "output_directory",
];

const sha256Pattern = /^[0-9a-f]{64}$/;
const releaseSchema = "reframe4s.motion.release-execution/v1";
const shardManifestSchema = "reframe4s.motion.shard-manifest/v1";
const shardCheckpointSchema = "reframe4s.motion.shard-checkpoint/v1";
const correctedPointerSchema = "reframe4s.motion.corrected-pointer/v1";
const finalizationSchema = "reframe4s.motion.shard-finalization/v1";

export const schemas = {
  release: releaseSchema,
  manifest: shardManifestSchema,
  checkpoint: shardCheckpointSchema,
  pointer: correctedPointerSchema,
  finalization: finalizationSchema,
};

export function sha256Text(value) {
  return createHash("sha256").update(value).digest("hex");
}

export function sha256File(path) {
  const descriptor = openSync(path, "r");
  try {
    const digest = createHash("sha256");
    const buffer = Buffer.allocUnsafe(1024 * 1024);
    let count = readSync(descriptor, buffer, 0, buffer.length, null);
    while (count > 0) {
      digest.update(buffer.subarray(0, count));
      count = readSync(descriptor, buffer, 0, buffer.length, null);
    }
    return digest.digest("hex");
  } finally {
    closeSync(descriptor);
  }
}

export function forceFile(path) {
  const descriptor = openSync(path, "r");
  try {
    fsyncSync(descriptor);
  } finally {
    closeSync(descriptor);
  }
}

function positiveInteger(errors, value, name) {
  if (!Number.isInteger(value) || value <= 0) {
    errors.push(`${name} must be a positive integer`);
  }
}

export function releaseExecutionErrors(definition) {
  const errors = [];
  if (definition?.schemaVersion !== releaseSchema) {
    errors.push("schemaVersion is invalid");
  }
  if (!sha256Pattern.test(definition?.protocol?.sha256 ?? "")) {
    errors.push("protocol.sha256 is invalid");
  }
  if (typeof definition?.protocol?.path !== "string") {
    errors.push("protocol.path is invalid");
  }
  const master = definition?.masterPlan;
  positiveInteger(errors, master?.expectedRows, "masterPlan.expectedRows");
  if (master?.balancedBlockRows !== 4) {
    errors.push("masterPlan.balancedBlockRows must be 4");
  }
  const implementations = [
    "reframe4s_motion",
    "afni_3dvolreg",
    "nifreeze",
    "fsl_mcflirt",
  ];
  if (
    !Array.isArray(master?.requiredImplementations) ||
    master.requiredImplementations.length !== implementations.length ||
    implementations.some(
      (value, index) => master.requiredImplementations[index] !== value,
    )
  ) {
    errors.push("masterPlan.requiredImplementations is invalid");
  }
  const sharding = definition?.sharding;
  positiveInteger(
    errors,
    sharding?.balancedBlocksPerShard,
    "sharding.balancedBlocksPerShard",
  );
  positiveInteger(errors, sharding?.expectedShards, "sharding.expectedShards");
  positiveInteger(
    errors,
    sharding?.maximumShardsPerInvocation,
    "sharding.maximumShardsPerInvocation",
  );
  if (sharding?.executeSequentiallyOnOneHost !== true) {
    errors.push("sharding.executeSequentiallyOnOneHost must be true");
  }
  if (
    Number.isInteger(master?.expectedRows) &&
    Number.isInteger(sharding?.balancedBlocksPerShard) &&
    Number.isInteger(sharding?.expectedShards) &&
    master.expectedRows /
      (master.balancedBlockRows * sharding.balancedBlocksPerShard) !==
      sharding.expectedShards
  ) {
    errors.push("sharding.expectedShards contradicts the registered row count");
  }
  const resume = definition?.resume;
  if (resume?.completedRunRecordRetry !== "forbidden") {
    errors.push("resume.completedRunRecordRetry must be forbidden");
  }
  if (resume?.incompleteAttemptDisposition !== "atomic-quarantine") {
    errors.push(
      "resume.incompleteAttemptDisposition must be atomic-quarantine",
    );
  }
  if (resume?.maximumAttemptsPerRow !== 2) {
    errors.push("resume.maximumAttemptsPerRow must be 2");
  }
  if (resume?.checkpointWrite !== "create-new-after-complete-validation") {
    errors.push("resume.checkpointWrite is invalid");
  }
  const retention = definition?.retention;
  if (retention?.algorithm !== "sha256") {
    errors.push("retention.algorithm must be sha256");
  }
  if (retention?.pointerFile !== "corrected.cas.json") {
    errors.push("retention.pointerFile is invalid");
  }
  if (
    retention?.nonDesignatedCorrectedDirectFiles !==
    "remove-after-object-and-pointer-fsync"
  ) {
    errors.push("non-designated corrected-output retention is invalid");
  }
  const designated = retention?.designatedAccuracyOutput;
  if (
    designated?.phase !== "measured" ||
    designated?.repetition !== 0 ||
    designated?.allocatedCoreCount !== 4 ||
    designated?.retainDirectHardLinkUntilScored !== true
  ) {
    errors.push("retention.designatedAccuracyOutput is invalid");
  }
  const finalization = definition?.finalization;
  for (const name of [
    "requireEveryShardCheckpoint",
    "requireExactMasterPlanCoverage",
    "requireDesignatedMetricsBeforeRelease",
    "releaseDesignatedHardLinksAfterScoring",
    "separateAttestationJob",
  ]) {
    if (finalization?.[name] !== true) {
      errors.push(`finalization.${name} must be true`);
    }
  }
  return errors;
}

export function parsePlanText(text) {
  const lines = text.trimEnd().split(/\r?\n/);
  if (lines.length < 2) throw new Error("execution plan has no rows");
  const names = lines.shift().split("\t");
  if (
    names.length !== planHeader.length ||
    planHeader.some((name, index) => names[index] !== name)
  ) {
    throw new Error("execution plan has an unexpected header");
  }
  const rows = lines.map((line, index) => {
    const fields = line.split("\t");
    if (fields.length !== names.length) {
      throw new Error(`execution plan row ${index + 2} has ${fields.length} fields`);
    }
    const value = Object.fromEntries(
      names.map((name, field) => [name, fields[field]]),
    );
    return {
      index,
      line,
      value,
      rowId: sha256Text(`${planHeader.join("\t")}\n${line}\n`),
    };
  });
  return { header: planHeader.join("\t"), rows };
}

export function encodePlanRows(rows) {
  return `${[planHeader.join("\t"), ...rows.map((row) => row.line)].join("\n")}\n`;
}

function blockIdentity(row) {
  const value = row.value;
  return [
    value.subject,
    value.scenario,
    value.input,
    value.mask,
    value.court,
    value.phase,
    value.repetition,
    value.allocated_core_count,
    value.reference_index,
    value.timeout_seconds,
  ].join("\u001f");
}

export function balancedBlocks(rows, definition) {
  const size = definition.masterPlan.balancedBlockRows;
  if (rows.length !== definition.masterPlan.expectedRows) {
    throw new Error(
      `master plan has ${rows.length} rows, expected ` +
        definition.masterPlan.expectedRows,
    );
  }
  if (rows.length % size !== 0) {
    throw new Error("master plan ends inside a balanced method block");
  }
  const blocks = [];
  for (let start = 0; start < rows.length; start += size) {
    const block = rows.slice(start, start + size);
    const identity = blockIdentity(block[0]);
    if (block.some((row) => blockIdentity(row) !== identity)) {
      throw new Error(`balanced block at row ${start} mixes run contexts`);
    }
    const orders = block.map((row) => Number(row.value.order)).sort();
    if (orders.some((order, index) => order !== index)) {
      throw new Error(`balanced block at row ${start} has invalid order`);
    }
    const actual = new Set(block.map((row) => row.value.implementation));
    if (
      actual.size !== definition.masterPlan.requiredImplementations.length ||
      definition.masterPlan.requiredImplementations.some(
        (implementation) => !actual.has(implementation),
      )
    ) {
      throw new Error(
        `balanced block at row ${start} lacks a required implementation`,
      );
    }
    blocks.push(block);
  }
  return blocks;
}

export function buildShardEntries(rows, definition) {
  const blocks = balancedBlocks(rows, definition);
  const perShard = definition.sharding.balancedBlocksPerShard;
  const shards = [];
  for (let start = 0; start < blocks.length; start += perShard) {
    const selected = blocks.slice(start, start + perShard).flat();
    const ordinal = shards.length;
    const id = `shard-${String(ordinal).padStart(4, "0")}`;
    const text = encodePlanRows(selected);
    shards.push({
      id,
      ordinal,
      firstRowIndex: selected[0].index,
      rowCount: selected.length,
      blockCount: selected.length / definition.masterPlan.balancedBlockRows,
      planFile: `shards/${id}.tsv`,
      planSha256: sha256Text(text),
      checkpointFile: `checkpoints/${id}.json`,
      rows: selected,
      text,
    });
  }
  if (shards.length !== definition.sharding.expectedShards) {
    throw new Error(
      `built ${shards.length} shards, expected ` +
        definition.sharding.expectedShards,
    );
  }
  return shards;
}

export function rowCoverageKey(row) {
  const value = row.value;
  return [
    value.subject,
    value.scenario,
    value.court,
    value.implementation,
    value.phase,
    value.repetition,
    value.order,
    value.allocated_core_count,
    value.requested_worker_count,
    value.effective_worker_count,
  ].join("\u001f");
}

export function isDesignatedAccuracyRow(row, definition) {
  const designated = definition.retention.designatedAccuracyOutput;
  return (
    row.value.phase === designated.phase &&
    Number(row.value.repetition) === designated.repetition &&
    Number(row.value.allocated_core_count) ===
      designated.allocatedCoreCount
  );
}

export function loadAndVerifyDefinition(path) {
  const text = readFileSync(path, "utf8");
  const definition = JSON.parse(text);
  const errors = releaseExecutionErrors(definition);
  if (errors.length > 0) {
    throw new Error(`invalid release execution:\n- ${errors.join("\n- ")}`);
  }
  const protocolPath = resolve(definition.protocol.path);
  if (!existsSync(protocolPath)) {
    throw new Error(`protocol is missing: ${protocolPath}`);
  }
  if (sha256File(protocolPath) !== definition.protocol.sha256) {
    throw new Error("release execution protocol hash differs");
  }
  return { definition, text, sha256: sha256Text(text), protocolPath };
}

export function regularFile(path) {
  return existsSync(path) && lstatSync(path).isFile();
}

export function assertSha256(value, label) {
  if (!sha256Pattern.test(value ?? "")) {
    throw new Error(`${label} is not a SHA-256 digest`);
  }
}
