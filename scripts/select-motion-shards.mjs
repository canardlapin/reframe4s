#!/usr/bin/env node

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

import {
  loadAndVerifyDefinition,
  regularFile,
  schemas,
  sha256File,
} from "./motion-shard-contract.mjs";

function option(arguments_, name) {
  const index = arguments_.indexOf(name);
  if (index < 0 || index + 1 >= arguments_.length) {
    throw new Error(`${name} is required`);
  }
  return arguments_[index + 1];
}

export function selectPendingShardIds(
  manifest,
  count,
  checkpointExists,
) {
  if (manifest?.schemaVersion !== schemas.manifest) {
    throw new Error("shard manifest schemaVersion is invalid");
  }
  if (
    !Number.isInteger(count) ||
    count < 1 ||
    count > manifest.maximumShardsPerInvocation
  ) {
    throw new Error(
      `count must be from 1 through ${manifest.maximumShardsPerInvocation}`,
    );
  }
  if (
    !Array.isArray(manifest.shards) ||
    manifest.shards.length !== manifest.shardCount
  ) {
    throw new Error("shard manifest count is invalid");
  }
  const selected = [];
  const ids = new Set();
  for (const [ordinal, shard] of manifest.shards.entries()) {
    if (
      shard.ordinal !== ordinal ||
      shard.id !== `shard-${String(ordinal).padStart(4, "0")}` ||
      ids.has(shard.id)
    ) {
      throw new Error(`shard manifest ordering is invalid at ${ordinal}`);
    }
    ids.add(shard.id);
    if (!checkpointExists(shard) && selected.length < count) {
      selected.push(shard.id);
    }
  }
  return selected;
}

export function loadSelectionManifest(manifestPath) {
  const path = resolve(manifestPath);
  const manifest = JSON.parse(readFileSync(path, "utf8"));
  if (manifest?.schemaVersion !== schemas.manifest) {
    throw new Error("shard manifest schemaVersion is invalid");
  }
  const loaded = loadAndVerifyDefinition(manifest.releaseExecutionPath);
  if (loaded.sha256 !== manifest.releaseExecutionSha256) {
    throw new Error("release execution hash differs from shard manifest");
  }
  if (
    loaded.definition.sharding.expectedShards !== manifest.shardCount ||
    loaded.definition.sharding.maximumShardsPerInvocation < 1
  ) {
    throw new Error("release execution sharding differs from manifest");
  }
  if (sha256File(manifest.masterPlanPath) !== manifest.masterPlanSha256) {
    throw new Error("master plan hash differs from shard manifest");
  }
  return {
    ...manifest,
    maximumShardsPerInvocation:
      loaded.definition.sharding.maximumShardsPerInvocation,
  };
}

function main(arguments_) {
  const manifest = loadSelectionManifest(option(arguments_, "--manifest"));
  const count = Number(option(arguments_, "--count"));
  const selected = selectPendingShardIds(
    manifest,
    count,
    (shard) =>
      regularFile(resolve(manifest.outputRoot, shard.checkpointFile)),
  );
  if (selected.length > 0) {
    process.stdout.write(`${selected.join("\n")}\n`);
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
