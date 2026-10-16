#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFileSync, statSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

import {
  admissionLockErrors,
  parseProtocol,
  validateProtocol,
} from "./verify-motion-superiority-protocol.mjs";
import { readPlan } from "./verify-motion-run-records.mjs";

const sha256Pattern = /^[0-9a-f]{64}$/;
const revisionPattern = /^[0-9a-f]{40}$/;
const fileFields = [
  "input",
  "motionFreeTruth",
  "truthPoses",
  "mask",
  "landmarks",
];

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function unique(values) {
  return new Set(values).size === values.length;
}

function fileErrors(file, context) {
  const errors = [];
  if (!isObject(file)) return [`${context} must be a file record`];
  if (typeof file.path !== "string" || file.path.length === 0) {
    errors.push(`${context} path is missing`);
  }
  if (!sha256Pattern.test(file.sha256 ?? "")) {
    errors.push(`${context} sha256 is invalid`);
  }
  if (!Number.isSafeInteger(file.bytes) || file.bytes <= 0) {
    errors.push(`${context} byte count is invalid`);
  }
  return errors;
}

export function validateAssetManifest(manifest, protocol, lock) {
  const errors = [];
  if (!isObject(manifest)) return ["asset manifest root must be an object"];
  if (manifest.schemaVersion !== "reframe4s.motion-superiority.assets/v1") {
    errors.push("asset manifest schemaVersion is invalid");
  }
  const scenarioIds = (protocol.scenarioGeneration?.scenarios ?? []).map(
    (scenario) => scenario.id,
  );
  const assets = manifest.assets ?? [];
  if (!Array.isArray(assets)) return [...errors, "assets must be an array"];
  const keys = [];
  for (const [index, asset] of assets.entries()) {
    const context = `asset ${index}`;
    if (!isObject(asset)) {
      errors.push(`${context} must be an object`);
      continue;
    }
    if (typeof asset.subject !== "string" || asset.subject.length === 0) {
      errors.push(`${context} subject is missing`);
    }
    if (!scenarioIds.includes(asset.scenario)) {
      errors.push(`${context} scenario is not registered`);
    }
    if (!["synthetic", "real_anatomy"].includes(asset.stratum)) {
      errors.push(`${context} stratum is invalid`);
    }
    keys.push(`${asset.subject}\u0000${asset.scenario}`);
    for (const field of fileFields) {
      errors.push(...fileErrors(asset[field], `${context} ${field}`));
    }
    const provenance = asset.provenance;
    if (!isObject(provenance)) {
      errors.push(`${context} provenance is missing`);
    } else {
      for (const field of [
        "dataset",
        "datasetVersion",
        "sourceUrl",
        "license",
        "redistribution",
        "sourceSubject",
        "sourceRun",
      ]) {
        if (
          typeof provenance[field] !== "string" ||
          provenance[field].length === 0
        ) {
          errors.push(`${context} provenance ${field} is missing`);
        }
      }
      if (provenance.redistribution !== "permitted") {
        errors.push(
          `${context} provenance does not permit receipt redistribution`,
        );
      }
      if (!revisionPattern.test(provenance.generatorRevision ?? "")) {
        errors.push(`${context} generator revision is invalid`);
      }
    }
  }
  if (!unique(keys)) errors.push("asset subject-scenario keys must be unique");

  const bySubject = Map.groupBy(assets, (asset) => asset.subject);
  const expectedSyntheticSubjects = new Set(
    (protocol.data?.syntheticSubjects?.seeds ?? []).map(
      (seed) => `synthetic-${seed}`,
    ),
  );
  for (const subject of expectedSyntheticSubjects) {
    const rows = bySubject.get(subject) ?? [];
    if (
      rows.length !== scenarioIds.length ||
      !rows.every((row) => row.stratum === "synthetic") ||
      !scenarioIds.every((scenario) =>
        rows.some((row) => row.scenario === scenario),
      )
    ) {
      errors.push(`${subject} does not cover every synthetic scenario`);
    }
  }
  const unexpectedSynthetic = assets
    .filter((asset) => asset.stratum === "synthetic")
    .map((asset) => asset.subject)
    .filter((subject) => !expectedSyntheticSubjects.has(subject));
  if (unexpectedSynthetic.length > 0) {
    errors.push("asset manifest contains an unregistered synthetic subject");
  }

  const realPairs = new Set(
    (lock.realAnatomy?.manifest ?? []).map(
      (row) => `${row.subject}\u0000${row.run}`,
    ),
  );
  const realBySource = Map.groupBy(
    assets.filter((asset) => asset.stratum === "real_anatomy"),
    (asset) =>
      `${asset.provenance?.sourceSubject}\u0000${asset.provenance?.sourceRun}`,
  );
  for (const pair of realPairs) {
    const rows = realBySource.get(pair) ?? [];
    if (
      rows.length !== scenarioIds.length ||
      !scenarioIds.every((scenario) =>
        rows.some((row) => row.scenario === scenario),
      )
    ) {
      errors.push(`real-anatomy source ${pair} does not cover every scenario`);
    }
  }
  for (const pair of realBySource.keys()) {
    if (!realPairs.has(pair)) {
      errors.push(`asset manifest contains unlocked real-anatomy source ${pair}`);
    }
  }
  return errors;
}

