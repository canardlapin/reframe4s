#!/usr/bin/env node

import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { resolve, relative } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(fileURLToPath(new URL("..", import.meta.url)));
const sha256 = (bytes) => createHash("sha256").update(bytes).digest("hex");

/** Bind evidence to source contents, including uncommitted/new source files.
 * The receipt and prose are excluded so recording evidence cannot change the
 * candidate it describes. Dependency revisions are included via build.sbt.
 */
export function candidateSnapshot() {
  const files = execFileSync("git", ["ls-files", "-z", "--cached", "--others", "--exclude-standard"], { cwd: root })
    .toString().split("\0").filter(Boolean);
  const manifest = {};
  for (const file of [...new Set(files)].sort()) {
    if (!/^(?:modules\/|project\/|scripts\/|benchmarks\/[^/]+\/(?:src|runner)\/|\.github\/workflows\/|build\.sbt$|PRD\.json$)/u.test(file)) continue;
    if (file.split("/").includes("target")) continue;
    try { manifest[file] = sha256(readFileSync(resolve(root, file))); }
    catch (error) { if (error.code !== "ENOENT") throw error; }
  }
  return { sha256: sha256(JSON.stringify(manifest)), files: manifest };
}

export function verifyReceipt(receipt, snapshot = candidateSnapshot()) {
  if (receipt.candidate.sha256 !== snapshot.sha256 ||
      JSON.stringify(receipt.candidate.files) !== JSON.stringify(snapshot.files)) {
    throw new Error("HalfFlow evidence does not match this source candidate; rerun numerical and ownership checks.");
  }
  for (const platform of ["JVM", "JS"]) {
    const check = receipt.numerical[platform];
    if (!check || check.failed !== 0 || check.errors !== 0 || check.passed < 1 ||
        !check.suites.some(suite => suite.name === "reframe4s.halfflow.CanonicalGeometrySuite" && suite.passed >= 6)) {
      throw new Error(`Missing passing ${platform} migration evidence.`);
    }
  }
  if (receipt.ownership.exitCode !== 0) throw new Error("Missing passing ownership evidence.");
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const receiptPath = process.argv[2];
  if (!receiptPath) process.stdout.write(`${JSON.stringify(candidateSnapshot(), null, 2)}\n`);
  else {
    verifyReceipt(JSON.parse(readFileSync(receiptPath, "utf8")));
    process.stdout.write(`HalfFlow numerical and ownership evidence matches candidate in ${relative(root, resolve(receiptPath))}.\n`);
  }
}
