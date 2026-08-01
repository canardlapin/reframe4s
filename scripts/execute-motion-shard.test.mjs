#!/usr/bin/env node

import assert from "node:assert/strict";
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  unlinkSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { resolve } from "node:path";

import {
  parsePlanText,
  planHeader,
  sha256File,
} from "./motion-shard-contract.mjs";
import {
  executeShard,
  loadShardContext,
  quarantineIncompleteRow,
  retainCorrectedOutput,
  validateCompletedRow,
} from "./execute-motion-shard.mjs";
import {
  buildShardManifest,
  writeShardSet,
} from "./prepare-motion-shards.mjs";

const root = mkdtempSync(resolve(tmpdir(), "motion-shard-execute-"));
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
const implementations = definition.masterPlan.requiredImplementations;

function rowLine(order, implementation, phase = "measured") {
  const row = {
    subject: "sub-01",
    scenario: "clean",
    input: "/assets/input.nii.gz",
    mask: "",
    court: "common_resampler_estimation",
    implementation,
    phase,
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

const planText = `${
  planHeader.join("\t")
}\n${implementations
  .map((implementation, order) => rowLine(order, implementation))
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

function writeRun(row) {
  const directory = resolve(row.value.output_directory);
  mkdirSync(directory, { recursive: true });
  const files = {
    "stdout.txt": `stdout ${row.value.implementation}\n`,
    "stderr.txt": "",
    "version-stdout.txt": "tool 1.0\n",
    "version-stderr.txt": "",
    "poses.csv": "fixture poses\n",
    "corrected.nii.gz": "identical corrected fixture\n",
  };
  for (const [name, value] of Object.entries(files)) {
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
    repetition: Number(row.value.repetition),
    order: Number(row.value.order),
    allocatedCoreCount: Number(row.value.allocated_core_count),
    requestedWorkerCount: requested,
    effectiveWorkerCount: Number(row.value.effective_worker_count),
    effectiveWorkerEvidence: row.value.effective_worker_evidence,
    cpuAffinity: "0,1,2,3",
    referencePolicy: row.value.reference_policy,
    command: ["/locked/tool"],
    workingDirectory: "/locked",
    environment: {
      OMP_NUM_THREADS: String(requested),
      OPENBLAS_NUM_THREADS: String(requested),
      MKL_NUM_THREADS: String(requested),
      VECLIB_MAXIMUM_THREADS: String(requested),
      NUMEXPR_NUM_THREADS: String(requested),
    },
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
      endToEndSeconds: 1.0,
    },
    peakRssBytes: 1024,
    materializedOutputBytes: 4096,
    stdoutSha256: sha256File(resolve(directory, "stdout.txt")),
    stderrSha256: sha256File(resolve(directory, "stderr.txt")),
    poseSha256: sha256File(resolve(directory, "poses.csv")),
    correctedImageSha256: sha256File(
      resolve(directory, "corrected.nii.gz"),
    ),
    tool: {
      executable: "/locked/tool",
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
      estimationInterpolation:
        {
          reframe4s_motion: "production_trilinear_objective",
          afni_3dvolreg: "afni_3dvolreg_heptic_registered",
          nifreeze: "ants_rigid_backend_registered",
          fsl_mcflirt: "mcflirt_internal_registered",
        }[row.value.implementation],
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

writeRun(context.rows[0]);
mkdirSync(resolve(context.rows[1].value.output_directory), {
  recursive: true,
});
writeFileSync(
  resolve(context.rows[1].value.output_directory, "partial.txt"),
  "interrupted",
);

let runnerCalls = 0;
const first = await executeShard(context, async (pendingPath, pending) => {
  runnerCalls += 1;
  assert.equal(parsePlanText(readFileSync(pendingPath, "utf8")).rows.length, 3);
  assert.equal(pending.length, 3);
  pending.forEach(writeRun);
});
assert.equal(first.alreadyComplete, false);
assert.equal(first.checkpoint.rowCount, 4);
assert.equal(first.checkpoint.failureCount, 0);
assert.equal(runnerCalls, 1);
assert(
  existsSync(
    resolve(
      outputRoot,
      "quarantine",
      context.rows[1].rowId,
      "attempt-01",
      "partial.txt",
    ),
  ),
);
for (const row of context.rows) {
  assert(
    existsSync(resolve(row.value.output_directory, "corrected.cas.json")),
  );
  assert(existsSync(resolve(row.value.output_directory, "corrected.nii.gz")));
}
const objectPrefixes = readdirSync(resolve(outputRoot, "objects/sha256"));
assert.equal(objectPrefixes.length, 1);
assert.equal(
  readdirSync(resolve(outputRoot, "objects/sha256", objectPrefixes[0])).length,
  1,
);

const second = await executeShard(context, async () => {
  throw new Error("idempotent resume must not invoke the runner");
});
assert.equal(second.alreadyComplete, true);
assert.equal(second.checkpoint.rowCount, 4);

const nonDesignated = structuredClone(context.rows[0]);
nonDesignated.value.phase = "warmup";
nonDesignated.value.output_directory = resolve(outputRoot, "non-designated");
mkdirSync(nonDesignated.value.output_directory, { recursive: true });
writeFileSync(
  resolve(nonDesignated.value.output_directory, "corrected.nii.gz"),
  "non-designated corrected fixture\n",
);
const nonDesignatedRecord = {
  correctedImageSha256: sha256File(
    resolve(nonDesignated.value.output_directory, "corrected.nii.gz"),
  ),
};
retainCorrectedOutput(
  nonDesignated,
  nonDesignatedRecord,
  definition,
  outputRoot,
);
assert(
  !existsSync(
    resolve(nonDesignated.value.output_directory, "corrected.nii.gz"),
  ),
);
assert(
  existsSync(
    resolve(nonDesignated.value.output_directory, "corrected.cas.json"),
  ),
);

const interruptedRetention = structuredClone(context.rows[0]);
interruptedRetention.rowId = "a".repeat(64);
interruptedRetention.value.output_directory = resolve(
  outputRoot,
  "interrupted-retention",
);
writeRun(interruptedRetention);
unlinkSync(
  resolve(interruptedRetention.value.output_directory, "corrected.nii.gz"),
);
const interruptedValidation = validateCompletedRow(
  interruptedRetention,
  definition,
  outputRoot,
);
retainCorrectedOutput(
  interruptedRetention,
  interruptedValidation.record,
  definition,
  outputRoot,
);
assert(
  existsSync(
    resolve(
      interruptedRetention.value.output_directory,
      "corrected.cas.json",
    ),
  ),
  "an object imported before interruption must recover its missing pointer",
);
assert(
  existsSync(
    resolve(
      interruptedRetention.value.output_directory,
      "corrected.nii.gz",
    ),
  ),
  "a designated row must recover its direct scoring hard link",
);

const exhausted = structuredClone(context.rows[0]);
exhausted.rowId = "b".repeat(64);
exhausted.value.output_directory = resolve(outputRoot, "exhausted");
mkdirSync(exhausted.value.output_directory, { recursive: true });
writeFileSync(resolve(exhausted.value.output_directory, "partial"), "one");
quarantineIncompleteRow(exhausted, definition, outputRoot);
mkdirSync(exhausted.value.output_directory, { recursive: true });
writeFileSync(resolve(exhausted.value.output_directory, "partial"), "two");
assert.throws(
  () => quarantineIncompleteRow(exhausted, definition, outputRoot),
  /exhausted its registered attempt limit/,
);
assert(
  existsSync(
    resolve(
      outputRoot,
      "quarantine",
      exhausted.rowId,
      "attempt-02",
      "partial",
    ),
  ),
);

writeFileSync(
  resolve(context.rows[0].value.output_directory, "stdout.txt"),
  "corrupted\n",
);
await assert.rejects(
  () => executeShard(context, async () => {}),
  /stdout.txt hash differs/,
);

writeFileSync(
  resolve(context.rows[0].value.output_directory, "stdout.txt"),
  `stdout ${context.rows[0].value.implementation}\n`,
);
const pointer = JSON.parse(
  readFileSync(
    resolve(context.rows[0].value.output_directory, "corrected.cas.json"),
    "utf8",
  ),
);
writeFileSync(resolve(outputRoot, pointer.object), "corrupted object\n");
await assert.rejects(
  () => executeShard(context, async () => {}),
  /corrected object hash differs/,
);

console.log("motion shard execution tests: ok");
