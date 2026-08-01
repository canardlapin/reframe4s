import { readFileSync } from "node:fs";
import { delimiter, dirname, join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const sha256 = /^[0-9a-f]{64}$/;
const courts = new Set([
  "common_resampler_estimation",
  "native_end_to_end",
]);
const implementations = new Set([
  "reframe4s_motion",
  "afni_3dvolreg",
  "nifreeze",
  "fsl_mcflirt",
]);
const phases = new Set(["warmup", "measured"]);
const terminations = new Set([
  "success",
  "process_failure",
  "timeout",
  "invalid_output",
  "runner_failure",
]);
const workerEvidence = new Set([
  "implementation_contract",
  "locked_tool_probe",
  "locked_adapter_configuration",
]);
const referencePolicies = new Set([
  "fixed_frame_0",
  "median_leave_one_volume_out",
]);
const stageTimingSources = new Set([
  "instrumented",
  "runner_end_to_end_only",
]);
const requiredEnvironment = [
  "OMP_NUM_THREADS",
  "OPENBLAS_NUM_THREADS",
  "MKL_NUM_THREADS",
  "VECLIB_MAXIMUM_THREADS",
  "NUMEXPR_NUM_THREADS",
];

function isNonnegativeFinite(value) {
  return typeof value === "number" && Number.isFinite(value) && value >= 0;
}

function isNullableSeconds(value) {
  return value === null || isNonnegativeFinite(value);
}

export function validateRunRecord(record, admission = false) {
  const errors = [];
  const required = [
    "schemaVersion",
    "protocolSha256",
    "subject",
    "scenario",
    "court",
    "implementation",
    "phase",
    "repetition",
    "order",
    "allocatedCoreCount",
    "requestedWorkerCount",
    "effectiveWorkerCount",
    "effectiveWorkerEvidence",
    "cpuAffinity",
    "referencePolicy",
    "command",
    "workingDirectory",
    "environment",
    "exitStatus",
    "termination",
    "failure",
    "stageTimes",
    "peakRssBytes",
    "materializedOutputBytes",
    "stdoutSha256",
    "stderrSha256",
    "poseSha256",
    "correctedImageSha256",
    "tool",
    "pipeline",
  ];
  for (const field of required) {
    if (!Object.hasOwn(record, field)) errors.push(`missing ${field}`);
  }
  if (record.schemaVersion !== "reframe4s.motion-superiority.run/v2") {
    errors.push("unexpected schemaVersion");
  }
  if (!sha256.test(record.protocolSha256 ?? "")) {
    errors.push("invalid protocolSha256");
  }
  if (typeof record.subject !== "string" || record.subject.length === 0) {
    errors.push("invalid subject");
  }
  if (typeof record.scenario !== "string" || record.scenario.length === 0) {
    errors.push("invalid scenario");
  }
  if (!courts.has(record.court)) errors.push("invalid court");
  if (!implementations.has(record.implementation)) {
    errors.push("invalid implementation");
  }
  if (!phases.has(record.phase)) errors.push("invalid phase");
  if (!Number.isInteger(record.repetition) || record.repetition < 0) {
    errors.push("invalid repetition");
  }
  if (!Number.isInteger(record.order) || record.order < 0 || record.order > 3) {
    errors.push("invalid order");
  }
  if (![1, 4].includes(record.allocatedCoreCount)) {
    errors.push("invalid allocatedCoreCount");
  }
  if (
    !Number.isInteger(record.requestedWorkerCount) ||
    record.requestedWorkerCount < 1 ||
    record.requestedWorkerCount > record.allocatedCoreCount
  ) {
    errors.push("invalid requestedWorkerCount");
  }
  if (
    !Number.isInteger(record.effectiveWorkerCount) ||
    record.effectiveWorkerCount < 1 ||
    record.effectiveWorkerCount > record.requestedWorkerCount
  ) {
    errors.push("invalid effectiveWorkerCount");
  }
  if (!workerEvidence.has(record.effectiveWorkerEvidence)) {
    errors.push("invalid effectiveWorkerEvidence");
  }
  if (typeof record.cpuAffinity !== "string" || record.cpuAffinity.length === 0) {
    errors.push("invalid cpuAffinity");
  }
  if (!referencePolicies.has(record.referencePolicy)) {
    errors.push("invalid referencePolicy");
  }
  if (
    record.allocatedCoreCount === 1 &&
    (record.requestedWorkerCount !== 1 ||
      record.effectiveWorkerCount !== 1)
  ) {
    errors.push("one-core rows require 1/1/1 worker counts");
  }
  const expectedReference =
    record.court === "native_end_to_end" &&
    record.implementation === "nifreeze"
      ? "median_leave_one_volume_out"
      : "fixed_frame_0";
  if (record.referencePolicy !== expectedReference) {
    errors.push("referencePolicy contradicts protocol-v2");
  }
  const expectedEffective =
    record.implementation === "nifreeze"
      ? record.allocatedCoreCount
      : 1;
  if (record.effectiveWorkerCount !== expectedEffective) {
    errors.push("effectiveWorkerCount contradicts protocol-v2");
  }
  if (!Array.isArray(record.command) || record.command.length === 0) {
    errors.push("command must be nonempty");
  }
  if (
    typeof record.workingDirectory !== "string" ||
    record.workingDirectory.length === 0
  ) {
    errors.push("workingDirectory must be nonempty");
  }
  if (!record.environment || typeof record.environment !== "object") {
    errors.push("environment must be an object");
  } else {
    for (const name of requiredEnvironment) {
      if (typeof record.environment[name] !== "string") {
        errors.push(`environment is missing ${name}`);
      }
    }
    if (
      record.environment.OMP_NUM_THREADS !==
      String(record.requestedWorkerCount)
    ) {
      errors.push("OMP_NUM_THREADS differs from requestedWorkerCount");
    }
  }
  if (!Number.isInteger(record.exitStatus)) errors.push("invalid exitStatus");
  if (!terminations.has(record.termination)) errors.push("invalid termination");
  if (record.failure !== null && typeof record.failure !== "string") {
    errors.push("failure must be string or null");
  }
  const stages = record.stageTimes;
  if (!stages || typeof stages !== "object") {
    errors.push("stageTimes must be an object");
  } else {
    if (!stageTimingSources.has(stages.source)) {
      errors.push("invalid stage timing source");
    }
    for (const name of [
      "decodeSeconds",
      "prepareSeconds",
      "estimateSeconds",
      "applySeconds",
      "encodeSeconds",
      "reportSeconds",
    ]) {
      if (!isNullableSeconds(stages[name])) errors.push(`invalid ${name}`);
    }
    if (!isNonnegativeFinite(stages.endToEndSeconds)) {
      errors.push("invalid endToEndSeconds");
    }
  }
  if (
    record.peakRssBytes !== null &&
    (!Number.isInteger(record.peakRssBytes) || record.peakRssBytes < 0)
  ) {
    errors.push("invalid peakRssBytes");
  }
  if (admission && record.peakRssBytes === null) {
    errors.push("admission requires peakRssBytes");
  }
  if (
    !Number.isInteger(record.materializedOutputBytes) ||
    record.materializedOutputBytes < 0
  ) {
    errors.push("invalid materializedOutputBytes");
  }
  for (const field of ["stdoutSha256", "stderrSha256"]) {
    if (!sha256.test(record[field] ?? "")) errors.push(`invalid ${field}`);
  }
  for (const field of ["poseSha256", "correctedImageSha256"]) {
    if (record[field] !== null && !sha256.test(record[field] ?? "")) {
      errors.push(`invalid ${field}`);
    }
  }
  if (!record.tool || typeof record.tool !== "object") {
    errors.push("tool must be an object");
  } else {
    if (typeof record.tool.executable !== "string") {
      errors.push("invalid tool executable");
    }
    if (typeof record.tool.registeredPin !== "string") {
      errors.push("invalid registered pin");
    }
    if (!Number.isInteger(record.tool.versionExitStatus)) {
      errors.push("invalid version exit status");
    }
    for (const field of ["versionStdoutSha256", "versionStderrSha256"]) {
      if (!sha256.test(record.tool[field] ?? "")) {
        errors.push(`invalid tool ${field}`);
      }
    }
  }
  const pipeline = record.pipeline;
  if (!pipeline || typeof pipeline !== "object") {
    errors.push("pipeline must be an object");
  } else {
    const expectedEstimation = {
      reframe4s_motion: "production_trilinear_objective",
      afni_3dvolreg: "afni_3dvolreg_heptic_registered",
      nifreeze: "ants_rigid_backend_registered",
      fsl_mcflirt: "mcflirt_internal_registered",
    }[record.implementation];
    const nativeFinal = {
      reframe4s_motion: ["production_lanczos_5", "constant_0"],
      afni_3dvolreg: ["afni_heptic", "zpad_1_and_clip"],
      nifreeze: ["nitransforms_cubic_order_3", "constant_0"],
      fsl_mcflirt: [
        "mcflirt_spline_final",
        "duplicated_terminal_z_slices",
      ],
    }[record.implementation];
    const expectedFinal =
      record.court === "common_resampler_estimation"
        ? ["image4s_reference_trilinear", "constant_0"]
        : nativeFinal;
    if (pipeline.estimationInterpolation !== expectedEstimation) {
      errors.push("pipeline estimation interpolation contradicts protocol-v2");
    }
    if (
      !expectedFinal ||
      pipeline.finalInterpolation !== expectedFinal[0] ||
      pipeline.boundaryPolicy !== expectedFinal[1]
    ) {
      errors.push("pipeline final application contradicts protocol-v2");
    }
    if (pipeline.finalResamplingPasses !== 1) {
      errors.push("pipeline must record one final resampling pass");
    }
    if (
      pipeline.timingIncludesFinalResampling !==
      (record.court === "native_end_to_end")
    ) {
      errors.push("pipeline timing inclusion contradicts court");
    }
    const acceptedMaskPolicies =
      record.court === "native_end_to_end" &&
      record.implementation === "nifreeze"
        ? new Set(["none", "registered_mask"])
        : new Set(["none"]);
    if (!acceptedMaskPolicies.has(pipeline.estimatorMaskPolicy)) {
      errors.push("pipeline estimator mask contradicts protocol-v2");
    }
  }
  if (record.termination === "success") {
    if (record.exitStatus !== 0) errors.push("success requires exitStatus zero");
    if (record.failure !== null) errors.push("success cannot carry failure");
    if (record.poseSha256 === null) errors.push("success requires poseSha256");
    if (record.correctedImageSha256 === null) {
      errors.push("success requires correctedImageSha256");
    }
  } else if (typeof record.failure !== "string" || record.failure.length === 0) {
    errors.push("non-success row requires a failure message");
  }
  if (admission) {
    if (record.tool.versionExitStatus !== 0) {
      errors.push("admission requires a successful version probe");
    }
  }
  return errors;
}

function recordKey(record) {
  return [
    record.subject,
    record.scenario,
    record.court,
    record.implementation,
    record.phase,
    record.repetition,
    record.order,
    record.allocatedCoreCount,
    record.requestedWorkerCount,
    record.effectiveWorkerCount,
  ].join("\u001f");
}

function planKey(fields) {
  return [
    fields.subject,
    fields.scenario,
    fields.court,
    fields.implementation,
    fields.phase,
    fields.repetition,
    fields.order,
    fields.allocated_core_count,
    fields.requested_worker_count,
    fields.effective_worker_count,
  ].join("\u001f");
}

export function readPlan(path) {
  const lines = readFileSync(path, "utf8").trimEnd().split(/\r?\n/);
  const names = lines.shift().split("\t");
  return lines.map((line) =>
    Object.fromEntries(names.map((name, index) => [name, line.split("\t")[index]])),
  );
}

export function validateCoverage(records, planRows) {
  const errors = [];
  const counts = new Map();
  for (const record of records) {
    const key = recordKey(record);
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  const expected = new Set(planRows.map(planKey));
  for (const key of expected) {
    const count = counts.get(key) ?? 0;
    if (count !== 1) errors.push(`planned row has ${count} records: ${key}`);
  }
  for (const key of counts.keys()) {
    if (!expected.has(key)) errors.push(`unplanned record: ${key}`);
  }
  return errors;
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

export function validateRecordsAgainstLock(records, lock, lockPath) {
  const errors = [];
  const base = dirname(resolve(lockPath));
  const lockedImplementations = new Map(
    (lock.implementations ?? []).map((entry) => [entry.id, entry]),
  );
  const lockedWorkers = new Map(
    (lock.workers ?? []).map((entry) => [entry.id, entry]),
  );
  const expectedExecutables = {
    reframe4s_motion: resolveLocked(base, lock.runner?.javaExecutable),
    afni_3dvolreg: resolveLocked(base, lock.runner?.afniExecutable),
    nifreeze: resolveLocked(base, lock.runner?.pythonExecutable),
    fsl_mcflirt: resolveLocked(base, lock.runner?.mcflirtExecutable),
  };
  const expectedWorkingDirectory = resolveLocked(
    base,
    lock.runner?.workingDirectory,
  );
  const expectedClasspath = resolveClasspath(
    base,
    lock.runner?.candidateClasspath,
  );
  const expectedNifreezeWrapper = resolveLocked(
    base,
    lock.runner?.nifreezeWrapper,
  );
  for (const [index, record] of records.entries()) {
    const context = `record ${index} ${record.implementation}`;
    if (record.protocolSha256 !== lock.protocol?.sha256) {
      errors.push(`${context} protocol hash differs from the lock`);
    }
    if (record.cpuAffinity !== lock.hardware?.cpuSet) {
      errors.push(`${context} CPU affinity differs from the hardware lock`);
    }
    if (record.workingDirectory !== expectedWorkingDirectory) {
      errors.push(`${context} working directory differs from the lock`);
    }
    if (record.command?.[0] !== expectedExecutables[record.implementation]) {
      errors.push(`${context} executable differs from the lock`);
    }
    if (record.tool?.executable !== expectedExecutables[record.implementation]) {
      errors.push(`${context} recorded tool executable differs from the lock`);
    }
    if (
      record.implementation === "reframe4s_motion" &&
      record.command?.[2] !== expectedClasspath
    ) {
      errors.push(`${context} candidate classpath differs from the lock`);
    }
    if (
      record.implementation === "nifreeze" &&
      record.command?.[1] !== expectedNifreezeWrapper
    ) {
      errors.push(`${context} nifreeze wrapper differs from the lock`);
    }
    const versionLock =
      record.implementation === "reframe4s_motion"
        ? lock.candidate
        : lockedImplementations.get(record.implementation);
    if (
      record.tool?.versionStdoutSha256 !== versionLock?.versionStdoutSha256
    ) {
      errors.push(`${context} version stdout differs from the lock`);
    }
    if (
      record.tool?.versionStderrSha256 !== versionLock?.versionStderrSha256
    ) {
      errors.push(`${context} version stderr differs from the lock`);
    }
    const worker = lockedWorkers.get(record.implementation);
    const expectedWorker =
      record.allocatedCoreCount === 4
        ? worker?.primary
        : record.allocatedCoreCount === 1
          ? worker?.sensitivity
          : null;
    if (
      !expectedWorker ||
      record.allocatedCoreCount !== expectedWorker.allocatedCoreCount ||
      record.requestedWorkerCount !== expectedWorker.requestedWorkerCount ||
      record.effectiveWorkerCount !== expectedWorker.effectiveWorkerCount
    ) {
      errors.push(`${context} worker registration differs from the lock`);
    }
  }
  return errors;
}

function main(argv) {
  let admission = false;
  let plan;
  let lockPath;
  let planRunRecords = false;
  const paths = [];
  for (let index = 0; index < argv.length; index += 1) {
    if (argv[index] === "--admission") admission = true;
    else if (argv[index] === "--plan-run-records") {
      planRunRecords = true;
    }
    else if (argv[index] === "--plan") {
      plan = argv[index + 1];
      index += 1;
    } else if (argv[index] === "--lock") {
      lockPath = argv[index + 1];
      index += 1;
    } else paths.push(argv[index]);
  }
  if (planRunRecords && !plan) {
    throw new Error("--plan-run-records requires --plan");
  }
  const planRows = plan ? readPlan(plan) : null;
  const recordPaths =
    planRunRecords && planRows
      ? planRows.map((row) => join(row.output_directory, "run.json"))
      : paths;
  if (recordPaths.length === 0) {
    throw new Error("provide one or more run.json files");
  }
  const records = recordPaths.map((path) =>
    JSON.parse(readFileSync(path, "utf8")),
  );
  const errors = records.flatMap((record, index) =>
    validateRunRecord(record, admission).map(
      (error) => `${recordPaths[index]}: ${error}`,
    ),
  );
  if (planRows) errors.push(...validateCoverage(records, planRows));
  if (lockPath) {
    const lock = JSON.parse(readFileSync(lockPath, "utf8"));
    errors.push(...validateRecordsAgainstLock(records, lock, lockPath));
  }
  if (errors.length > 0) {
    for (const error of errors) console.error(error);
    process.exitCode = 1;
  } else {
    console.log(
      `motion run records: ok (${records.length} rows${
        plan ? ", complete plan coverage" : ""
      }${lockPath ? ", admission-lock bound" : ""})`,
    );
  }
}

if (import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main(process.argv.slice(2));
}
