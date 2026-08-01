#!/usr/bin/env node

import {
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  writeFileSync,
} from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

import {
  buildShardEntries,
  forceFile,
  loadAndVerifyDefinition,
  parsePlanText,
  schemas,
  sha256Text,
} from "./motion-shard-contract.mjs";

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

function prepareEmptyRoot(path) {
  if (existsSync(path)) {
    if (readdirSync(path).length > 0) {
      throw new Error(`shard output root is not empty: ${path}`);
    }
  } else {
    mkdirSync(path, { recursive: true });
  }
  for (const directory of [
    "shards",
    "checkpoints",
    "state",
    "quarantine",
    "objects/sha256",
  ]) {
    mkdirSync(resolve(path, directory), { recursive: true });
  }
}

export function buildShardManifest({
  definitionPath,
  definitionText,
  definition,
  planPath,
  planText,
  outputRoot,
}) {
  const plan = parsePlanText(planText);
  const shards = buildShardEntries(plan.rows, definition);
  return {
    manifest: {
      schemaVersion: schemas.manifest,
      releaseExecutionId: definition.id,
      releaseExecutionPath: resolve(definitionPath),
      releaseExecutionSha256: sha256Text(definitionText),
      protocolPath: resolve(definition.protocol.path),
      protocolSha256: definition.protocol.sha256,
      masterPlanPath: resolve(planPath),
      masterPlanSha256: sha256Text(planText),
      outputRoot: resolve(outputRoot),
      rowCount: plan.rows.length,
      blockCount:
        plan.rows.length / definition.masterPlan.balancedBlockRows,
      shardCount: shards.length,
      maximumShardsPerInvocation:
        definition.sharding.maximumShardsPerInvocation,
      shards: shards.map((shard) => ({
        id: shard.id,
        ordinal: shard.ordinal,
        firstRowIndex: shard.firstRowIndex,
        rowCount: shard.rowCount,
        blockCount: shard.blockCount,
        planFile: shard.planFile,
        planSha256: shard.planSha256,
        checkpointFile: shard.checkpointFile,
      })),
    },
    shards,
  };
}

export function writeShardSet(outputRoot, built) {
  prepareEmptyRoot(outputRoot);
  for (const shard of built.shards) {
    const path = resolve(outputRoot, shard.planFile);
    writeFileSync(path, shard.text, { encoding: "utf8", flag: "wx" });
    forceFile(path);
  }
  const manifestPath = resolve(outputRoot, "manifest.json");
  writeFileSync(
    manifestPath,
    `${JSON.stringify(built.manifest, null, 2)}\n`,
    { encoding: "utf8", flag: "wx" },
  );
  forceFile(manifestPath);
  return manifestPath;
}

function main(arguments_) {
  const definitionPath = resolve(
    option(
      arguments_,
      "--definition",
      "benchmarks/motion/release-execution-v1.json",
    ),
  );
  const planPath = resolve(option(arguments_, "--plan"));
  const outputRoot = resolve(option(arguments_, "--output-root"));
  const loaded = loadAndVerifyDefinition(definitionPath);
  const planText = readFileSync(planPath, "utf8");
  const built = buildShardManifest({
    definitionPath,
    definitionText: loaded.text,
    definition: loaded.definition,
    planPath,
    planText,
    outputRoot,
  });
  const manifestPath = writeShardSet(outputRoot, built);
  process.stdout.write(
    `motion shards: wrote ${built.manifest.shardCount} shards for ` +
      `${built.manifest.rowCount} rows to ${manifestPath}\n`,
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
