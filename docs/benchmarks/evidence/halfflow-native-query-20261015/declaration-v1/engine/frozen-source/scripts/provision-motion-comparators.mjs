#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { pathToFileURL } from "node:url";

function hashFile(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

export function provisioningErrors(lock, observations) {
  const errors = [];
  for (const implementation of lock.implementations ?? []) {
    if (implementation.id === "fsl_mcflirt") {
      if (
        observations.fileHashes?.fsl_mcflirt !==
        implementation.packageLockSha256
      ) {
        errors.push("FSL package lock differs from the admission lock");
      }
      continue;
    }
    const observed = observations.images?.[implementation.id];
    if (!observed || observed.exitStatus !== 0) {
      errors.push(`${implementation.id} pinned image is unavailable`);
      continue;
    }
    if (
      !Array.isArray(observed.repoDigests) ||
      !observed.repoDigests.some((digest) =>
        digest.endsWith(`@${implementation.digest}`),
      )
    ) {
      errors.push(
        `${implementation.id} local image digest differs from the admission lock`,
      );
    }
    if (
      implementation.id === "nifreeze" &&
      observations.fileHashes?.nifreeze !==
        implementation.dependencyLockSha256
    ) {
      errors.push("nifreeze dependency lock differs from the admission lock");
    }
  }
  return errors;
}

function run(engine, arguments_, inherit = false) {
  const result = spawnSync(engine, arguments_, {
    encoding: "utf8",
    stdio: inherit ? "inherit" : ["ignore", "pipe", "pipe"],
  });
  return {
    exitStatus: Number.isInteger(result.status) ? result.status : 127,
    stdout: result.stdout ?? "",
    stderr:
      result.stderr ??
      String(result.error?.message ?? ""),
  };
}

function inspect(engine, reference) {
  const result = run(engine, [
    "image",
    "inspect",
    "--format",
    "{{json .RepoDigests}}",
    reference,
  ]);
  let repoDigests = [];
  if (result.exitStatus === 0) {
    try {
      const parsed = JSON.parse(result.stdout.trim());
      if (Array.isArray(parsed)) repoDigests = parsed;
    } catch {
      repoDigests = [];
    }
  }
  return { exitStatus: result.exitStatus, repoDigests };
}

function option(arguments_, name) {
  const index = arguments_.indexOf(name);
  if (index < 0 || index + 1 >= arguments_.length) {
    throw new Error(`${name} is required`);
  }
  return arguments_[index + 1];
}

function main(arguments_) {
  const lockPath = resolve(option(arguments_, "--lock"));
  const pull = arguments_.includes("--pull");
  const lock = JSON.parse(readFileSync(lockPath, "utf8"));
  const base = dirname(lockPath);
  const engine = resolve(base, lock.runner?.containerEngine ?? "");
  const images = {};
  const fileHashes = {};
  for (const implementation of lock.implementations ?? []) {
    if (implementation.id === "fsl_mcflirt") {
      fileHashes.fsl_mcflirt = hashFile(
        resolve(base, implementation.packageLockPath),
      );
    } else {
      if (pull) {
        const pulled = run(
          engine,
          [
            "pull",
            "--platform",
            implementation.platform,
            implementation.imageReference,
          ],
          true,
        );
        if (pulled.exitStatus !== 0) {
          images[implementation.id] = {
            exitStatus: pulled.exitStatus,
            repoDigests: [],
          };
          continue;
        }
      }
      images[implementation.id] = inspect(
        engine,
        implementation.imageReference,
      );
      if (implementation.id === "nifreeze") {
        fileHashes.nifreeze = hashFile(
          resolve(base, implementation.dependencyLockPath),
        );
      }
    }
  }
  const errors = provisioningErrors(lock, { images, fileHashes });
  if (errors.length > 0) {
    for (const error of errors) process.stderr.write(`${error}\n`);
    process.exitCode = 1;
  } else {
    process.stdout.write(
      `motion comparators: exact pins verified${
        pull ? " after pull" : ""
      }\n`,
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
