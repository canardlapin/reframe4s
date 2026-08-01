#!/usr/bin/env node

import assert from "node:assert/strict";
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  renameSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { resolve } from "node:path";

import {
  planHeader,
  sha256File,
} from "./motion-shard-contract.mjs";
import {
  executeShard,
  loadShardContext,
} from "./execute-motion-shard.mjs";
import { finalizeShardSet } from "./finalize-motion-shards.mjs";
import {
  buildShardManifest,
  writeShardSet,
} from "./prepare-motion-shards.mjs";

const root = mkdtempSync(resolve(tmpdir(), "motion-shard-finalize-"));
const protocolPath = resolve(root, "protocol.json");
writeFileSync(protocolPath, "{}\n");
const definition = JSON.parse(
  readFileSync("benchmarks/motion/release-execution-v1.json", "utf8"),
);
definition.protocol.path = protocolPath;
definition.protocol.sha256 = sha256File(protocolPath);
definition.masterPlan.expectedRows = 4;
definition.sharding.balancedBlocksPerShard = 1;
definition.sharding.expectedShards = 1;
const definitionText = `${JSON.stringify(definition, null, 2)}\n`;
const definitionPath = resolve(root, "release.json");
writeFileSync(definitionPath, definitionText);
const outputRoot = resolve(root, "execution");

function planLine(order, implementation) {
  const row = {
    subject: "sub-01",
    scenario: "clean",
    input: "/input.nii.gz",
    mask: "",
    court: "common_resampler_estimation",
    implementation,
    phase: "measured",
    repetition: "0",
    order: String(order),
    allocated_core_count: "4",
    requested_worker_count: "4",
    effective_worker_count: implementation === "nifreeze" ? "4" : "1",
    effective_worker_evidence:
      implementation === "reframe4s_motion"
        ? "implementation_contract"
        : implementation === "nifreeze"
          ? "locked_adapter_configuration"
          : "locked_tool_probe",
    reference_policy: "fixed_frame_0",
    reference_index: "0",
    timeout_seconds: "60",
    output_directory: resolve(outputRoot, "runs", implementation),
  };
  return planHeader.map((name) => row[name]).join("\t");
}
const implementations = definition.masterPlan.requiredImplementations;
const planText = `${
  planHeader.join("\t")
}\n${implementations
  .map((implementation, order) => planLine(order, implementation))
  .join("\n")}\n`;
const planPath = resolve(root, "plan.tsv");
writeFileSync(planPath, planText);
const built = buildShardManifest({
  definitionPath,
  definitionText,
  definition,
  planPath,
  planText,
  outputRoot,
});
const manifestPath = writeShardSet(outputRoot, built);
const context = loadShardContext(manifestPath, "shard-0000");

function estimation(implementation) {
  return {
    reframe4s_motion: "production_trilinear_objective",
    afni_3dvolreg: "afni_3dvolreg_heptic_registered",
    nifreeze: "ants_rigid_backend_registered",
    fsl_mcflirt: "mcflirt_internal_registered",
  }[implementation];
}

