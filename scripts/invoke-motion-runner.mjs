#!/usr/bin/env node

import { readFileSync } from "node:fs";
import { delimiter, dirname, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { pathToFileURL } from "node:url";

const mainClass = "reframe4s.benchmark.motion.MotionBenchmarkMain";
const lockedOptions = new Set([
  "--working-directory",
  "--nifreeze-wrapper",
  "--afni",
  "--mcflirt",
  "--python",
  "--java",
  "--candidate-classpath",
]);

function resolveLocked(base, value) {
  return resolve(base, value ?? "");
}

function resolveClasspath(base, value) {
  return (value ?? "")
    .split(delimiter)
    .map((entry) => resolveLocked(base, entry))
    .join(delimiter);
}

export function runnerInvocation(lock, lockPath, arguments_) {
  if (arguments_.length === 0) throw new Error("runner command is required");
  if (arguments_.some((argument) => lockedOptions.has(argument))) {
    throw new Error("runner adapter paths are supplied only by the admission lock");
  }
  const base = dirname(resolve(lockPath));
  const runner = lock.runner ?? {};
  const command = arguments_[0];
  const adapterArguments =
    command === "execute-plan" || command === "run"
      ? [
          "--working-directory",
          resolveLocked(base, runner.workingDirectory),
          "--nifreeze-wrapper",
          resolveLocked(base, runner.nifreezeWrapper),
          "--afni",
          resolveLocked(base, runner.afniExecutable),
          "--mcflirt",
          resolveLocked(base, runner.mcflirtExecutable),
          "--python",
          resolveLocked(base, runner.pythonExecutable),
          "--java",
          resolveLocked(base, runner.javaExecutable),
          "--candidate-classpath",
          resolveClasspath(base, runner.candidateClasspath),
        ]
      : [];
  return {
    executable: resolveLocked(base, runner.affinityExecutable),
    arguments: [
      "--cpu-list",
      lock.hardware.cpuSet,
      resolveLocked(base, runner.javaExecutable),
      "-cp",
      resolveClasspath(base, runner.candidateClasspath),
      mainClass,
      ...arguments_,
      ...adapterArguments,
    ],
    workingDirectory: resolveLocked(base, runner.workingDirectory),
    environment: {
      REFRAME4S_CANDIDATE_REVISION: lock.candidate.revision,
      MOTION_BENCHMARK_CPU_SET: lock.hardware.cpuSet,
      MOTION_BENCHMARK_PAGE_CACHE_POLICY: lock.hardware.pageCachePolicy,
    },
  };
}

function option(arguments_, name) {
  const index = arguments_.indexOf(name);
  if (index < 0 || index + 1 >= arguments_.length) {
    throw new Error(`${name} is required`);
  }
  return { value: arguments_[index + 1], index };
}

function main(arguments_) {
  const lockOption = option(arguments_, "--lock");
  const separator = arguments_.indexOf("--");
  if (separator < 0 || separator + 1 >= arguments_.length) {
    throw new Error("separate runner arguments with --");
  }
  const lockPath = resolve(lockOption.value);
  const lock = JSON.parse(readFileSync(lockPath, "utf8"));
  const invocation = runnerInvocation(
    lock,
    lockPath,
    arguments_.slice(separator + 1),
  );
  const result = spawnSync(
    invocation.executable,
    invocation.arguments,
    {
      cwd: invocation.workingDirectory,
      env: { ...process.env, ...invocation.environment },
      stdio: "inherit",
    },
  );
  process.exitCode = Number.isInteger(result.status) ? result.status : 127;
  if (result.error) {
    process.stderr.write(`${result.error.message}\n`);
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
