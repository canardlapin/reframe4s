#!/usr/bin/env node

import { createHash } from "node:crypto";
import {
  existsSync,
  lstatSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  statfsSync,
  writeFileSync,
} from "node:fs";
import { hostname, platform, arch, release } from "node:os";
import { dirname, relative, resolve } from "node:path";
import { spawn, spawnSync } from "node:child_process";
import { pathToFileURL } from "node:url";

const defaultDefinition =
  "benchmarks/motion/execution-tiers-v1.json";
const receiptSchema = "reframe4s.motion.laptop-receipt/v1";
const diagnosticPrefixes = {
  sixDof: "MIG-423 six-DOF diagnostic:",
  capture: "MIG-423 capture court:",
  pair: "MIG-414 pair JVM baseline:",
  application: "MIG-414 application JVM baseline:",
  kernel: "MIG-422 normal-kernel JVM baseline:",
};

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function isFiniteNumber(value) {
  return typeof value === "number" && Number.isFinite(value);
}

function requiredNumber(errors, object, key, context) {
  if (!isFiniteNumber(object?.[key])) {
    errors.push(`${context}.${key} must be a finite number`);
  }
}

function requiredInteger(errors, object, key, context) {
  requiredNumber(errors, object, key, context);
  if (isFiniteNumber(object?.[key]) && !Number.isInteger(object[key])) {
    errors.push(`${context}.${key} must be an integer`);
  }
}

export function definitionErrors(definition) {
  const errors = [];
  if (
    definition?.schemaVersion !==
    "reframe4s.motion.execution-tiers/v1"
  ) {
    errors.push("schemaVersion is invalid");
  }
  const laptop = definition?.laptopRegression;
  const releaseCourt = definition?.releaseSuperiority;
  if (laptop?.claimAuthority !== "none") {
    errors.push("laptopRegression.claimAuthority must be none");
  }
  if (laptop?.fixtures !== "deterministic-in-memory-analytic-only") {
    errors.push("laptopRegression must use only in-memory analytic fixtures");
  }
  for (const key of [
    "externalDatasets",
    "externalComparators",
    "niftiOutputs",
  ]) {
    if (laptop?.[key] !== false) {
      errors.push(`laptopRegression.${key} must be false`);
    }
  }
  requiredInteger(
    errors,
    laptop,
    "jvmMaximumHeapMiB",
    "laptopRegression",
  );
  requiredInteger(errors, laptop, "testsRequired", "laptopRegression");
  if (laptop?.testsRequired !== 13) {
    errors.push("laptopRegression.testsRequired must be 13");
  }
  if ((laptop?.jvmMaximumHeapMiB ?? 0) > 2048) {
    errors.push("laptopRegression.jvmMaximumHeapMiB exceeds 2048");
  }
  const requiredSuites = [
    "reframe4s.motion.RigidEstimatorSuite",
    "reframe4s.motion.RigidCaptureSuite",
    "reframe4s.motion.MotionPerformanceSuite",
    "reframe4s.motion.RigidOptimizationKernelPerformanceSuite",
  ];
  if (
    !Array.isArray(laptop?.suites) ||
    laptop.suites.length !== requiredSuites.length ||
    requiredSuites.some((suite, index) => laptop.suites[index] !== suite)
  ) {
    errors.push("laptopRegression.suites differ from the registered suite set");
  }
  const accuracy = laptop?.accuracyThresholds;
  for (const key of [
    "sixDofTranslationErrorMmMaximum",
    "sixDofRotationErrorDegreesMaximum",
  ]) {
    requiredNumber(errors, accuracy, key, "accuracyThresholds");
  }
  for (const key of [
    "captureScenariosRequired",
    "captureSuccessesRequired",
  ]) {
    requiredInteger(errors, accuracy, key, "accuracyThresholds");
  }
  if (accuracy?.captureMayUnderperformDisabledBaseline !== false) {
    errors.push(
      "accuracyThresholds.captureMayUnderperformDisabledBaseline must be false",
    );
  }
  const performance = laptop?.performanceThresholds;
  for (const key of [
    "pairThroughputMegaVoxelsPerSecondMinimum",
    "pairLargeAllocationBytesMaximum",
    "applicationThroughputMegaSamplesPerSecondMinimum",
    "applicationFixedOverheadBytesMaximum",
    "kernelThroughputMegaSamplesPerSecondMinimum",
    "kernelLargeAllocationBytesMaximum",
    "kernelAllocationGrowthBytesMaximum",
  ]) {
    requiredNumber(errors, performance, key, "performanceThresholds");
  }
  const budget = laptop?.resourceBudget;
  for (const key of [
    "minimumFreeDiskBytesBeforeRun",
    "maximumNetRepositoryBuildGrowthBytes",
    "maximumPersistedLogBytes",
    "maximumPersistedReceiptBytes",
  ]) {
    requiredInteger(errors, budget, key, "resourceBudget");
  }
  if (releaseCourt?.claimAuthority !== "speed-and-accuracy-superiority") {
    errors.push("releaseSuperiority.claimAuthority is invalid");
  }
  if (releaseCourt?.executionLocation !== "qualified-larger-host") {
    errors.push("releaseSuperiority must run on a qualified larger host");
  }
  if (releaseCourt?.status !== "deferred") {
    errors.push("releaseSuperiority.status must remain deferred");
  }
  if (releaseCourt?.protocolPath !== "benchmarks/motion/protocol-v2.json") {
    errors.push("releaseSuperiority.protocolPath is invalid");
  }
  if (
    !Array.isArray(releaseCourt?.activationRequirements) ||
    !releaseCourt.activationRequirements.some((value) =>
      value.includes("No laptop-regression result"),
    )
  ) {
    errors.push("releaseSuperiority lacks the laptop non-substitution rule");
  }
  return errors;
}

