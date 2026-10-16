#!/usr/bin/env node

import { createHash } from "node:crypto";
import {
  accessSync,
  constants,
  existsSync,
  readFileSync,
  statSync,
} from "node:fs";
import os from "node:os";
import { delimiter, dirname, resolve } from "node:path";
import { execFileSync, spawnSync } from "node:child_process";
import { pathToFileURL } from "node:url";

import {
  admissionLockErrors,
  parseProtocol,
  validateProtocol,
} from "./verify-motion-superiority-protocol.mjs";

function hashFile(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

function hashBytes(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function regularExecutable(path, label) {
  try {
    if (!statSync(path).isFile()) return [`${label} is not a regular file`];
    accessSync(path, constants.X_OK);
    return [];
  } catch (error) {
    return [`${label}: ${String(error?.message ?? error)}`];
  }
}

function existingPath(path, label) {
  try {
    statSync(path);
    accessSync(path, constants.R_OK);
    return [];
  } catch (error) {
    return [`${label}: ${String(error?.message ?? error)}`];
  }
}

function directory(path, label) {
  try {
    if (!statSync(path).isDirectory()) return [`${label} is not a directory`];
    accessSync(path, constants.R_OK | constants.X_OK);
    return [];
  } catch (error) {
    return [`${label}: ${String(error?.message ?? error)}`];
  }
}

export function preflightErrors({
  lock,
  protocolSha256,
  gitRevision,
  gitClean,
  candidateArtifactSha256,
  assetManifestSha256,
  environmentManifestSha256,
  hardware,
  executableErrors = [],
  versionProbes = {},
}) {
  const errors = [...executableErrors];
  if (lock.protocol?.sha256 !== protocolSha256) {
    errors.push("preflight protocol hash differs from the admission lock");
  }
  if (lock.candidate?.revision !== gitRevision) {
    errors.push("checked-out candidate revision differs from the admission lock");
  }
  if (!gitClean || lock.candidate?.clean !== true) {
    errors.push("candidate worktree is not clean");
  }
  if (lock.candidate?.artifactSha256 !== candidateArtifactSha256) {
    errors.push("candidate artifact hash differs from the admission lock");
  }
  if (
    lock.benchmarkAssets?.manifestSha256 !== assetManifestSha256
  ) {
    errors.push("asset manifest hash differs from the admission lock");
  }
  if (lock.dataLockSha256 !== assetManifestSha256) {
    errors.push("data lock hash differs from the asset manifest");
  }
  if (lock.environmentLockSha256 !== environmentManifestSha256) {
    errors.push("environment manifest hash differs from the admission lock");
  }
  for (const field of [
    "hostId",
    "cpuModel",
    "physicalCores",
    "cpuSet",
    "simultaneousMultithreadingExcluded",
    "operatingSystem",
    "kernel",
    "firmware",
    "cpuGovernor",
    "thermalPolicy",
    "swapDisabled",
    "memoryBytes",
    "filesystem",
    "pageCachePolicy",
    "jvmVersion",
    "nodeVersion",
  ]) {
    if (String(lock.hardware?.[field]) !== String(hardware[field])) {
      errors.push(`live hardware ${field} differs from the admission lock`);
    }
  }
  const lockedVersions = new Map([
    ["reframe4s_motion", lock.candidate],
    ...(lock.implementations ?? []).map((entry) => [entry.id, entry]),
  ]);
  for (const implementation of [
    "reframe4s_motion",
    "afni_3dvolreg",
    "nifreeze",
    "fsl_mcflirt",
  ]) {
    const observed = versionProbes[implementation];
    const locked = lockedVersions.get(implementation);
    if (!observed) {
      errors.push(`${implementation} version probe is absent`);
      continue;
    }
    if (observed.exitStatus !== 0) {
      errors.push(`${implementation} version probe exited ${observed.exitStatus}`);
    }
    if (observed.stdoutSha256 !== locked?.versionStdoutSha256) {
      errors.push(`${implementation} version stdout differs from the lock`);
    }
    if (observed.stderrSha256 !== locked?.versionStderrSha256) {
      errors.push(`${implementation} version stderr differs from the lock`);
    }
  }
  return errors;
}

function git(path, ...arguments_) {
  return execFileSync("git", ["-C", path, ...arguments_], {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  }).trim();
}

function resolveLocked(base, value) {
  return resolve(base, value ?? "");
}

function resolveClasspath(base, value) {
  return (value ?? "")
    .split(delimiter)
    .map((entry) => resolveLocked(base, entry))
    .join(delimiter);
}

function probe(command, workingDirectory, environment = {}) {
  const result = spawnSync(command[0], command.slice(1), {
    cwd: workingDirectory,
    env: { ...process.env, ...environment },
    encoding: null,
    maxBuffer: 16 * 1024 * 1024,
  });
  const stdout = result.stdout ?? Buffer.from("");
  const stderr = result.stderr ?? Buffer.from(
    String(result.error?.message ?? ""),
  );
  return {
    exitStatus: Number.isInteger(result.status) ? result.status : 127,
    stdoutSha256: hashBytes(stdout),
    stderrSha256: hashBytes(stderr),
  };
}

function commandOutput(executable, arguments_) {
  const result = spawnSync(executable, arguments_, {
    encoding: null,
    stdio: ["ignore", "pipe", "pipe"],
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(
      `${executable} ${arguments_.join(" ")} exited ${result.status}`,
    );
  }
  return Buffer.concat([
    result.stdout ?? Buffer.from(""),
    result.stderr ?? Buffer.from(""),
  ])
    .toString("utf8")
    .trim();
}

function physicalCoreCount() {
  const rows = commandOutput("lscpu", ["-p=core,socket"])
    .split(/\r?\n/)
    .filter((line) => line.length > 0 && !line.startsWith("#"));
  return new Set(rows).size;
}

function cpuGovernor() {
  const path =
    "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor";
  if (existsSync(path)) return readFileSync(path, "utf8").trim();
  return process.env.MOTION_BENCHMARK_CPU_GOVERNOR ?? "unavailable";
}

function expandCpuList(value) {
  const cpus = new Set();
  for (const part of value.split(",")) {
    const range = part.trim().split("-");
    const start = Number(range[0]);
    const end = Number(range[1] ?? range[0]);
    if (
      !Number.isInteger(start) ||
      !Number.isInteger(end) ||
      start < 0 ||
      end < start
    ) {
      throw new Error(`invalid CPU list ${value}`);
    }
    for (let cpu = start; cpu <= end; cpu += 1) cpus.add(cpu);
  }
  return cpus;
}

function cpuSetExcludesSiblings(cpuSet) {
  const selected = expandCpuList(cpuSet);
  for (const cpu of selected) {
    const path =
      `/sys/devices/system/cpu/cpu${cpu}/topology/thread_siblings_list`;
    const siblings = expandCpuList(readFileSync(path, "utf8").trim());
    const selectedSiblings = [...siblings].filter((value) =>
      selected.has(value),
    );
    if (selectedSiblings.length !== 1) return false;
  }
  return true;
}

function firmware() {
  const path = "/sys/class/dmi/id/bios_version";
  return existsSync(path)
    ? readFileSync(path, "utf8").trim()
    : "unavailable";
}

function swapDisabled() {
  const path = "/proc/swaps";
  if (!existsSync(path)) return false;
  return readFileSync(path, "utf8").trim().split(/\r?\n/).length === 1;
}

function liveHardware(repository, javaExecutable, lockedHardware) {
  const cpus = os.cpus();
  const cpuSet = String(lockedHardware.cpuSet);
  return {
    hostId: os.hostname(),
    cpuModel: cpus[0]?.model ?? "unavailable",
    physicalCores: physicalCoreCount(),
    cpuSet,
    simultaneousMultithreadingExcluded:
      cpuSetExcludesSiblings(cpuSet),
    operatingSystem: process.platform,
    kernel: os.release(),
    firmware: firmware(),
    cpuGovernor: cpuGovernor(),
    thermalPolicy: lockedHardware.thermalPolicy,
    swapDisabled: swapDisabled(),
    memoryBytes: os.totalmem(),
    filesystem: commandOutput("findmnt", [
      "-n",
      "-o",
      "FSTYPE",
      "--target",
      repository,
    ]),
    pageCachePolicy: lockedHardware.pageCachePolicy,
    jvmVersion: commandOutput(javaExecutable, ["-version"]),
    nodeVersion: process.version,
  };
}

function main(arguments_) {
  const lockIndex = arguments_.indexOf("--lock");
  if (lockIndex < 0 || lockIndex + 1 >= arguments_.length) {
    throw new Error("--lock is required");
  }
  const lockPath = resolve(arguments_[lockIndex + 1]);
  const protocolIndex = arguments_.indexOf("--protocol");
  const protocolPath =
    protocolIndex < 0
      ? resolve("benchmarks/motion/protocol-v2.json")
      : resolve(arguments_[protocolIndex + 1]);
  const repositoryIndex = arguments_.indexOf("--repository");
  const repository =
    repositoryIndex < 0 ? resolve(".") : resolve(arguments_[repositoryIndex + 1]);
  const protocolText = readFileSync(protocolPath, "utf8");
  const protocol = parseProtocol(protocolText);
  const protocolSha256 = createHash("sha256")
    .update(protocolText)
    .digest("hex");
  const lock = JSON.parse(readFileSync(lockPath, "utf8"));
  const base = dirname(lockPath);
  const artifact = resolveLocked(base, lock.candidate?.artifactPath);
  const assets = resolveLocked(
    base,
    lock.benchmarkAssets?.manifestPath,
  );
  const environmentManifest = resolveLocked(
    base,
    lock.environmentManifestPath,
  );
  const runner = lock.runner ?? {};
  const workingDirectory = resolveLocked(base, runner.workingDirectory);
  const containerEngine = resolveLocked(base, runner.containerEngine);
  const affinityExecutable = resolveLocked(
    base,
    runner.affinityExecutable,
  );
  const javaExecutable = resolveLocked(base, runner.javaExecutable);
  const candidateClasspath = resolveClasspath(
    base,
    runner.candidateClasspath,
  );
  const afniExecutable = resolveLocked(base, runner.afniExecutable);
  const mcflirtExecutable = resolveLocked(base, runner.mcflirtExecutable);
  const pythonExecutable = resolveLocked(base, runner.pythonExecutable);
  const nifreezeWrapper = resolveLocked(base, runner.nifreezeWrapper);
  const executables = [
    [containerEngine, "container engine"],
    [affinityExecutable, "CPU-affinity executable"],
    [javaExecutable, "Java executable"],
    [afniExecutable, "AFNI executable"],
    [mcflirtExecutable, "MCFLIRT executable"],
    [pythonExecutable, "Python executable"],
    [nifreezeWrapper, "nifreeze wrapper"],
  ];
  const executableErrors = [
    ...executables.flatMap(([path, label]) =>
      regularExecutable(path, label),
    ),
    ...candidateClasspath
      .split(delimiter)
      .flatMap((path, index) =>
        existingPath(path, `candidate classpath entry ${index}`),
      ),
    ...directory(workingDirectory, "runner working directory"),
  ];
  const gitRevision = git(repository, "rev-parse", "HEAD");
  const versionProbes =
    executableErrors.length === 0
      ? {
          reframe4s_motion: probe(
            [
              javaExecutable,
              "-cp",
              candidateClasspath,
              "reframe4s.benchmark.motion.MotionBenchmarkMain",
              "--version",
            ],
            workingDirectory,
            { REFRAME4S_CANDIDATE_REVISION: gitRevision },
          ),
          afni_3dvolreg: probe(
            [afniExecutable, "-help"],
            workingDirectory,
          ),
          nifreeze: probe(
            [pythonExecutable, nifreezeWrapper, "--probe"],
            workingDirectory,
          ),
          fsl_mcflirt: probe(
            [mcflirtExecutable, "-help"],
            workingDirectory,
          ),
        }
      : {};
  const hardware =
    executableErrors.length === 0
      ? liveHardware(repository, javaExecutable, lock.hardware)
      : {};
  const errors = [
    ...validateProtocol(protocol),
    ...admissionLockErrors(protocol, lock, protocolSha256),
    ...preflightErrors({
      lock,
      protocolSha256,
      gitRevision,
      gitClean: git(repository, "status", "--porcelain").length === 0,
      candidateArtifactSha256: hashFile(artifact),
      assetManifestSha256: hashFile(assets),
      environmentManifestSha256: hashFile(environmentManifest),
      hardware,
      executableErrors,
      versionProbes,
    }),
  ];
  if (errors.length > 0) {
    for (const error of errors) process.stderr.write(`${error}\n`);
    process.exitCode = 1;
  } else {
    process.stdout.write(
      `motion court preflight: ok (${protocol.protocolId} ${protocol.version})\n`,
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
