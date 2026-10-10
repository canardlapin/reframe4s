import assert from "node:assert/strict";
import test from "node:test";
import { verifyReceipt } from "./halfflow-candidate.mjs";

const candidate = { sha256: "exact-source", files: { "build.sbt": "pinned-dependencies" } };
const passing = {
  passed: 6, failed: 0, errors: 0,
  suites: [{ name: "reframe4s.halfflow.CanonicalGeometrySuite", passed: 6 }],
};
const receipt = { candidate, numerical: { JVM: passing, JS: passing }, ownership: { exitCode: 0 } };

test("candidate binding rejects stale source or dependency pins", () => {
  assert.doesNotThrow(() => verifyReceipt(receipt, candidate));
  assert.throws(() => verifyReceipt(receipt, { ...candidate, sha256: "new-source" }), /does not match/u);
  assert.throws(() => verifyReceipt(receipt, { ...candidate, files: { "build.sbt": "other-pin" } }), /does not match/u);
});

test("candidate binding requires numerical migration evidence on both platforms and ownership", () => {
  assert.throws(() => verifyReceipt({ ...receipt, numerical: { JVM: passing } }, candidate), /JS/u);
  assert.throws(() => verifyReceipt({ ...receipt, numerical: { JVM: passing, JS: { ...passing, failed: 1 } } }, candidate), /JS/u);
  assert.throws(() => verifyReceipt({ ...receipt, ownership: { exitCode: 1 } }, candidate), /ownership/u);
});