function diagnosticLine(output, prefix) {
  return output
    .split(/\r?\n/)
    .map((line) => line.replace(/^\[[^\]]+\]\s*/, "").trim())
    .find((line) => line.startsWith(prefix));
}

function fieldsFrom(line, prefix) {
  if (!line) return undefined;
  const fields = {};
  const body = line.slice(prefix.length).split(", records=[", 1)[0];
  for (const match of body.matchAll(
    /([A-Za-z][A-Za-z0-9_]*)=([-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[Ee][-+]?\d+)?)/g,
  )) {
    fields[match[1]] = Number(match[2]);
  }
  return fields;
}

export function parseDiagnostics(output) {
  const parsed = Object.fromEntries(
    Object.entries(diagnosticPrefixes).map(([name, prefix]) => [
      name,
      fieldsFrom(diagnosticLine(output, prefix), prefix),
    ]),
  );
  const pairLine = diagnosticLine(output, diagnosticPrefixes.pair);
  const runtime =
    pairLine?.match(/environment=(.+)$/)?.[1]?.trim() ?? undefined;
  const summaryMatch = output.match(
    /Passed:\s+Total\s+(\d+),\s+Failed\s+(\d+),\s+Errors\s+(\d+),\s+Passed\s+(\d+)/,
  );
  const ignored = [
    ...output.matchAll(
      /finished:\s+\d+\s+failed,\s+(\d+)\s+ignored,\s+\d+\s+total/g,
    ),
  ].reduce((total, match) => total + Number(match[1]), 0);
  const testSummary = summaryMatch
    ? {
        total: Number(summaryMatch[1]),
        failed: Number(summaryMatch[2]),
        errors: Number(summaryMatch[3]),
        passed: Number(summaryMatch[4]),
        ignored,
      }
    : undefined;
  return { ...parsed, runtime, testSummary };
}

function check(checks, id, pass, actual, expected) {
  checks.push({ id, pass, actual, expected });
}

