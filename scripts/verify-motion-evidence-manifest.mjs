#!/usr/bin/env node

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

import {
  evidenceManifestErrors,
} from "./create-motion-evidence-manifest.mjs";

function main(arguments_) {
  if (arguments_.length !== 1) {
    throw new Error("provide exactly one evidence-manifest path");
  }
  const path = resolve(arguments_[0]);
  const manifest = JSON.parse(readFileSync(path, "utf8"));
  const errors = evidenceManifestErrors(manifest);
  if (errors.length > 0) {
    for (const error of errors) process.stderr.write(`${error}\n`);
    process.exitCode = 1;
  } else {
    process.stdout.write(
      `motion evidence manifest: verified ${manifest.entries.length} files\n`,
    );
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
