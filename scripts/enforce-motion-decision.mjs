#!/usr/bin/env node

import { readFileSync } from "node:fs";

if (process.argv.length !== 3) {
  process.stderr.write("provide exactly one motion receipt\n");
  process.exitCode = 1;
} else {
  try {
    const receipt = JSON.parse(readFileSync(process.argv[2], "utf8"));
    const status = receipt.claimDecision?.status;
    if (status === "passed") {
      process.stdout.write("motion superiority decision: passed\n");
    } else if (status === "failed") {
      process.stderr.write("motion superiority decision: failed\n");
      process.exitCode = 3;
    } else if (status === "inadmissible") {
      process.stderr.write("motion superiority decision: inadmissible\n");
      process.exitCode = 2;
    } else {
      process.stderr.write(`unknown motion decision: ${String(status)}\n`);
      process.exitCode = 1;
    }
  } catch (error) {
    process.stderr.write(`${String(error?.message ?? error)}\n`);
    process.exitCode = 1;
  }
}
