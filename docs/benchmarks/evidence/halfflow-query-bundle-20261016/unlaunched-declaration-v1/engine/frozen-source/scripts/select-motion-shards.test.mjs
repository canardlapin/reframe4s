#!/usr/bin/env node

import assert from "node:assert/strict";

import { schemas } from "./motion-shard-contract.mjs";
import { selectPendingShardIds } from "./select-motion-shards.mjs";

const shards = Array.from({ length: 7 }, (_, ordinal) => ({
  id: `shard-${String(ordinal).padStart(4, "0")}`,
  ordinal,
}));
const manifest = {
  schemaVersion: schemas.manifest,
  shardCount: shards.length,
  maximumShardsPerInvocation: 4,
  shards,
};
const complete = new Set(["shard-0000", "shard-0002", "shard-0005"]);

assert.deepEqual(
  selectPendingShardIds(
    manifest,
    4,
    (shard) => complete.has(shard.id),
  ),
  ["shard-0001", "shard-0003", "shard-0004", "shard-0006"],
);
assert.deepEqual(
  selectPendingShardIds(
    manifest,
    2,
    (shard) => complete.has(shard.id),
  ),
  ["shard-0001", "shard-0003"],
);
assert.deepEqual(
  selectPendingShardIds(manifest, 4, () => true),
  [],
);
assert.throws(
  () => selectPendingShardIds(manifest, 5, () => false),
  /count must be from 1 through 4/,
);
assert.throws(
  () =>
    selectPendingShardIds(
      {
        ...manifest,
        shards: [
          shards[0],
          { ...shards[1], id: "shard-0000" },
          ...shards.slice(2),
        ],
      },
      1,
      () => false,
    ),
  /ordering is invalid/,
);

console.log("motion pending-shard selection tests: ok");
