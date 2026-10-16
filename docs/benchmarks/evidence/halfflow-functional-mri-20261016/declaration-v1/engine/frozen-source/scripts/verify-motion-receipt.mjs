#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

import { analyzeMotionCourt } from "./analyze-motion-court.mjs";
import {
  applySourceLoadErrors,
  buildReceipt,
  loadReceiptInputs,
} from "./build-motion-receipt.mjs";

export function receiptErrors(receipt, expected) {
  const errors = [];
  if (JSON.stringify(receipt) !== JSON.stringify(expected)) {
    errors.push(
      "receipt differs from a fresh deterministic evaluation of its locked sources",
    );
  }
  if (
    receipt.claimDecision?.passed !==
    receipt.claimDecision?.completePassRuleSatisfied
  ) {
    errors.push("receipt pass flag contradicts the complete pass rule");
  }
  if (
    receipt.claimDecision?.status === "passed" &&
    receipt.hypotheses?.headline?.some((result) => !result.passed)
  ) {
    errors.push("passed receipt contains a failed headline hypothesis");
  }
  if (
    receipt.claimDecision?.status === "inadmissible" &&
    (receipt.failures?.analysisErrors?.length ?? 0) === 0
  ) {
    errors.push("inadmissible receipt has no visible analysis error");
  }
  return errors;
}

function option(arguments_, name) {
  const index = arguments_.indexOf(name);
  if (index < 0 || index + 1 >= arguments_.length) {
    throw new Error(`${name} is required`);
  }
  return arguments_[index + 1];
}

function main(arguments_) {
  const receiptPath = option(arguments_, "--receipt");
  const loaded = loadReceiptInputs(arguments_);
  const analysis = applySourceLoadErrors(
    analyzeMotionCourt({
      protocol: loaded.protocol,
      assets: loaded.assets,
      runs: loaded.runs,
      metricRecords: loaded.metricRecords,
    }),
    loaded.sourceLoadErrors,
  );
  const expected = buildReceipt({ ...loaded, analysis });
  const text = readFileSync(receiptPath, "utf8");
  const receipt = JSON.parse(text);
  const errors = receiptErrors(receipt, expected);
  if (errors.length > 0) {
    for (const error of errors) process.stderr.write(`${error}\n`);
    process.exitCode = 1;
  } else {
    const sha256 = createHash("sha256").update(text).digest("hex");
    process.stdout.write(
      `motion receipt: verified ${receipt.claimDecision.status} sha256 ${sha256}\n`,
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
