import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { providerPin } from "./provider-pin.mjs";

const revision = "d963ab5be1c916da055750b9f92f1e57e94df4ac";
const declaration = `lazy val image4sRevision = "${revision}"`;

test("read exactly one immutable revision without accepting comments or another owner", () => {
  assert.equal(providerPin(`// ${declaration}\n${declaration}\nlazy val galeRevision = "${"a".repeat(40)}"`, "image4s"), revision);
});

test("missing, malformed, nonhex, duplicate and injectable declarations refuse", () => {
  for (const build of ["", declaration.replace(revision, "main"), declaration.replace(revision, "g".repeat(40)),
    `${declaration}\n${declaration}`, declaration.replace(revision, revision.toUpperCase())]) {
    assert.throws(() => providerPin(build, "image4s"), /immutable/);
  }
  assert.throws(() => providerPin(declaration, "image4s|gale"), /provider name/);
});

test("real CI Image checkout derives the owning pin instead of carrying a second revision", () => {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
  assert.match(providerPin(readFileSync(resolve(root, "build.sbt"), "utf8"), "image4s"), /^[0-9a-f]{40}$/u);
  const workflow = readFileSync(resolve(root, ".github/workflows/ci.yml"), "utf8");
  assert.match(workflow, /node scripts\/provider-pin\.mjs image4s build\.sbt/u);
  assert.match(workflow, /repository: canardlapin\/image4s\s+ref: \$\{\{ steps\.image-pin\.outputs\.revision \}\}/u);
});

test("CI transitive ownership checkouts follow Image's immutable pins", () => {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
  const workflow = readFileSync(resolve(root, ".github/workflows/ci.yml"), "utf8");
  for (const provider of ["spatial4s", "locus4s"]) {
    const first = `lazy val ${provider}Revision = "${"1".repeat(40)}"`;
    const changed = first.replace("1".repeat(40), "2".repeat(40));
    assert.equal(providerPin(first, provider), "1".repeat(40));
    assert.equal(providerPin(changed, provider), "2".repeat(40));
    assert.ok(workflow.includes(`node scripts/provider-pin.mjs ${provider} .ci/image4s/build.sbt`));
    const checkout = workflow.split(`repository: canardlapin/${provider}`)[1]?.split("path:")[0];
    assert.equal(checkout?.trim(), "ref: ${{ steps.image-provider-pins.outputs.PROVIDER }}".replace("PROVIDER", provider));
  }
});
