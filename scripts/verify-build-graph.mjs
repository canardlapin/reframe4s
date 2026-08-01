#!/usr/bin/env node

import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const root = resolve(scriptDirectory, "..");
const prd = JSON.parse(readFileSync(resolve(root, "PRD.json"), "utf8"));
const rows = readFileSync(resolve(root, "project", "artifact-graph.tsv"), "utf8")
  .split(/\r?\n/u)
  .map((line) => line.trim())
  .filter((line) => line.length > 0 && !line.startsWith("#"))
  .map((line, index) => {
    const fields = line.split("\t");
    if (fields.length !== 3 || !["node", "edge"].includes(fields[0])) {
      throw new Error(
        `artifact-graph.tsv row ${index + 1} must be node<TAB>kind<TAB>artifact ` +
          "or edge<TAB>consumer<TAB>dependency",
      );
    }
    return fields;
  });

const actualNodes = new Map();
const actualEdges = new Set();
for (const [tag, second, third] of rows) {
  if (tag === "node") {
    if (actualNodes.has(third)) {
      throw new Error(`duplicate artifact graph node '${third}'`);
    }
    actualNodes.set(third, second);
  } else {
    const key = `${second}\u0000${third}`;
    if (actualEdges.has(key)) {
      throw new Error(`duplicate artifact graph edge '${second}' -> '${third}'`);
    }
    actualEdges.add(key);
  }
}

const expectedNodes = new Map(
  prd.moduleGraph.nodes.map(({ artifact, kind }) => [artifact, kind]),
);
const expectedEdges = new Set(
  prd.moduleGraph.edges.map(({ from, to }) => `${from}\u0000${to}`),
);
const errors = [];

for (const [artifact, kind] of expectedNodes) {
  if (!actualNodes.has(artifact)) {
    errors.push(`missing build node '${artifact}'`);
  } else if (actualNodes.get(artifact) !== kind) {
    errors.push(
      `build node '${artifact}' has kind '${actualNodes.get(artifact)}', expected '${kind}'`,
    );
  }
}
for (const artifact of actualNodes.keys()) {
  if (!expectedNodes.has(artifact)) {
    errors.push(`undeclared build node '${artifact}'`);
  }
}
for (const edge of expectedEdges) {
  if (!actualEdges.has(edge)) {
    const [from, to] = edge.split("\u0000");
    errors.push(`missing build edge '${from}' -> '${to}'`);
  }
}
for (const edge of actualEdges) {
  if (!expectedEdges.has(edge)) {
    const [from, to] = edge.split("\u0000");
    errors.push(`undeclared build edge '${from}' -> '${to}'`);
  }
}
for (const edge of actualEdges) {
  const [from, to] = edge.split("\u0000");
  if (!actualNodes.has(from) || !actualNodes.has(to)) {
    errors.push(`build edge '${from}' -> '${to}' references an unknown node`);
  }
}

if (errors.length > 0) {
  process.stderr.write(
    `Build graph validation failed with ${errors.length} error${errors.length === 1 ? "" : "s"}:\n`,
  );
  for (const error of errors) {
    process.stderr.write(`- ${error}\n`);
  }
  process.exit(1);
}

process.stdout.write(
  `Build graph matches PRD: ${actualNodes.size} nodes and ${actualEdges.size} edges.\n`,
);
