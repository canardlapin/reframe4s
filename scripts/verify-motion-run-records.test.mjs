import assert from "node:assert/strict";

import {
  validateCoverage,
  validateRecordsAgainstLock,
  validateRunRecord,
} from "./verify-motion-run-records.mjs";

const hash = "a".repeat(64);
const valid = {
  schemaVersion: "reframe4s.motion-superiority.run/v2",
  protocolSha256: hash,
  subject: "sub-01",
  scenario: "clean",
  court: "native_end_to_end",
  implementation: "reframe4s_motion",
  phase: "measured",
  repetition: 0,
  order: 0,
  allocatedCoreCount: 4,
  requestedWorkerCount: 4,
  effectiveWorkerCount: 1,
  effectiveWorkerEvidence: "implementation_contract",
  cpuAffinity: "0,2,4,6",
  referencePolicy: "fixed_frame_0",
  command: ["java"],
  workingDirectory: "/locked",
  environment: {
    OMP_NUM_THREADS: "4",
    OPENBLAS_NUM_THREADS: "1",
    MKL_NUM_THREADS: "1",
    VECLIB_MAXIMUM_THREADS: "1",
    NUMEXPR_NUM_THREADS: "1",
  },
  exitStatus: 0,
  termination: "success",
  failure: null,
  stageTimes: {
    source: "instrumented",
    decodeSeconds: 0.1,
    prepareSeconds: 0.1,
    estimateSeconds: 0.2,
    applySeconds: 0.1,
    encodeSeconds: 0.1,
    reportSeconds: 0.01,
    endToEndSeconds: 0.7,
  },
  peakRssBytes: 1024,
  materializedOutputBytes: 4096,
  stdoutSha256: hash,
  stderrSha256: hash,
  poseSha256: hash,
  correctedImageSha256: hash,
  tool: {
    executable: "java",
    registeredPin: "pin",
    versionExitStatus: 0,
    versionStdoutSha256: hash,
    versionStderrSha256: hash,
  },
  pipeline: {
    estimatorMaskPolicy: "none",
    estimationInterpolation: "production_trilinear_objective",
    finalInterpolation: "production_lanczos_5",
    boundaryPolicy: "constant_0",
    finalResamplingPasses: 1,
    timingIncludesFinalResampling: true,
  },
};

assert.deepEqual(validateRunRecord(valid, true), []);

const silentFailure = {
  ...valid,
  termination: "process_failure",
  exitStatus: 2,
  failure: null,
};
assert(
  validateRunRecord(silentFailure).some((error) =>
    error.includes("failure message"),
  ),
);

const lock = {
  protocol: { sha256: hash },
  candidate: {
    versionStdoutSha256: hash,
    versionStderrSha256: hash,
  },
  implementations: [
    "afni_3dvolreg",
    "nifreeze",
    "fsl_mcflirt",
  ].map((id) => ({
    id,
    versionStdoutSha256: hash,
    versionStderrSha256: hash,
  })),
  workers: [
    "reframe4s_motion",
    "afni_3dvolreg",
    "nifreeze",
    "fsl_mcflirt",
  ].map((id) => ({
    id,
    primary: {
      allocatedCoreCount: 4,
      requestedWorkerCount: 4,
      effectiveWorkerCount: id === "nifreeze" ? 4 : 1,
    },
    sensitivity: {
      allocatedCoreCount: 1,
      requestedWorkerCount: 1,
      effectiveWorkerCount: 1,
    },
  })),
  runner: {
    workingDirectory: ".",
    javaExecutable: "java",
    candidateClasspath: "candidate.jar",
    afniExecutable: "3dvolreg",
    mcflirtExecutable: "mcflirt",
    pythonExecutable: "python",
    nifreezeWrapper: "run_nifreeze.py",
  },
  hardware: {
    cpuSet: "0,2,4,6",
  },
};
const lockedCandidate = {
  ...valid,
  command: ["/locked/java", "-cp", "/locked/candidate.jar"],
  tool: {
    ...valid.tool,
    executable: "/locked/java",
  },
};
assert.deepEqual(
  validateRecordsAgainstLock(
    [lockedCandidate],
    lock,
    "/locked/admission.json",
  ),
  [],
);
const wrongVersion = {
  ...lockedCandidate,
  tool: {
    ...lockedCandidate.tool,
    versionStdoutSha256: "b".repeat(64),
  },
};
assert(
  validateRecordsAgainstLock(
    [wrongVersion],
    lock,
    "/locked/admission.json",
  ).some((error) => error.includes("version stdout differs")),
);

const nativeWithoutImage = {
  ...valid,
  correctedImageSha256: null,
};
assert(
  validateRunRecord(nativeWithoutImage).some((error) =>
    error.includes("correctedImageSha256"),
  ),
);

const plan = [
  {
    subject: "sub-01",
    scenario: "clean",
    court: "native_end_to_end",
    implementation: "reframe4s_motion",
    phase: "measured",
    repetition: "0",
    order: "0",
    allocated_core_count: "4",
    requested_worker_count: "4",
    effective_worker_count: "1",
  },
];
assert.deepEqual(validateCoverage([valid], plan), []);
assert.equal(validateCoverage([], plan).length, 1);
assert.equal(validateCoverage([valid, valid], plan).length, 1);

const relabeledSerialCandidate = {
  ...valid,
  effectiveWorkerCount: 4,
};
assert(
  validateRunRecord(relabeledSerialCandidate).some((error) =>
    error.includes("effectiveWorkerCount contradicts"),
  ),
);

const nativeNifreezeWithFixedReference = {
  ...valid,
  implementation: "nifreeze",
  effectiveWorkerCount: 4,
  effectiveWorkerEvidence: "locked_adapter_configuration",
  referencePolicy: "fixed_frame_0",
};
assert(
  validateRunRecord(nativeNifreezeWithFixedReference).some((error) =>
    error.includes("referencePolicy contradicts"),
  ),
);

const hiddenCommonResampler = {
  ...valid,
  court: "common_resampler_estimation",
  pipeline: {
    ...valid.pipeline,
    finalInterpolation: "production_lanczos_5",
    timingIncludesFinalResampling: false,
  },
};
assert(
  validateRunRecord(hiddenCommonResampler).some((error) =>
    error.includes("final application contradicts"),
  ),
);

console.log("motion run record validator tests: ok");