export function evaluateGate(definition, diagnostics, execution) {
  const checks = [];
  const accuracy = definition.laptopRegression.accuracyThresholds;
  const performance = definition.laptopRegression.performanceThresholds;
  const budget = definition.laptopRegression.resourceBudget;
  const sixDof = diagnostics.sixDof;
  const capture = diagnostics.capture;
  const pair = diagnostics.pair;
  const application = diagnostics.application;
  const kernel = diagnostics.kernel;

  check(checks, "test-process", execution.exitCode === 0, execution.exitCode, 0);
  check(
    checks,
    "test-count",
    diagnostics.testSummary?.total ===
      definition.laptopRegression.testsRequired &&
      diagnostics.testSummary?.passed ===
        definition.laptopRegression.testsRequired &&
      diagnostics.testSummary?.failed === 0 &&
      diagnostics.testSummary?.errors === 0 &&
      diagnostics.testSummary?.ignored === 0,
    diagnostics.testSummary ?? null,
    {
      total: definition.laptopRegression.testsRequired,
      passed: definition.laptopRegression.testsRequired,
      failed: 0,
      errors: 0,
      ignored: 0,
    },
  );
  for (const name of Object.keys(diagnosticPrefixes)) {
    check(
      checks,
      `diagnostic-${name}`,
      diagnostics[name] !== undefined,
      diagnostics[name] === undefined ? "missing" : "present",
      "present",
    );
  }
  check(
    checks,
    "six-dof-translation",
    isFiniteNumber(sixDof?.translation_error_mm) &&
      sixDof.translation_error_mm <=
        accuracy.sixDofTranslationErrorMmMaximum,
    sixDof?.translation_error_mm ?? null,
    { maximum: accuracy.sixDofTranslationErrorMmMaximum, unit: "mm" },
  );
  check(
    checks,
    "six-dof-rotation",
    isFiniteNumber(sixDof?.rotation_error_deg) &&
      sixDof.rotation_error_deg <=
        accuracy.sixDofRotationErrorDegreesMaximum,
    sixDof?.rotation_error_deg ?? null,
    {
      maximum: accuracy.sixDofRotationErrorDegreesMaximum,
      unit: "degrees",
    },
  );
  check(
    checks,
    "capture-scenarios",
    capture?.scenarios === accuracy.captureScenariosRequired,
    capture?.scenarios ?? null,
    accuracy.captureScenariosRequired,
  );
  check(
    checks,
    "capture-successes",
    capture?.capturedSuccesses === accuracy.captureSuccessesRequired,
    capture?.capturedSuccesses ?? null,
    accuracy.captureSuccessesRequired,
  );
  check(
    checks,
    "capture-versus-disabled-baseline",
    isFiniteNumber(capture?.capturedSuccesses) &&
      isFiniteNumber(capture?.baselineSuccesses) &&
      capture.capturedSuccesses >= capture.baselineSuccesses,
    {
      captured: capture?.capturedSuccesses ?? null,
      baseline: capture?.baselineSuccesses ?? null,
    },
    "captured >= baseline",
  );
  check(
    checks,
    "pair-throughput",
    isFiniteNumber(pair?.throughput) &&
      pair.throughput >=
        performance.pairThroughputMegaVoxelsPerSecondMinimum,
    pair?.throughput ?? null,
    {
      minimum: performance.pairThroughputMegaVoxelsPerSecondMinimum,
      unit: "MVox/s",
    },
  );
  check(
    checks,
    "pair-allocation",
    isFiniteNumber(pair?.largeAllocated) &&
      pair.largeAllocated <= performance.pairLargeAllocationBytesMaximum,
    pair?.largeAllocated ?? null,
    { maximum: performance.pairLargeAllocationBytesMaximum, unit: "bytes" },
  );
  check(
    checks,
    "application-throughput",
    isFiniteNumber(application?.throughput) &&
      application.throughput >=
        performance.applicationThroughputMegaSamplesPerSecondMinimum,
    application?.throughput ?? null,
    {
      minimum: performance.applicationThroughputMegaSamplesPerSecondMinimum,
      unit: "MSamples/s",
    },
  );
  check(
    checks,
    "application-fixed-overhead",
    isFiniteNumber(application?.fixedOverhead) &&
      application.fixedOverhead <=
        performance.applicationFixedOverheadBytesMaximum,
    application?.fixedOverhead ?? null,
    {
      maximum: performance.applicationFixedOverheadBytesMaximum,
      unit: "bytes",
    },
  );
  check(
    checks,
    "kernel-throughput",
    isFiniteNumber(kernel?.throughput) &&
      kernel.throughput >=
        performance.kernelThroughputMegaSamplesPerSecondMinimum,
    kernel?.throughput ?? null,
    {
      minimum: performance.kernelThroughputMegaSamplesPerSecondMinimum,
      unit: "MSamples/s",
    },
  );
  check(
    checks,
    "kernel-allocation",
    isFiniteNumber(kernel?.largeAllocated) &&
      kernel.largeAllocated <=
        performance.kernelLargeAllocationBytesMaximum,
    kernel?.largeAllocated ?? null,
    {
      maximum: performance.kernelLargeAllocationBytesMaximum,
      unit: "bytes",
    },
  );
  check(
    checks,
    "kernel-allocation-growth",
    isFiniteNumber(kernel?.largeAllocated) &&
      isFiniteNumber(kernel?.smallAllocated) &&
      kernel.largeAllocated - kernel.smallAllocated <=
        performance.kernelAllocationGrowthBytesMaximum,
    isFiniteNumber(kernel?.largeAllocated) &&
      isFiniteNumber(kernel?.smallAllocated)
      ? kernel.largeAllocated - kernel.smallAllocated
      : null,
    {
      maximum: performance.kernelAllocationGrowthBytesMaximum,
      unit: "bytes",
    },
  );
  check(
    checks,
    "free-disk-before-run",
    execution.freeDiskBytesBefore >= budget.minimumFreeDiskBytesBeforeRun,
    execution.freeDiskBytesBefore,
    { minimum: budget.minimumFreeDiskBytesBeforeRun, unit: "bytes" },
  );
  check(
    checks,
    "repository-build-growth",
    execution.netBuildGrowthBytes <=
      budget.maximumNetRepositoryBuildGrowthBytes,
    execution.netBuildGrowthBytes,
    {
      maximum: budget.maximumNetRepositoryBuildGrowthBytes,
      unit: "bytes",
    },
  );
  check(
    checks,
    "complete-bounded-log",
    execution.logComplete === true,
    {
      bytes: execution.originalLogBytes,
      complete: execution.logComplete,
    },
    {
      maximum: budget.maximumPersistedLogBytes,
      complete: true,
      unit: "bytes",
    },
  );
  return {
    passed: checks.every((entry) => entry.pass),
    checks,
  };
}

