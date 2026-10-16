#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

const requiredImplementations = [
  "reframe4s_motion",
  "afni_3dvolreg",
  "nifreeze",
  "fsl_mcflirt",
];

const requiredCourts = [
  "common_resampler_estimation",
  "native_end_to_end",
];

const requiredPrimaryMetrics = [
  "physical_landmark_displacement_p95_mm",
  "corrected_image_nrmse",
  "native_end_to_end_elapsed_seconds",
  "common_court_elapsed_seconds",
];

const requiredGuardrailMetrics = [
  "relative_rotation_error_p95_degrees",
  "framewise_displacement_error_p95_mm",
  "boundary_shell_nrmse",
  "temporal_difference_nrmse",
  "edge_energy_log_error",
  "ringing_fraction",
  "failure_rate",
];

const isObject = (value) =>
  value !== null && typeof value === "object" && !Array.isArray(value);

const unique = (values) => new Set(values).size === values.length;

const finiteNumber = (value) =>
  typeof value === "number" && Number.isFinite(value);

const determinant3 = (matrix) => {
  const a = matrix[0];
  const b = matrix[1];
  const c = matrix[2];
  const d = matrix[4];
  const e = matrix[5];
  const f = matrix[6];
  const g = matrix[8];
  const h = matrix[9];
  const i = matrix[10];
  return a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
};

const requireIds = (actual, expected, label, errors) => {
  for (const id of expected) {
    if (!actual.includes(id)) {
      errors.push(`${label} is missing required id ${id}`);
    }
  }
};