function writeRun(row) {
  const directory = resolve(row.value.output_directory);
  mkdirSync(directory, { recursive: true });
  for (const [name, value] of Object.entries({
    "stdout.txt": "stdout\n",
    "stderr.txt": "",
    "version-stdout.txt": "version\n",
    "version-stderr.txt": "",
    "poses.csv": "poses\n",
    "corrected.nii.gz": "shared corrected\n",
  })) {
    writeFileSync(resolve(directory, name), value);
  }
  const requested = Number(row.value.requested_worker_count);
  const record = {
    schemaVersion: "reframe4s.motion-superiority.run/v2",
    protocolSha256: definition.protocol.sha256,
    subject: row.value.subject,
    scenario: row.value.scenario,
    court: row.value.court,
    implementation: row.value.implementation,
    phase: row.value.phase,
    repetition: 0,
    order: Number(row.value.order),
    allocatedCoreCount: 4,
    requestedWorkerCount: requested,
    effectiveWorkerCount: Number(row.value.effective_worker_count),
    effectiveWorkerEvidence: row.value.effective_worker_evidence,
    cpuAffinity: "0,1,2,3",
    referencePolicy: "fixed_frame_0",
    command: ["/tool"],
    workingDirectory: "/locked",
    environment: Object.fromEntries(
      [
        "OMP_NUM_THREADS",
        "OPENBLAS_NUM_THREADS",
        "MKL_NUM_THREADS",
        "VECLIB_MAXIMUM_THREADS",
        "NUMEXPR_NUM_THREADS",
      ].map((name) => [name, String(requested)]),
    ),
    exitStatus: 0,
    termination: "success",
    failure: null,
    stageTimes: {
      source: "runner_end_to_end_only",
      decodeSeconds: null,
      prepareSeconds: null,
      estimateSeconds: null,
      applySeconds: null,
      encodeSeconds: null,
      reportSeconds: null,
      endToEndSeconds: 1,
    },
    peakRssBytes: 1,
    materializedOutputBytes: 1,
    stdoutSha256: sha256File(resolve(directory, "stdout.txt")),
    stderrSha256: sha256File(resolve(directory, "stderr.txt")),
    poseSha256: sha256File(resolve(directory, "poses.csv")),
    correctedImageSha256: sha256File(
      resolve(directory, "corrected.nii.gz"),
    ),
    tool: {
      executable: "/tool",
      registeredPin: "fixture",
      versionExitStatus: 0,
      versionStdoutSha256: sha256File(
        resolve(directory, "version-stdout.txt"),
      ),
      versionStderrSha256: sha256File(
        resolve(directory, "version-stderr.txt"),
      ),
    },
    pipeline: {
      estimatorMaskPolicy: "none",
      estimationInterpolation: estimation(row.value.implementation),
      finalInterpolation: "image4s_reference_trilinear",
      boundaryPolicy: "constant_0",
      finalResamplingPasses: 1,
      timingIncludesFinalResampling: false,
    },
  };
  writeFileSync(
    resolve(directory, "run.json"),
    `${JSON.stringify(record, null, 2)}\n`,
  );
}

await executeShard(context, async (_pendingPath, pending) => {
  pending.forEach(writeRun);
});
assert.throws(
  () => finalizeShardSet(manifestPath, false),
  /lacks metrics/,
);

for (const row of context.rows) {
  const directory = resolve(row.value.output_directory);
  const runPath = resolve(directory, "run.json");
  const run = JSON.parse(readFileSync(runPath, "utf8"));
  writeFileSync(
    resolve(directory, "metrics.json"),
    `${JSON.stringify(
      {
        schemaVersion: "reframe4s.motion-superiority.metrics/v1",
        protocolSha256: definition.protocol.sha256,
        runRecordSha256: sha256File(runPath),
        subject: run.subject,
        scenario: run.scenario,
        court: run.court,
        implementation: run.implementation,
        phase: run.phase,
        repetition: run.repetition,
        allocatedCoreCount: run.allocatedCoreCount,
      },
      null,
      2,
    )}\n`,
  );
}

const verified = finalizeShardSet(manifestPath, false);
assert.equal(verified.rowCount, 4);
assert.equal(verified.designatedMetricCount, 4);
assert.equal(verified.correctedObjectCount, 1);
assert.equal(verified.designatedHardLinksReleased, false);
for (const row of context.rows) {
  assert(existsSync(resolve(row.value.output_directory, "corrected.nii.gz")));
}

const finalized = finalizeShardSet(manifestPath, true);
assert.equal(finalized.designatedHardLinksReleased, true);
assert.equal(finalized.releasedDirectFileCount, 4);
for (const row of context.rows) {
  assert(!existsSync(resolve(row.value.output_directory, "corrected.nii.gz")));
}
assert.deepEqual(finalizeShardSet(manifestPath, true), finalized);

const checkpoint = resolve(outputRoot, "checkpoints/shard-0000.json");
const moved = `${checkpoint}.missing`;
renameSync(checkpoint, moved);
for (const row of context.rows) {
  const directory = resolve(row.value.output_directory);
  writeFileSync(resolve(directory, "corrected.nii.gz"), "shared corrected\n");
}
assert.throws(
  () => finalizeShardSet(manifestPath, true),
  /checkpoint is missing/,
);
for (const row of context.rows) {
  assert(
    existsSync(resolve(row.value.output_directory, "corrected.nii.gz")),
    "a failed global validation must not release retained artifacts",
  );
}
renameSync(moved, checkpoint);

console.log("motion shard finalization tests: ok");
