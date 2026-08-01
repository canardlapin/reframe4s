#!/usr/bin/env node

import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const prdPath = resolve(
  process.cwd(),
  process.argv[2] ?? resolve(scriptDirectory, "..", "PRD.json"),
);

const errors = [];

function fail(message) {
  errors.push(message);
}

function isRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function nonEmptyString(value) {
  return typeof value === "string" && value.trim().length > 0;
}

function requireArray(root, key) {
  const value = root[key];
  if (!Array.isArray(value)) {
    fail(`${key} must be an array`);
    return [];
  }
  return value;
}

function requireRecord(root, key) {
  const value = root[key];
  if (!isRecord(value)) {
    fail(`${key} must be an object`);
    return {};
  }
  return value;
}

function itemId(item, context) {
  if (!isRecord(item) || !nonEmptyString(item.id)) {
    fail(`${context} must be an object with a non-empty string id`);
    return undefined;
  }
  return item.id;
}

function nodeId(node, context) {
  if (nonEmptyString(node)) {
    return node;
  }
  if (!isRecord(node)) {
    fail(`${context} must be a string or object`);
    return undefined;
  }

  const id = node.id ?? node.artifact ?? node.name;
  if (!nonEmptyString(id)) {
    fail(`${context} must declare a non-empty id, artifact, or name`);
    return undefined;
  }
  if (
    node.concrete === false ||
    node.kind === "aggregate" ||
    node.type === "aggregate" ||
    node.type === "repository"
  ) {
    fail(`${context} '${id}' is not a concrete artifact`);
  }
  return id;
}

function artifactId(artifact, context) {
  if (nonEmptyString(artifact)) {
    return artifact;
  }
  if (!isRecord(artifact)) {
    fail(`${context} must be a string or object`);
    return undefined;
  }
  const id = artifact.id ?? artifact.artifact ?? artifact.name;
  if (!nonEmptyString(id)) {
    fail(`${context} must declare a non-empty id, artifact, or name`);
    return undefined;
  }
  return id;
}

function isInternalArtifact(artifact) {
  return (
    !isRecord(artifact) ||
    (artifact.external !== true &&
      artifact.kind !== "external" &&
      artifact.scope !== "external")
  );
}

function declaredArtifacts(repositories) {
  if (isRecord(repositories)) {
    if (!Array.isArray(repositories.artifacts)) {
      fail("repositories.artifacts must be an array");
      return [];
    }
    return repositories.artifacts;
  }

  if (Array.isArray(repositories)) {
    const entries = [];
    for (const [index, repository] of repositories.entries()) {
      if (!isRecord(repository)) {
        fail(`repositories[${index}] must be an object`);
        continue;
      }
      if (repository.artifacts !== undefined) {
        if (!Array.isArray(repository.artifacts)) {
          fail(`repositories[${index}].artifacts must be an array`);
        } else {
          entries.push(...repository.artifacts);
        }
      }
    }
    if (entries.length === 0) {
      fail(
        "repositories must declare internal concrete artifacts in artifacts arrays",
      );
    }
    return entries;
  }

  fail("repositories must be an object or array");
  return [];
}

function aggregateRepositoryNames(repositories, internalArtifactIds, graphNodeIds) {
  const aggregates = new Set();

  if (nonEmptyString(prd.product?.repository)) {
    aggregates.add(prd.product.repository);
  }

  if (Array.isArray(repositories)) {
    for (const repository of repositories) {
      if (isRecord(repository) && nonEmptyString(repository.name)) {
        aggregates.add(repository.name);
      }
    }
  } else if (isRecord(repositories)) {
    for (const key of [
      "name",
      "repository",
      "sourceRepository",
      "monorepo",
    ]) {
      const value = repositories[key];
      if (nonEmptyString(value)) {
        aggregates.add(value);
      } else if (isRecord(value) && nonEmptyString(value.name)) {
        aggregates.add(value.name);
      }
    }
    for (const key of ["aggregateNames", "aggregateRepositories"]) {
      const values = repositories[key];
      if (Array.isArray(values)) {
        for (const value of values) {
          if (nonEmptyString(value)) {
            aggregates.add(value);
          } else if (isRecord(value) && nonEmptyString(value.name)) {
            aggregates.add(value.name);
          }
        }
      }
    }
  }

  for (const candidate of graphNodeIds) {
    if (
      !internalArtifactIds.has(candidate) &&
      graphNodeIds.some(
        (other) => other !== candidate && other.startsWith(`${candidate}-`),
      )
    ) {
      aggregates.add(candidate);
    }
  }

  return aggregates;
}

