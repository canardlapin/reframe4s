#!/usr/bin/env node

import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { resolve } from "node:path";

import {
  buildEvidenceManifest,
  evidenceManifestErrors,
} from "./create-motion-evidence-manifest.mjs";

const root = mkdtempSync(resolve(tmpdir(), "motion-evidence-"));
writeFileSync(resolve(root, "a.txt"), "alpha");
writeFileSync(resolve(root, "b.txt"), "beta");
const manifest = buildEvidenceManifest([root], root);
assert.equal(manifest.entries.length, 2);
assert.deepEqual(evidenceManifestErrors(manifest, root), []);

writeFileSync(resolve(root, "a.txt"), "changed");
assert(
  evidenceManifestErrors(manifest, root).some((error) =>
    error.includes("differs"),
  ),
);

console.log("motion evidence-manifest tests: ok");
