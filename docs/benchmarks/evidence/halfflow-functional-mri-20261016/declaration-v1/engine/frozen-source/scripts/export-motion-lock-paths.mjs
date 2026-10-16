#!/usr/bin/env node

import { appendFileSync, readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

export function lockedPaths(lock, lockPath) {
  const base = dirname(resolve(lockPath));
  return {
    MOTION_ASSETS_PATH: resolve(
      base,
      lock.benchmarkAssets.manifestPath,
    ),
    MOTION_ENVIRONMENT_MANIFEST_PATH: resolve(
      base,
      lock.environmentManifestPath,
    ),
  };
}

function main(arguments_) {
  if (arguments_.length !== 1) {
    throw new Error("provide exactly one admission-lock path");
  }
  const lockPath = resolve(arguments_[0]);
  const lock = JSON.parse(readFileSync(lockPath, "utf8"));
  const paths = lockedPaths(lock, lockPath);
  const output = process.env.GITHUB_ENV;
  if (!output) throw new Error("GITHUB_ENV is unavailable");
  for (const [name, value] of Object.entries(paths)) {
    if (value.includes("\n") || value.includes("\r")) {
      throw new Error(`${name} contains a newline`);
    }
    appendFileSync(output, `${name}=${value}\n`, "utf8");
  }
  process.stdout.write("motion lock paths exported\n");
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