function validateEdgeList(edges, label, nodeIds) {
  if (!Array.isArray(edges)) {
    fail(`${label} must be an array`);
    return [];
  }

  const validEdges = [];
  const seen = new Set();
  for (const [index, edge] of edges.entries()) {
    const context = `${label}[${index}]`;
    if (!isRecord(edge) || !nonEmptyString(edge.from) || !nonEmptyString(edge.to)) {
      fail(`${context} must have non-empty string from and to fields`);
      continue;
    }

    if (!nodeIds.has(edge.from)) {
      fail(`${context}.from references undeclared node '${edge.from}'`);
    }
    if (!nodeIds.has(edge.to)) {
      fail(`${context}.to references undeclared node '${edge.to}'`);
    }
    if (edge.from === edge.to) {
      fail(`${context} is a self edge on '${edge.from}'`);
    }

    const key = `${edge.from}\u0000${edge.to}`;
    if (seen.has(key)) {
      fail(`${context} duplicates edge '${edge.from}' -> '${edge.to}'`);
    } else {
      seen.add(key);
    }
    validEdges.push([edge.from, edge.to]);
  }
  return validEdges;
}

function validateDag(nodeIds, edges) {
  const indegree = new Map([...nodeIds].map((id) => [id, 0]));
  const outgoing = new Map([...nodeIds].map((id) => [id, []]));

  for (const [from, to] of edges) {
    if (!indegree.has(from) || !indegree.has(to) || from === to) {
      continue;
    }
    outgoing.get(from).push(to);
    indegree.set(to, indegree.get(to) + 1);
  }

  const queue = [...indegree]
    .filter(([, degree]) => degree === 0)
    .map(([id]) => id);
  let visited = 0;

  for (let cursor = 0; cursor < queue.length; cursor += 1) {
    const from = queue[cursor];
    visited += 1;
    for (const to of outgoing.get(from)) {
      const next = indegree.get(to) - 1;
      indegree.set(to, next);
      if (next === 0) {
        queue.push(to);
      }
    }
  }

  if (visited !== nodeIds.size) {
    const cyclicNodes = [...indegree]
      .filter(([, degree]) => degree > 0)
      .map(([id]) => id)
      .sort();
    fail(`moduleGraph contains a cycle involving: ${cyclicNodes.join(", ")}`);
  }
}

let prd;
try {
  prd = JSON.parse(readFileSync(prdPath, "utf8"));
} catch (error) {
  process.stderr.write(
    `PRD validation failed\n- cannot parse ${prdPath}: ${error.message}\n`,
  );
  process.exit(1);
}

if (!isRecord(prd)) {
  process.stderr.write("PRD validation failed\n- PRD root must be an object\n");
  process.exit(1);
}

const requiredTopLevelSections = [
  "schemaVersion",
  "product",
  "status",
  "authority",
  "problem",
  "decisions",
  "repositories",
  "moduleGraph",
  "dependencyRules",
  "publicContracts",
  "migrationPhases",
  "acceptanceGates",
  "risks",
  "nonGoals",
  "sourceEvidence",
];

for (const section of requiredTopLevelSections) {
  if (!Object.hasOwn(prd, section)) {
    fail(`missing required top-level section '${section}'`);
  }
}

if (!nonEmptyString(prd.schemaVersion)) {
  fail("schemaVersion must be a non-empty string");
}
requireRecord(prd, "product");
const status = requireRecord(prd, "status");
if (status.state !== "approved") {
  fail("status.state must equal 'approved' before implementation");
}
if (!nonEmptyString(status.approvalBasis)) {
  fail("status.approvalBasis must be a non-empty string");
}
requireRecord(prd, "authority");
requireRecord(prd, "problem");
requireArray(prd, "nonGoals");
requireArray(prd, "sourceEvidence");

const identifiedSections = [
  "decisions",
  "dependencyRules",
  "publicContracts",
  "migrationPhases",
  "acceptanceGates",
  "risks",
];
const sectionItems = new Map();
const allIds = new Map();

for (const section of identifiedSections) {
  const items = requireArray(prd, section);
  sectionItems.set(section, items);
  for (const [index, item] of items.entries()) {
    const id = itemId(item, `${section}[${index}]`);
    if (id === undefined) {
      continue;
    }
    const previous = allIds.get(id);
    if (previous !== undefined) {
      fail(`duplicate id '${id}' in ${previous} and ${section}[${index}]`);
    } else {
      allIds.set(id, `${section}[${index}]`);
    }
  }
}

const phaseIds = new Set(
  sectionItems
    .get("migrationPhases")
    .map((item) => item?.id)
    .filter(nonEmptyString),
);
const gateIds = new Set(
  sectionItems
    .get("acceptanceGates")
    .map((item) => item?.id)
    .filter(nonEmptyString),
);

