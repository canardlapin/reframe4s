#!/usr/bin/env node

import assert from "node:assert/strict";

import { runnerInvocation } from "./invoke-motion-runner.mjs";

const revision = "a".repeat(40);
const lock = {
  candidate: { revision },
  hardware: {
    cpuSet: "0,2,4,6",
    pageCachePolicy: "warm-identical",
  },
  runner: {
    affinityExecutable: "bin/taskset",
    javaExecutable: "bin/java",
    candidateClasspath: "lib/runner.jar",
    workingDirectory: ".",
    nifreezeWrapper: "bin/nifreeze.py",
    afniExecutable: "bin/3dvolreg",
    mcflirtExecutable: "bin/mcflirt",
    pythonExecutable: "bin/python",
  },
};
const invocation = runnerInvocation(
  lock,
  "/locked/admission.json",
  ["execute-plan", "--plan", "/results/plan.tsv"],
);
assert.equal(invocation.executable, "/locked/bin/taskset");
assert.deepEqual(invocation.arguments.slice(0, 5), [
  "--cpu-list",
  "0,2,4,6",
  "/locked/bin/java",
  "-cp",
  "/locked/lib/runner.jar",
]);
assert(invocation.arguments.includes("/locked/bin/3dvolreg"));
assert.equal(
  invocation.environment.REFRAME4S_CANDIDATE_REVISION,
  revision,
);

assert.throws(
  () =>
    runnerInvocation(lock, "/locked/admission.json", [
      "execute-plan",
      "--afni",
      "/different",
    ]),
  /admission lock/,
);

console.log("motion runner invocation tests: ok");