export function sbtInvocation(definition) {
  const laptop = definition.laptopRegression;
  return {
    executable: "sbt",
    arguments: [
      `-J-Xmx${laptop.jvmMaximumHeapMiB}M`,
      "-batch",
      `reframe4s-motionJVM / Test / testOnly ${laptop.suites.join(" ")}`,
    ],
  };
}

function boundedUtf8(value, maximumBytes) {
  const bytes = Buffer.byteLength(value);
  if (bytes <= maximumBytes) {
    return { value, originalBytes: bytes, complete: true };
  }
  const marker = "\n... laptop gate log exceeded its registered byte budget ...\n";
  const markerBytes = Buffer.byteLength(marker);
  const retained = maximumBytes - markerBytes;
  const headBytes = Math.floor(retained / 2);
  const tailBytes = retained - headBytes;
  const buffer = Buffer.from(value);
  return {
    value:
      buffer.subarray(0, headBytes).toString("utf8") +
      marker +
      buffer.subarray(buffer.length - tailBytes).toString("utf8"),
    originalBytes: bytes,
    complete: false,
  };
}

function treeBytes(path) {
  if (!existsSync(path)) return 0;
  const stat = lstatSync(path);
  if (stat.isSymbolicLink()) return 0;
  if (stat.isFile()) return stat.size;
  if (!stat.isDirectory()) return 0;
  return readdirSync(path).reduce(
    (total, entry) => total + treeBytes(resolve(path, entry)),
    0,
  );
}

