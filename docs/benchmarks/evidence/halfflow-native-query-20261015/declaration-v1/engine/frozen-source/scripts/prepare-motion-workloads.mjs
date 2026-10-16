#!/usr/bin/env node

import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

export function workloadRows(manifest, manifestPath) {
  const base = dirname(resolve(manifestPath));
  return (manifest.assets ?? [])
    .map((asset) => ({
      subject: asset.subject,
      scenario: asset.scenario,
      input: resolve(base, asset.input.path),
      mask: resolve(base, asset.mask.path),
    }))
    .sort((left, right) =>
      `${left.subject}\u0000${left.scenario}`.localeCompare(
        `${right.subject}\u0000${right.scenario}`,
      ),
    );
}

export function encodeWorkloads(rows) {
  const header = "subject\tscenario\tinput\tmask";
  const values = rows.map((row) =>
    [row.subject, row.scenario, row.input, row.mask].join("\t"),
  );
  return `${[header, ...values].join("\n")}\n`;
}

function option(arguments_, name) {
  const index = arguments_.indexOf(name);
  if (index < 0 || index + 1 >= arguments_.length) {
    throw new Error(`${name} is required`);
  }
  return arguments_[index + 1];
}

function main(arguments_) {
  const assetsPath = option(arguments_, "--assets");
  const outputPath = option(arguments_, "--output");
  const manifest = JSON.parse(readFileSync(assetsPath, "utf8"));
  const rows = workloadRows(manifest, assetsPath);
  writeFileSync(outputPath, encodeWorkloads(rows), {
    encoding: "utf8",
    flag: "wx",
  });
  process.stdout.write(`motion workloads: wrote ${rows.length} rows\n`);
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