export function validateProtocol(protocol) {
  const errors = [];

  if (!isObject(protocol)) {
    return ["protocol root must be an object"];
  }
  if (
    protocol.schemaVersion !==
    "reframe4s.motion-superiority.protocol/v2"
  ) {
    errors.push("schemaVersion must be reframe4s.motion-superiority.protocol/v2");
  }
  if (protocol.protocolId !== "motion-superiority-v2") {
    errors.push("protocolId must be motion-superiority-v2");
  }
  if (protocol.version !== "2.0.0" || protocol.status !== "frozen") {
    errors.push("protocol version 2.0.0 must have frozen status");
  }
  if (!protocol.changeControl?.resultDrivenChangesForbidden) {
    errors.push("result-driven protocol changes must be forbidden");
  }
  if (
    protocol.changeControl?.supersedes !== "motion-superiority-v1 1.0.0" ||
    !protocol.changeControl?.reason?.includes(
      "No candidate or comparator result was run",
    )
  ) {
    errors.push("v2 must identify the pre-result v1 correction");
  }

  const headlineComparators = protocol.claim?.headlineComparators ?? [];
  const contextComparators = protocol.claim?.requiredContextComparators ?? [];
  if (
    !Array.isArray(headlineComparators) ||
    !unique(headlineComparators) ||
    headlineComparators.join(",") !== "afni_3dvolreg,nifreeze"
  ) {
    errors.push("headline comparators must be exactly AFNI 3dvolreg and nifreeze");
  }
  if (
    !Array.isArray(contextComparators) ||
    contextComparators.join(",") !== "fsl_mcflirt"
  ) {
    errors.push("FSL MCFLIRT must be the required context comparator");
  }
  if (
    typeof protocol.claim?.acceptedLanguage !== "string" ||
    typeof protocol.claim?.unmetLanguage !== "string"
  ) {
    errors.push("accepted and unmet claim language must be fixed strings");
  }

  const implementations = protocol.implementations ?? [];
  const implementationIds = implementations.map((value) => value.id);
  if (!unique(implementationIds)) {
    errors.push("implementation ids must be unique");
  }
  requireIds(
    implementationIds,
    requiredImplementations,
    "implementations",
    errors,
  );
  for (const implementation of implementations) {
    if (!isObject(implementation.pin)) {
      errors.push(`${implementation.id} must define a pin`);
    }
    if (
      implementation.id !== "reframe4s_motion" &&
      !(typeof implementation.pin?.value === "string" &&
        implementation.pin.value.length > 0)
    ) {
      errors.push(`${implementation.id} must define a concrete version or revision`);
    }
    if (!isObject(implementation.configuration)) {
      errors.push(`${implementation.id} must define an exact configuration`);
    }
  }
  const expectedEffectiveWorkers = new Map([
    ["reframe4s_motion", 1],
    ["afni_3dvolreg", 1],
    ["nifreeze", 4],
    ["fsl_mcflirt", 1],
  ]);
  for (const implementation of implementations) {
    const configuration = implementation.configuration ?? {};
    if ("threads" in configuration) {
      errors.push(
        `${implementation.id} cannot use the ambiguous v1 threads field`,
      );
    }
    if (
      configuration.primaryAllocatedCoreCount !== 4 ||
      configuration.primaryRequestedWorkerCount !== 4 ||
      configuration.primaryEffectiveWorkerCount !==
        expectedEffectiveWorkers.get(implementation.id)
    ) {
      errors.push(
        `${implementation.id} primary worker counts differ from the registered v2 policy`,
      );
    }
    if (
      typeof configuration.effectiveWorkerEvidence !== "string" ||
      configuration.effectiveWorkerEvidence.length === 0
    ) {
      errors.push(`${implementation.id} must define effective-worker evidence`);
    }
  }

  const synthetic = protocol.data?.syntheticSubjects;
  if (synthetic?.count !== 24) {
    errors.push("synthetic subject count must be exactly 24");
  }
  const subjectSeeds = synthetic?.seeds ?? [];
  if (
    subjectSeeds.length !== 24 ||
    !unique(subjectSeeds) ||
    !subjectSeeds.every(Number.isSafeInteger)
  ) {
    errors.push("synthetic subject seeds must be 24 unique integers");
  }
  if (synthetic?.earlyStopping !== "forbidden") {
    errors.push("synthetic early stopping must be forbidden");
  }
  if (!synthetic?.renderer?.independentOfProductionResampling) {
    errors.push("the renderer must be independent of production resampling");
  }
  if (protocol.data?.realAnatomySubjects?.minimumCount < 12) {
    errors.push("at least twelve real-anatomy subjects must be required");
  }

  const scenarios = protocol.scenarioGeneration?.scenarios ?? [];
  const scenarioIds = scenarios.map((value) => value.id);
  if (scenarios.length !== 4 || !unique(scenarioIds)) {
    errors.push("the protocol must define four uniquely named scenarios");
  }
  const totalWeight = scenarios.reduce(
    (sum, scenario) => sum + (scenario.weight ?? Number.NaN),
    0,
  );
  if (!Number.isFinite(totalWeight) || Math.abs(totalWeight - 1) > 1e-12) {
    errors.push(`scenario weights must sum to one, got ${totalWeight}`);
  }
  for (const scenario of scenarios) {
    if (
      !Array.isArray(scenario.shape) ||
      scenario.shape.length !== 4 ||
      !scenario.shape.every(
        (extent) => Number.isSafeInteger(extent) && extent > 0,
      )
    ) {
      errors.push(`${scenario.id} must define a positive rank-four shape`);
    }
    const affine = scenario.indexToWorld;
    if (
      !Array.isArray(affine) ||
      affine.length !== 16 ||
      !affine.every(finiteNumber)
    ) {
      errors.push(`${scenario.id} must define a finite 4-by-4 affine`);
    } else {
      const bottom = affine.slice(12);
      if (
        bottom[0] !== 0 ||
        bottom[1] !== 0 ||
        bottom[2] !== 0 ||
        bottom[3] !== 1
      ) {
        errors.push(`${scenario.id} affine must have homogeneous bottom row`);
      }
      if (Math.abs(determinant3(affine)) <= 1e-9) {
        errors.push(`${scenario.id} affine must be invertible`);
      }
    }
    const translation = scenario.twistHarmonics?.translationMm;
    const rotation = scenario.twistHarmonics?.rotationRadians;
    for (const [name, harmonics] of [
      ["translationMm", translation],
      ["rotationRadians", rotation],
    ]) {
      if (
        !Array.isArray(harmonics) ||
        harmonics.length !== 3 ||
        !harmonics.every(
          (triple) =>
            Array.isArray(triple) &&
            triple.length === 3 &&
            triple.every(finiteNumber),
        )
      ) {
        errors.push(`${scenario.id} must define three ${name} harmonics`);
      }
    }
    if (!isObject(scenario.nuisance) || !Array.isArray(scenario.impulses)) {
      errors.push(`${scenario.id} must define nuisance parameters and impulses`);
    }
  }
  const referencePolicy = protocol.scenarioGeneration?.referencePolicy;
  const expectedCommonReferences = {
    reframe4s_motion: "fixed_frame_0",
    afni_3dvolreg: "fixed_frame_0",
    nifreeze: "fixed_frame_0",
    fsl_mcflirt: "fixed_frame_0",
  };
  const expectedNativeReferences = {
    reframe4s_motion: "fixed_frame_0",
    afni_3dvolreg: "fixed_frame_0",
    nifreeze: "median_leave_one_volume_out",
    fsl_mcflirt: "fixed_frame_0",
  };
  for (const [court, expected] of [
    ["commonCourt", expectedCommonReferences],
    ["nativeCourt", expectedNativeReferences],
  ]) {
    if (!isObject(referencePolicy?.[court])) {
      errors.push(`${court} reference policy must be an object`);
      continue;
    }
    for (const [implementation, policy] of Object.entries(expected)) {
      if (referencePolicy[court][implementation] !== policy) {
        errors.push(
          `${court} ${implementation} reference policy must be ${policy}`,
        );
      }
    }
  }
  if (
    !referencePolicy?.commonCourt?.rule?.includes(
      "No estimator receives the scoring mask",
    )
  ) {
    errors.push("the common court must separate estimator and scoring masks");
  }

  const courts = protocol.courts ?? [];
  const courtIds = courts.map((value) => value.id);
  if (!unique(courtIds)) {
    errors.push("court ids must be unique");
  }
  requireIds(courtIds, requiredCourts, "courts", errors);
  for (const court of courts) {
    if (!court.required || typeof court.timing !== "string") {
      errors.push(`${court.id} must be required and define its timing boundary`);
    }
  }

  const metrics = protocol.metrics ?? [];
  const metricIds = metrics.map((value) => value.id);
  if (!unique(metricIds)) {
    errors.push("metric ids must be unique");
  }
  requireIds(metricIds, requiredPrimaryMetrics, "metrics", errors);
  requireIds(metricIds, requiredGuardrailMetrics, "metrics", errors);
  for (const metric of metrics) {
    for (const field of [
      "units",
      "direction",
      "aggregation",
      "role",
      "missingValuePolicy",
      "effect",
    ]) {
      if (typeof metric[field] !== "string" || metric[field].length === 0) {
        errors.push(`${metric.id} must define ${field}`);
      }
    }
    if (
      !finiteNumber(metric.practicalSuperiorityMargin) ||
      metric.practicalSuperiorityMargin <= 0
    ) {
      errors.push(`${metric.id} must define a positive superiority margin`);
    }
    if (
      !finiteNumber(metric.scenarioNonInferiorityMargin) ||
      metric.scenarioNonInferiorityMargin <= 0
    ) {
      errors.push(`${metric.id} must define a positive scenario margin`);
    }
  }
  for (const id of requiredPrimaryMetrics) {
    const metric = metrics.find((value) => value.id === id);
    if (!metric?.role?.startsWith("co-primary-")) {
      errors.push(`${id} must be co-primary`);
    }
  }

  const headline = protocol.hypotheses?.headlineFamily;
  if (headline?.allMustReject !== true) {
    errors.push("all headline hypotheses must reject");
  }
  requireIds(
    headline?.comparators ?? [],
    ["afni_3dvolreg", "nifreeze"],
    "headline hypotheses",
    errors,
  );
  requireIds(
    headline?.metrics ?? [],
    requiredPrimaryMetrics,
    "headline hypotheses",
    errors,
  );
  requireIds(
    protocol.hypotheses?.contextFamily?.comparators ?? [],
    ["fsl_mcflirt"],
    "context hypotheses",
    errors,
  );

  if (protocol.execution?.jvm?.warmupRuns < 5) {
    errors.push("JVM warmup must include at least five runs");
  }
  if (protocol.execution?.jvm?.measuredRuns < 20) {
    errors.push("JVM timing must include at least twenty measured runs");
  }
  if (protocol.execution?.otherImplementations?.measuredRuns < 20) {
    errors.push("comparator timing must include at least twenty measured runs");
  }
  if (!protocol.execution?.oneShotTimingForbidden) {
    errors.push("one-shot timing must be forbidden");
  }
  if (!protocol.execution?.randomization?.methodOrder?.includes("Latin square")) {
    errors.push("execution order must use a registered balanced Latin square");
  }
  if (
    protocol.execution?.primaryAllocatedCoreCount !== 4 ||
    protocol.execution?.sensitivityAllocatedCoreCount !== 1
  ) {
    errors.push("execution must register four-core and one-core courts");
  }
  if (
    !protocol.execution?.workerCountSemantics?.rule?.includes(
      "all three counts to equal one",
    )
  ) {
    errors.push("worker-count semantics must bind the one-core sensitivity court");
  }
  const commonCourt = courts.find(
    (court) => court.id === "common_resampler_estimation",
  );
  if (
    !commonCourt?.timing?.includes("process or already-provisioned container launch") ||
    !commonCourt?.timing?.includes("canonical physical pose matrix")
  ) {
    errors.push("the common court must use the observable process timing boundary");
  }

  if (
    protocol.statistics?.cluster !== "subject" ||
    !protocol.statistics?.pairedAnalysis
  ) {
    errors.push("statistics must use paired subject clusters");
  }
  if (
    !protocol.statistics?.intervalMethod?.includes("10000 subject resamples")
  ) {
    errors.push("statistics must register 10000 subject-cluster bootstrap resamples");
  }
  if (
    !protocol.statistics?.headlineMultiplicity?.includes("eight headline") ||
    !protocol.statistics?.headlineMultiplicity?.includes("Holm")
  ) {
    errors.push("headline family-wise multiplicity control must be explicit");
  }

  if ((protocol.absoluteGates ?? []).length < 4) {
    errors.push("absolute gates are incomplete");
  }
  if ((protocol.completePassRule ?? []).length < 8) {
    errors.push("complete pass rule is incomplete");
  }
  if ((protocol.prohibitedEvidence ?? []).length < 10) {
    errors.push("prohibited evidence registry is incomplete");
  }
  if (!protocol.receipt?.visibleLossesRequired) {
    errors.push("receipts must retain visible losses");
  }
  if ((protocol.receipt?.requiredRunFields ?? []).length < 10) {
    errors.push("receipt run schema is incomplete");
  }
  requireIds(
    protocol.receipt?.requiredRunFields ?? [],
    [
      "allocatedCoreCount",
      "requestedWorkerCount",
      "effectiveWorkerCount",
      "effectiveWorkerEvidence",
      "referencePolicy",
    ],
    "receipt run fields",
    errors,
  );

  return errors;
}