function repositoryBuildBytes(root) {
  function visit(path, name) {
    if (!existsSync(path)) return 0;
    const stat = lstatSync(path);
    if (stat.isSymbolicLink() || !stat.isDirectory()) return 0;
    if (name === "target") return treeBytes(path);
    if (
      name === ".git" ||
      name === ".mote" ||
      path === resolve(root, "benchmarks", "motion", "results")
    ) {
      return 0;
    }
    return readdirSync(path).reduce(
      (total, entry) =>
        total + visit(resolve(path, entry), entry),
      0,
    );
  }
  return visit(root, "");
}

function freeDiskBytes(path) {
  const stat = statfsSync(path);
  return Number(stat.bavail) * Number(stat.bsize);
}

function gitMetadata(root) {
  const revision = spawnSync("git", ["rev-parse", "HEAD"], {
    cwd: root,
    encoding: "utf8",
  });
  const status = spawnSync("git", ["status", "--porcelain"], {
    cwd: root,
    encoding: "utf8",
  });
  return {
    sourceRevision:
      revision.status === 0 ? revision.stdout.trim() : "uncommitted-worktree",
    dirtyWorktree:
      status.status !== 0 || status.stdout.trim().length > 0,
  };
}

function javaVersion(root) {
  const result = spawnSync("java", ["-version"], {
    cwd: root,
    encoding: "utf8",
  });
  const output = `${result.stdout ?? ""}${result.stderr ?? ""}`.trim();
  return result.status === 0 && output.length > 0 ? output : null;
}

function option(arguments_, name, fallback) {
  const found = [];
  for (let index = 0; index < arguments_.length; index += 1) {
    if (arguments_[index] === name) {
      if (index + 1 >= arguments_.length) {
        throw new Error(`${name} requires a value`);
      }
      found.push(arguments_[index + 1]);
      index += 1;
    }
  }
  if (found.length > 1) throw new Error(`${name} may appear only once`);
  return found[0] ?? fallback;
}

function defaultOutputDirectory() {
  const timestamp = new Date().toISOString().replaceAll(":", "-");
  return resolve("benchmarks", "motion", "results", "laptop", timestamp);
}

async function runProcess(invocation, root, environment) {
  return await new Promise((resolveResult) => {
    const child = spawn(invocation.executable, invocation.arguments, {
      cwd: root,
      env: { ...process.env, ...environment },
      stdio: ["ignore", "pipe", "pipe"],
    });
    const stdout = [];
    const stderr = [];
    child.stdout.on("data", (chunk) => {
      process.stdout.write(chunk);
      stdout.push(chunk);
    });
    child.stderr.on("data", (chunk) => {
      process.stderr.write(chunk);
      stderr.push(chunk);
    });
    child.on("error", (error) => {
      stderr.push(Buffer.from(`${error.message}\n`));
    });
    child.on("close", (code, signal) => {
      resolveResult({
        exitCode: Number.isInteger(code) ? code : 127,
        signal: signal ?? null,
        stdout: Buffer.concat(stdout).toString("utf8"),
        stderr: Buffer.concat(stderr).toString("utf8"),
      });
    });
  });
}

function writeExclusive(path, value) {
  writeFileSync(path, value, { encoding: "utf8", flag: "wx" });
}