function hashFile(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

export function verifyAssetFiles(manifest, manifestPath) {
  const errors = [];
  const base = dirname(resolve(manifestPath));
  for (const asset of manifest.assets ?? []) {
    for (const field of fileFields) {
      const file = asset[field];
      if (!isObject(file) || typeof file.path !== "string") continue;
      const path = resolve(base, file.path);
      try {
        const stat = statSync(path);
        if (!stat.isFile()) errors.push(`${path} is not a regular file`);
        if (stat.size !== file.bytes) {
          errors.push(`${path} byte count differs from the manifest`);
        }
        if (hashFile(path) !== file.sha256) {
          errors.push(`${path} sha256 differs from the manifest`);
        }
      } catch (error) {
        errors.push(`${path}: ${String(error?.message ?? error)}`);
      }
    }
  }
  return errors;
}

export function validatePlanAssets(planRows, manifest, manifestPath) {
  const errors = [];
  const base = dirname(resolve(manifestPath));
  const assets = new Map(
    (manifest.assets ?? []).map((asset) => [
      `${asset.subject}\u0000${asset.scenario}`,
      asset,
    ]),
  );
  const workloads = new Map();
  for (const row of planRows) {
    workloads.set(`${row.subject}\u0000${row.scenario}`, row);
  }
  for (const [key, row] of workloads) {
    const asset = assets.get(key);
    if (!asset) {
      errors.push(`plan workload ${key} has no locked asset`);
      continue;
    }
    if (resolve(row.input) !== resolve(base, asset.input.path)) {
      errors.push(`plan workload ${key} input differs from the asset lock`);
    }
    if (resolve(row.mask) !== resolve(base, asset.mask.path)) {
      errors.push(`plan workload ${key} mask differs from the asset lock`);
    }
  }
  if (workloads.size !== assets.size) {
    errors.push(
      `plan has ${workloads.size} unique workloads but asset lock has ${assets.size}`,
    );
  }
  return errors;
}

function option(arguments_, name, fallback = null) {
  const index = arguments_.indexOf(name);
  if (index < 0) return fallback;
  if (index + 1 >= arguments_.length) {
    throw new Error(`missing value after ${name}`);
  }
  return arguments_[index + 1];
}

function main(arguments_) {
  const protocolPath = option(
    arguments_,
    "--protocol",
    "benchmarks/motion/protocol-v2.json",
  );
  const lockPath = option(arguments_, "--lock");
  if (!lockPath) throw new Error("--lock is required");
  const protocolText = readFileSync(protocolPath, "utf8");
  const protocol = parseProtocol(protocolText);
  const protocolSha256 = createHash("sha256")
    .update(protocolText)
    .digest("hex");
  const lock = JSON.parse(readFileSync(lockPath, "utf8"));
  const assetPath = resolve(
    dirname(resolve(lockPath)),
    lock.benchmarkAssets?.manifestPath ?? "",
  );
  const assetText = readFileSync(assetPath, "utf8");
  const assetSha256 = createHash("sha256").update(assetText).digest("hex");
  const manifest = JSON.parse(assetText);
  const errors = [
    ...validateProtocol(protocol),
    ...admissionLockErrors(protocol, lock, protocolSha256),
    ...validateAssetManifest(manifest, protocol, lock),
  ];
  if (manifest.protocolSha256 !== protocolSha256) {
    errors.push("asset manifest protocol sha256 differs from the frozen file");
  }
  if (assetSha256 !== lock.benchmarkAssets?.manifestSha256) {
    errors.push("asset manifest sha256 differs from the admission lock");
  }
  if (arguments_.includes("--verify-files")) {
    errors.push(...verifyAssetFiles(manifest, assetPath));
  }
  const planPath = option(arguments_, "--plan");
  if (planPath) {
    errors.push(...validatePlanAssets(readPlan(planPath), manifest, assetPath));
  }
  if (errors.length > 0) {
    for (const error of errors) process.stderr.write(`${error}\n`);
    process.exitCode = 1;
  } else {
    process.stdout.write(
      `motion asset lock: ok (${manifest.assets.length} workloads)\n`,
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