export function admissionErrors(protocol) {
  return admissionLockErrors(protocol, null, null);
}

const sha256Pattern = /^[0-9a-f]{64}$/;

const requiredHardwareFields = [
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
];

export function admissionLockErrors(protocol, lock, protocolSha256) {
  const errors = [];
  if (!isObject(lock)) {
    return [
      "admission lock is absent; the frozen protocol is not itself mutated to admit a claim",
    ];
  }
  if (
    lock.schemaVersion !==
    "reframe4s.motion-superiority.lock/v2"
  ) {
    errors.push("admission lock schemaVersion is invalid");
  }
  if (
    lock.protocol?.id !== protocol.protocolId ||
    lock.protocol?.version !== protocol.version
  ) {
    errors.push("admission lock targets a different protocol id or version");
  }
  if (
    typeof protocolSha256 !== "string" ||
    lock.protocol?.sha256 !== protocolSha256
  ) {
    errors.push("admission lock protocol sha256 does not match the frozen file");
  }

  const blockerIds = (protocol.admission?.blockers ?? []).map(
    (blocker) => blocker.id,
  );
  const resolvedBlockers = lock.resolvedBlockers ?? [];
  if (!Array.isArray(resolvedBlockers) || !unique(resolvedBlockers)) {
    errors.push("resolved blocker ids must be a unique array");
  } else {
    requireIds(
      resolvedBlockers,
      blockerIds,
      "admission lock",
      errors,
    );
    for (const id of resolvedBlockers) {
      if (!blockerIds.includes(id)) {
        errors.push(`admission lock contains unknown blocker ${id}`);
      }
    }
  }

  if (lock.candidate?.clean !== true) {
    errors.push("candidate revision must be clean");
  }
  if (!/^[0-9a-f]{40}$/.test(lock.candidate?.revision ?? "")) {
    errors.push("candidate revision must be a full git sha");
  }
  if (!sha256Pattern.test(lock.candidate?.artifactSha256 ?? "")) {
    errors.push("candidate artifact sha256 is missing or invalid");
  }
  if (
    !sha256Pattern.test(lock.candidate?.versionStdoutSha256 ?? "") ||
    !sha256Pattern.test(lock.candidate?.versionStderrSha256 ?? "")
  ) {
    errors.push("candidate version-output hashes are missing or invalid");
  }
  if (
    typeof lock.candidate?.artifactPath !== "string" ||
    lock.candidate.artifactPath.length === 0
  ) {
    errors.push("candidate artifact path is missing");
  }

  const lockedImplementations = lock.implementations ?? [];
  const lockedIds = lockedImplementations.map((entry) => entry.id);
  if (!unique(lockedIds)) {
    errors.push("locked implementation ids must be unique");
  }
  requireIds(
    lockedIds,
    requiredImplementations.slice(1),
    "admission lock implementations",
    errors,
  );
  for (const protocolImplementation of (protocol.implementations ?? []).filter(
    (entry) => entry.id !== "reframe4s_motion",
  )) {
    const locked = lockedImplementations.find(
      (entry) => entry.id === protocolImplementation.id,
    );
    if (!locked) continue;
    if (locked.resolved !== true) {
      errors.push(`${locked.id} lock is not resolved`);
    }
    if (locked.sourcePin !== protocolImplementation.pin?.value) {
      errors.push(`${locked.id} source pin differs from the frozen protocol`);
    }
    if (locked.id === "fsl_mcflirt") {
      if (!sha256Pattern.test(locked.packageLockSha256 ?? "")) {
        errors.push("fsl_mcflirt package lock sha256 is missing or invalid");
      }
      if (
        typeof locked.packageLockPath !== "string" ||
        locked.packageLockPath.length === 0
      ) {
        errors.push("fsl_mcflirt package lock path is missing");
      }
      if (locked.licenseAccepted !== true) {
        errors.push("fsl_mcflirt license acceptance is not recorded");
      }
    } else {
      if (!/^sha256:[0-9a-f]{64}$/.test(locked.digest ?? "")) {
        errors.push(`${locked.id} container digest is missing or invalid`);
      }
      if (
        typeof locked.imageReference !== "string" ||
        !locked.imageReference.endsWith(`@${locked.digest}`) ||
        locked.imageReference.includes(":latest@")
      ) {
        errors.push(`${locked.id} immutable image reference is invalid`);
      }
      if (locked.platform !== "linux/amd64") {
        errors.push(`${locked.id} locked platform must be linux/amd64`);
      }
      if (locked.id === "nifreeze") {
        if (
          typeof locked.dependencyLockPath !== "string" ||
          locked.dependencyLockPath.length === 0 ||
          !sha256Pattern.test(locked.dependencyLockSha256 ?? "")
        ) {
          errors.push("nifreeze dependency lock is missing or invalid");
        }
      }
    }
    if (
      !sha256Pattern.test(locked.versionStdoutSha256 ?? "") ||
      !sha256Pattern.test(locked.versionStderrSha256 ?? "")
    ) {
      errors.push(
        `${locked.id} version-output hashes are missing or invalid`,
      );
    }
  }

  const workers = lock.workers ?? [];
  const workerIds = workers.map((entry) => entry.id);
  if (!unique(workerIds)) {
    errors.push("worker lock implementation ids must be unique");
  }
  requireIds(
    workerIds,
    requiredImplementations,
    "worker lock",
    errors,
  );
  for (const implementation of protocol.implementations ?? []) {
    const worker = workers.find((entry) => entry.id === implementation.id);
    if (!worker) continue;
    const configuration = implementation.configuration;
    if (
      worker.primary?.allocatedCoreCount !==
        configuration.primaryAllocatedCoreCount ||
      worker.primary?.requestedWorkerCount !==
        configuration.primaryRequestedWorkerCount ||
      worker.primary?.effectiveWorkerCount !==
        configuration.primaryEffectiveWorkerCount
    ) {
      errors.push(`${implementation.id} primary worker lock differs from protocol`);
    }
    if (
      worker.sensitivity?.allocatedCoreCount !== 1 ||
      worker.sensitivity?.requestedWorkerCount !== 1 ||
      worker.sensitivity?.effectiveWorkerCount !== 1
    ) {
      errors.push(`${implementation.id} sensitivity worker lock must be 1/1/1`);
    }
    if (
      typeof worker.evidence !== "string" ||
      worker.evidence.length === 0 ||
      !sha256Pattern.test(worker.evidenceSha256 ?? "")
    ) {
      errors.push(`${implementation.id} worker evidence is missing or invalid`);
    }
  }

  const realData = lock.realAnatomy;
  const manifest = realData?.manifest;
  const minimumCount = protocol.data?.realAnatomySubjects?.minimumCount ?? 12;
  const requiredFields =
    protocol.data?.realAnatomySubjects?.requiredManifestFields ?? [];
  if (!Array.isArray(manifest)) {
    errors.push("real-anatomy data manifest is absent");
  } else {
    if (manifest.length < minimumCount) {
      errors.push(
        `real-anatomy manifest has ${manifest.length} rows, requires ${minimumCount}`,
      );
    }
    const keys = [];
    for (const [index, row] of manifest.entries()) {
      if (!isObject(row)) {
        errors.push(`real-anatomy manifest row ${index} must be an object`);
        continue;
      }
      for (const field of requiredFields) {
        if (!(field in row) || row[field] === null || row[field] === "") {
          errors.push(`real-anatomy manifest row ${index} is missing ${field}`);
        }
      }
      if (!sha256Pattern.test(row.sha256 ?? "")) {
        errors.push(`real-anatomy manifest row ${index} has invalid sha256`);
      }
      if (!Array.isArray(row.shape) || row.shape.length !== 4) {
        errors.push(`real-anatomy manifest row ${index} has invalid shape`);
      }
      if (
        !Array.isArray(row.indexToWorld) ||
        row.indexToWorld.length !== 16
      ) {
        errors.push(
          `real-anatomy manifest row ${index} has invalid indexToWorld`,
        );
      }
      keys.push(`${row.dataset}\u0000${row.subject}\u0000${row.run}`);
    }
    if (!unique(keys)) {
      errors.push("real-anatomy subject-run keys must be unique");
    }
  }
  if (!sha256Pattern.test(realData?.manifestSha256 ?? "")) {
    errors.push("real-anatomy manifest sha256 is missing or invalid");
  }
  if (
    typeof lock.benchmarkAssets?.manifestPath !== "string" ||
    lock.benchmarkAssets.manifestPath.length === 0 ||
    !sha256Pattern.test(lock.benchmarkAssets?.manifestSha256 ?? "")
  ) {
    errors.push("benchmark asset manifest lock is missing or invalid");
  }
  for (const field of [
    "workingDirectory",
    "containerEngine",
    "affinityExecutable",
    "javaExecutable",
    "candidateClasspath",
    "afniExecutable",
    "mcflirtExecutable",
    "pythonExecutable",
    "nifreezeWrapper",
  ]) {
    if (
      typeof lock.runner?.[field] !== "string" ||
      lock.runner[field].length === 0
    ) {
      errors.push(`runner lock is missing ${field}`);
    }
  }

  for (const field of requiredHardwareFields) {
    if (
      !(field in (lock.hardware ?? {})) ||
      lock.hardware[field] === null ||
      lock.hardware[field] === ""
    ) {
      errors.push(`hardware lock is missing ${field}`);
    }
  }
  const cpuSet = String(lock.hardware?.cpuSet ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter((value) => value.length > 0);
  if (
    cpuSet.length !== 4 ||
    new Set(cpuSet).size !== 4 ||
    cpuSet.some((value) => !/^[0-9]+$/.test(value))
  ) {
    errors.push("hardware cpuSet must name four distinct logical CPUs");
  }
  if (lock.hardware?.simultaneousMultithreadingExcluded !== true) {
    errors.push("hardware lock must exclude simultaneous-multithreading siblings");
  }
  if (lock.hardware?.swapDisabled !== true) {
    errors.push("hardware lock must record swap as disabled");
  }
  if (
    typeof lock.environmentManifestPath !== "string" ||
    lock.environmentManifestPath.length === 0
  ) {
    errors.push("environment manifest path is missing");
  }
  if (!sha256Pattern.test(lock.environmentLockSha256 ?? "")) {
    errors.push("environment lock sha256 is missing or invalid");
  }
  if (!sha256Pattern.test(lock.dataLockSha256 ?? "")) {
    errors.push("data lock sha256 is missing or invalid");
  }
  if (
    sha256Pattern.test(lock.dataLockSha256 ?? "") &&
    lock.dataLockSha256 !== lock.benchmarkAssets?.manifestSha256
  ) {
    errors.push("data lock sha256 must equal the benchmark asset manifest hash");
  }
  return errors;
}

export function parseProtocol(text) {
  return JSON.parse(text);
}

const runCli = () => {
  const args = process.argv.slice(2);
  const admission = args.includes("--admission");
  const json = args.includes("--json");
  const lockIndex = args.indexOf("--lock");
  const lockPath = lockIndex >= 0 ? args[lockIndex + 1] : null;
  const positional = args.filter(
    (arg, index) =>
      !arg.startsWith("--") &&
      !(lockIndex >= 0 && index === lockIndex + 1),
  );
  const path = positional[0] ?? "benchmarks/motion/protocol-v2.json";
  let text;
  let protocol;
  try {
    text = readFileSync(path, "utf8");
    protocol = parseProtocol(text);
  } catch (error) {
    const result = {
      ok: false,
      phase: "parse",
      errors: [String(error?.message ?? error)],
    };
    if (json) {
      process.stdout.write(`${JSON.stringify(result)}\n`);
    } else {
      process.stderr.write(`motion protocol parse failed: ${result.errors[0]}\n`);
    }
    process.exitCode = 1;
    return;
  }

  const sha256 = createHash("sha256").update(text).digest("hex");
  let lock = null;
  let lockParseErrors = [];
  if (admission && lockPath) {
    try {
      lock = JSON.parse(readFileSync(lockPath, "utf8"));
    } catch (error) {
      lockParseErrors = [
        `could not parse admission lock ${lockPath}: ${String(error?.message ?? error)}`,
      ];
    }
  }
  const structuralErrors = validateProtocol(protocol);
  const gateErrors = admission
    ? [...lockParseErrors, ...admissionLockErrors(protocol, lock, sha256)]
    : [];
  const errors = [...structuralErrors, ...gateErrors];
  const result = {
    ok: errors.length === 0,
    phase: admission ? "claim-admission" : "structure",
    protocolId: protocol.protocolId,
    version: protocol.version,
    sha256,
    errors,
  };

  if (json) {
    process.stdout.write(`${JSON.stringify(result)}\n`);
  } else if (result.ok) {
    process.stdout.write(
      `motion protocol ${result.phase}: ok (${protocol.protocolId} ${protocol.version}, sha256 ${sha256})\n`,
    );
  } else {
    process.stderr.write(`motion protocol ${result.phase}: failed\n`);
    for (const error of errors) {
      process.stderr.write(`- ${error}\n`);
    }
  }
  process.exitCode = result.ok ? 0 : admission ? 2 : 1;
};

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  runCli();
}
