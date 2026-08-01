#!/usr/bin/env node

import { createHash } from "node:crypto";
import {
  lstatSync,
  readdirSync,
  readFileSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { relative, resolve } from "node:path";
import { pathToFileURL } from "node:url";

function hashFile(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

function filesUnder(path) {
  const stat = lstatSync(path);
  if (stat.isSymbolicLink()) {
    throw new Error(`evidence path may not be a symbolic link: ${path}`);
  }
  if (stat.isFile()) return [path];
  if (!stat.isDirectory()) {
    throw new Error(`evidence path is not a regular file or directory: ${path}`);
  }
  return readdirSync(path, { withFileTypes: true })
    .sort((left, right) => left.name.localeCompare(right.name))
    .flatMap((entry) => filesUnder(resolve(path, entry.name)));
}

export function buildEvidenceManifest(paths, base = process.cwd()) {
  const files = [...new Set(paths.flatMap((path) => filesUnder(resolve(path))))];
  return {
    schemaVersion: "reframe4s.motion-superiority.evidence/v1",
    entries: files
      .map((path) => ({
        path: relative(resolve(base), path),
        bytes: statSync(path).size,
        sha256: hashFile(path),
      }))
      .sort((left, right) => left.path.localeCompare(right.path)),
  };
}

export function evidenceManifestErrors(manifest, base = process.cwd()) {
  const errors = [];
  if (manifest.schemaVersion !== "reframe4s.motion-superiority.evidence/v1") {
    errors.push("evidence manifest schemaVersion is invalid");
  }
  const paths = new Set();
  for (const [index, entry] of (manifest.entries ?? []).entries()) {
    if (paths.has(entry.path)) {
      errors.push(`evidence entry ${index} duplicates ${entry.path}`);
      continue;
    }
    paths.add(entry.path);
    const path = resolve(base, entry.path);
    try {
      const stat = lstatSync(path);
      if (!stat.isFile() || stat.isSymbolicLink()) {
        errors.push(`${entry.path} is not a regular evidence file`);
      } else {
        if (stat.size !== entry.bytes) {
          errors.push(`${entry.path} byte count differs`);
        }
        if (hashFile(path) !== entry.sha256) {
          errors.push(`${entry.path} sha256 differs`);
        }
      }
    } catch (error) {
      errors.push(`${entry.path}: ${String(error?.message ?? error)}`);
    }
  }
  if ((manifest.entries ?? []).length === 0) {
    errors.push("evidence manifest is empty");
  }
  return errors;
}

function values(arguments_, name) {
  const found = [];
  for (let index = 0; index < arguments_.length; index += 1) {
    if (arguments_[index] === name) {
      if (index + 1 >= arguments_.length) {
        throw new Error(`missing value after ${name}`);
      }
      found.push(arguments_[index + 1]);
      index += 1;
    }
  }
  return found;
}

function single(arguments_, name) {
  const found = values(arguments_, name);
  if (found.length !== 1) throw new Error(`${name} is required exactly once`);
  return found[0];
}

function main(arguments_) {
  const output = resolve(single(arguments_, "--output"));
  const roots = values(arguments_, "--include");
  if (roots.length === 0) throw new Error("at least one --include is required");
  const manifest = buildEvidenceManifest(roots);
  writeFileSync(output, `${JSON.stringify(manifest, null, 2)}\n`, {
    encoding: "utf8",
    flag: "wx",
  });
  process.stdout.write(
    `motion evidence manifest: wrote ${manifest.entries.length} hashes\n`,
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
