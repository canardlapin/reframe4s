#!/usr/bin/env node
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

export function providerPin(build, provider) {
  if (!/^[a-z][a-z0-9]*$/u.test(provider)) {
    throw new Error("invalid provider name");
  }
  const declarations = [...build.matchAll(new RegExp(
    `^\\s*lazy val ${provider}Revision\\s*=\\s*"([^"]*)"\\s*$`, "gmu",
  ))];
  if (declarations.length !== 1 || !/^[0-9a-f]{40}$/u.test(declarations[0][1])) {
    throw new Error(`expected one immutable ${provider} revision`);
  }
  return declarations[0][1];
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [, , provider, build = "build.sbt"] = process.argv;
  process.stdout.write(`${providerPin(readFileSync(build, "utf8"), provider)}\n`);
}