async function main(arguments_) {
  const root = resolve(option(arguments_, "--root", "."));
  const definitionPath = resolve(
    root,
    option(arguments_, "--definition", defaultDefinition),
  );
  const definitionText = readFileSync(definitionPath, "utf8");
  const definition = JSON.parse(definitionText);
  const errors = definitionErrors(definition);
  if (errors.length > 0) {
    throw new Error(`invalid execution tiers:\n- ${errors.join("\n- ")}`);
  }
  if (arguments_.includes("--check-only")) {
    process.stdout.write(
      `motion laptop gate definition: valid (${sha256(definitionText)})\n`,
    );
    return;
  }

  const outputDirectory = resolve(
    root,
    option(arguments_, "--output", relative(root, defaultOutputDirectory())),
  );
  if (existsSync(outputDirectory)) {
    throw new Error(`output directory already exists: ${outputDirectory}`);
  }
  mkdirSync(outputDirectory, { recursive: true });
  const budget = definition.laptopRegression.resourceBudget;
  const diskBefore = freeDiskBytes(root);
  const buildBefore = repositoryBuildBytes(root);
  const invocation = sbtInvocation(definition);
  const git = gitMetadata(root);
  const startedAt = new Date().toISOString();
  let processResult;
  if (diskBefore < budget.minimumFreeDiskBytesBeforeRun) {
    processResult = {
      exitCode: 125,
      signal: null,
      stdout: "",
      stderr:
        `free disk ${diskBefore} B is below the registered ` +
        `minimum ${budget.minimumFreeDiskBytesBeforeRun} B\n`,
    };
  } else {
    processResult = await runProcess(invocation, root, {
      REFRAME4S_EVIDENCE_REVISION: git.sourceRevision,
    });
  }
  const finishedAt = new Date().toISOString();
  const combined =
    `--- stdout ---\n${processResult.stdout}` +
    `\n--- stderr ---\n${processResult.stderr}`;
  const boundedLog = boundedUtf8(
    combined,
    budget.maximumPersistedLogBytes,
  );
  const logPath = resolve(outputDirectory, "sbt.log");
  writeExclusive(logPath, boundedLog.value);
  const buildAfter = repositoryBuildBytes(root);
  const execution = {
    exitCode: processResult.exitCode,
    signal: processResult.signal,
    freeDiskBytesBefore: diskBefore,
    freeDiskBytesAfter: freeDiskBytes(root),
    netBuildGrowthBytes: Math.max(0, buildAfter - buildBefore),
    originalLogBytes: boundedLog.originalBytes,
    persistedLogBytes: Buffer.byteLength(boundedLog.value),
    logComplete: boundedLog.complete,
  };
  const diagnostics = parseDiagnostics(combined);
  const evaluation = evaluateGate(definition, diagnostics, execution);
  const receipt = {
    schemaVersion: receiptSchema,
    gateId: definition.laptopRegression.id,
    claimAuthority: definition.laptopRegression.claimAuthority,
    passed: evaluation.passed,
    startedAt,
    finishedAt,
    definitionPath: relative(root, definitionPath),
    definitionSha256: sha256(definitionText),
    sourceRevision: git.sourceRevision,
    dirtyWorktree: git.dirtyWorktree,
    environment: {
      hostname: hostname(),
      operatingSystem: platform(),
      architecture: arch(),
      release: release(),
      node: process.version,
      javaLauncher: javaVersion(root),
      testRuntime: diagnostics.runtime ?? null,
    },
    command: [invocation.executable, ...invocation.arguments],
    disk: {
      freeBytesBefore: execution.freeDiskBytesBefore,
      freeBytesAfter: execution.freeDiskBytesAfter,
      netRepositoryBuildGrowthBytes: execution.netBuildGrowthBytes,
    },
    tests: diagnostics.testSummary ?? null,
    accuracy: {
      sixDof: diagnostics.sixDof ?? null,
      capture: diagnostics.capture ?? null,
    },
    performance: {
      pair: diagnostics.pair ?? null,
      application: diagnostics.application ?? null,
      kernel: diagnostics.kernel ?? null,
    },
    checks: evaluation.checks,
    log: {
      path: relative(root, logPath),
      sha256: sha256(boundedLog.value),
      originalBytes: boundedLog.originalBytes,
      persistedBytes: execution.persistedLogBytes,
      complete: boundedLog.complete,
    },
  };
  const receiptText = `${JSON.stringify(receipt, null, 2)}\n`;
  if (
    Buffer.byteLength(receiptText) >
    budget.maximumPersistedReceiptBytes
  ) {
    throw new Error("laptop gate receipt exceeds its registered byte budget");
  }
  writeExclusive(resolve(outputDirectory, "receipt.json"), receiptText);
  process.stdout.write(
    `motion laptop gate: ${receipt.passed ? "PASS" : "FAIL"}; ` +
      `receipt=${relative(root, resolve(outputDirectory, "receipt.json"))}\n`,
  );
  if (!receipt.passed) process.exitCode = 1;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  main(process.argv.slice(2)).catch((error) => {
    process.stderr.write(`${String(error?.message ?? error)}\n`);
    process.exitCode = 1;
  });
}