for (const [index, phase] of sectionItems.get("migrationPhases").entries()) {
  if (!isRecord(phase)) {
    continue;
  }
  for (const field of ["requires", "exitGates"]) {
    if (!Array.isArray(phase[field])) {
      fail(`migrationPhases[${index}].${field} must be an array`);
    }
  }
  const requires = Array.isArray(phase.requires) ? phase.requires : [];
  const entryGates =
    phase.entryGates === undefined
      ? []
      : Array.isArray(phase.entryGates)
        ? phase.entryGates
        : (fail(`migrationPhases[${index}].entryGates must be an array`), []);
  const exitGates = Array.isArray(phase.exitGates) ? phase.exitGates : [];
  for (const [requiredIndex, required] of requires.entries()) {
    if (!nonEmptyString(required)) {
      fail(
        `migrationPhases[${index}].requires[${requiredIndex}] must be a non-empty string`,
      );
    } else if (!phaseIds.has(required)) {
      fail(
        `migrationPhases[${index}].requires[${requiredIndex}] references unknown phase '${required}'`,
      );
    }
  }
  for (const [field, gates] of [
    ["entryGates", entryGates],
    ["exitGates", exitGates],
  ]) {
    for (const [gateIndex, gate] of gates.entries()) {
    if (!nonEmptyString(gate)) {
      fail(
          `migrationPhases[${index}].${field}[${gateIndex}] must be a non-empty string`,
      );
    } else if (!gateIds.has(gate)) {
      fail(
          `migrationPhases[${index}].${field}[${gateIndex}] references unknown gate '${gate}'`,
      );
    }
    }
  }
}

const moduleGraph = requireRecord(prd, "moduleGraph");
if (moduleGraph.direction !== "consumer-to-dependency") {
  fail("moduleGraph.direction must equal 'consumer-to-dependency'");
}

const rawNodes = Array.isArray(moduleGraph.nodes) ? moduleGraph.nodes : [];
if (!Array.isArray(moduleGraph.nodes)) {
  fail("moduleGraph.nodes must be an array");
}
const nodeIds = new Set();
for (const [index, node] of rawNodes.entries()) {
  const id = nodeId(node, `moduleGraph.nodes[${index}]`);
  if (id === undefined) {
    continue;
  }
  if (nodeIds.has(id)) {
    fail(`moduleGraph.nodes[${index}] duplicates node '${id}'`);
  } else {
    nodeIds.add(id);
  }
}

const artifacts = declaredArtifacts(prd.repositories);
const internalArtifactIds = new Set();
for (const [index, artifact] of artifacts.entries()) {
  if (!isInternalArtifact(artifact)) {
    continue;
  }
  const id = artifactId(artifact, `repositories.artifacts[${index}]`);
  if (id !== undefined) {
    if (internalArtifactIds.has(id)) {
      fail(`repositories.artifacts[${index}] duplicates artifact '${id}'`);
    }
    internalArtifactIds.add(id);
  }
}

for (const artifact of internalArtifactIds) {
  if (!nodeIds.has(artifact)) {
    fail(`internal artifact '${artifact}' is missing from moduleGraph.nodes`);
  }
}

const aggregateNames = aggregateRepositoryNames(
  prd.repositories,
  internalArtifactIds,
  [...nodeIds],
);
for (const node of rawNodes) {
  if (
    isRecord(node) &&
    node.kind === "internal-bundle" &&
    nonEmptyString(node.artifact)
  ) {
    aggregateNames.delete(node.artifact);
  }
}
for (const aggregate of aggregateNames) {
  if (nodeIds.has(aggregate)) {
    fail(`moduleGraph.nodes contains aggregate repository name '${aggregate}'`);
  }
}

const graphEdges = validateEdgeList(moduleGraph.edges, "moduleGraph.edges", nodeIds);
validateDag(nodeIds, graphEdges);
if (moduleGraph.forbiddenEdges !== undefined) {
  validateEdgeList(
    moduleGraph.forbiddenEdges,
    "moduleGraph.forbiddenEdges",
    nodeIds,
  );
}

if (errors.length > 0) {
  process.stderr.write(
    `PRD validation failed with ${errors.length} error${errors.length === 1 ? "" : "s"}:\n`,
  );
  for (const error of errors) {
    process.stderr.write(`- ${error}\n`);
  }
  process.exit(1);
}

process.stdout.write(
  `PRD valid: ${allIds.size} unique IDs, ${nodeIds.size} artifact nodes, ` +
    `${graphEdges.length} consumer-to-dependency edges, acyclic DAG.\n`,
);
