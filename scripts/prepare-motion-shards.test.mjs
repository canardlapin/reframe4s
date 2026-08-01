#!/usr/bin/env node

import assert from "node:assert/strict";
import {
  existsSync,
  mkdtempSync,
  readFileSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { resolve } from "node:path";

import {
  planHeader,
  sha256Text,
} from "./motion-shard-contract.mjs";
import {
  buildShardManifest,
  writeShardSet,
} from "./prepare-motion-shards.mjs";

const root = mkdtempSync(resolve(tmpdir(), "motion-shards-"));
const definitionPath = resolve(root, "release.json");
const planPath = resolve(root, "plan.tsv");
const outputRoot = resolve(root, "execution");
const implementations = [
  "reframe4s_motion",
  "afni_3dvolreg",
  "nifreeze",
  "fsl_mcflirt",
];
const definition = {
  id: "fixture-release",
  protocol: {
    path: resolve(root, "protocol.json"),
    sha256: "a".repeat(64),
  },
  masterPlan: {
    expectedRows: 8,
    balancedBlockRows: 4,
    requiredImplementations: implementations,
  },
  sharding: {
    balancedBlocksPerShard: 1,
    expectedShards: 2,
  },
};
const definitionText = `${JSON.stringify(definition, null, 2)}\n`;
writeFileSync(definitionPath, definitionText);

function line(subject, order, implementation) {
  const row = {
    subject,
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
    effective_worker_evidence: "fixture",
    reference_policy: "fixed_frame_0",
    reference_index: "0",
    timeout_seconds: "60",
    output_directory: resolve(root, "runs", subject, implementation),
  };
  return planHeader.map((name) => row[name]).join("\t");
}
const planText = `${
  planHeader.join("\t")
}\n${["sub-01", "sub-02"]
  .flatMap((subject) =>
    implementations.map((implementation, order) =>
      line(subject, order, implementation),
    ),
  )
  .join("\n")}\n`;
writeFileSync(planPath, planText);

const built = buildShardManifest({
  definitionPath,
  definitionText,
  definition,
  planPath,
  planText,
  outputRoot,
});
assert.equal(built.manifest.shardCount, 2);
assert.equal(built.manifest.rowCount, 8);
assert.equal(built.manifest.masterPlanSha256, sha256Text(planText));
assert.equal(built.shards[0].rowCount, 4);
const manifestPath = writeShardSet(outputRoot, built);
assert(existsSync(manifestPath));
assert(existsSync(resolve(outputRoot, "shards/shard-0000.tsv")));
assert(existsSync(resolve(outputRoot, "shards/shard-0001.tsv")));
assert.equal(
  JSON.parse(readFileSync(manifestPath, "utf8")).shards[1].firstRowIndex,
  4,
);
assert.throws(() => writeShardSet(outputRoot, built), /not empty/);

console.log("motion shard preparation tests: ok");
