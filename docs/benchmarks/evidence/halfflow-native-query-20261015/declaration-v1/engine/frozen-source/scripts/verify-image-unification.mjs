#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const args = process.argv.slice(2);
const reportOnly = args.includes("--report-only");
const summaryOnly = args.includes("--summary-only");
const rootFlag = args.indexOf("--scalafim");
const scalaFimRoot =
  rootFlag >= 0 && args[rootFlag + 1]
    ? path.resolve(args[rootFlag + 1])
    : path.resolve("../scalafim");

const sourceRoot = path.join(scalaFimRoot, "modules");
if (!fs.existsSync(sourceRoot)) {
  console.error(
    `image-unification: ScalaFIM modules directory does not exist: ${sourceRoot}`,
  );
  process.exit(2);
}

const scalaFiles = [];
walk(sourceRoot, scalaFiles);

const declarations = [
  {
    id: "parallel-ndarray-owner",
    pattern: /\b(?:final\s+)?class\s+NDArray\s*\[/,
    message: "ScalaFIM still declares a dense NDArray owner",
  },
  {
    id: "parallel-neuroimage-owner",
    pattern: /\b(?:final\s+)?class\s+NeuroImage\s*\[/,
    message: "ScalaFIM still declares a dense NeuroImage owner",
  },
  {
    id: "parallel-neuroimage-view",
    pattern: /\btrait\s+NeuroImageView\s*\[/,
    message: "ScalaFIM still declares the legacy NeuroImage view hierarchy",
  },
  {
    id: "parallel-neurospace-owner",
    pattern: /\b(?:final\s+)?(?:case\s+)?class\s+NeuroSpace\b/,
    message: "ScalaFIM still declares NeuroSpace as a parallel geometry owner",
  },
  {
    id: "parallel-gridspec-owner",
    pattern: /\b(?:final\s+)?case\s+class\s+GridSpec\b/,
    message: "ScalaFIM still declares GridSpec as a parallel grid owner",
  },
  {
    id: "parallel-component-field-owner",
    pattern: /\b(?:final\s+)?case\s+class\s+DenseVectorField\b/,
    message: "ScalaFIM still declares a component-field storage owner",
  },
  {
    id: "duplicate-dense-field-kernels",
    pattern: /\bobject\s+DenseFieldKernels\b/,
    message: "ScalaFIM still declares the duplicate dense-field kernel stack",
  },
];

const rawAccess = {
  id: "raw-image-buffer-access",
  pattern: /\.values\.data\b/,
  message: "ScalaFIM still reads an image or component field through raw .values.data",
};

const findings = [];
for (const file of scalaFiles) {
  const relative = path.relative(scalaFimRoot, file);
  const lines = fs.readFileSync(file, "utf8").split(/\r?\n/);
  lines.forEach((line, index) => {
    for (const check of declarations) {
      if (check.pattern.test(line)) {
        findings.push(finding(check, relative, index + 1, line));
      }
    }
    if (rawAccess.pattern.test(line)) {
      findings.push(finding(rawAccess, relative, index + 1, line));
    }
  });
}

const required = [
  requiredPattern(
    "dense-volume-sampled-owner",
    "modules/image/shared/src/main/scala/scalafim/image/NeuroVol.scala",
    /\bSampled\s*\[/,
    "NeuroVol must retain or alias the canonical image4s Sampled value",
  ),
  requiredPattern(
    "dense-series-sampled-owner",
    "modules/image/shared/src/main/scala/scalafim/image/NeuroVec.scala",
    /\bSampled\s*\[/,
    "NeuroVec must retain or alias the canonical image4s Sampled value",
  ),
  requiredPattern(
    "sparse-ravel-rank-two",
    "modules/image/shared/src/main/scala/scalafim/image/SparseNeuroVec.scala",
    /RavelArray\s*\[\s*A\s*,\s*Rank\s*\[\s*2\s*\]\s*\]/,
    "SparseNeuroVec must store its compact payload in rank-2 Ravel",
  ),
  requiredPattern(
    "sparse-typed-support",
    "modules/image/shared/src/main/scala/scalafim/image/SparseNeuroVec.scala",
    /\bSparseSupport\b|\bGridDomain\b|\bSelection\b/,
    "SparseNeuroVec must name typed spatial support rather than a sparse array hierarchy",
  ),
  requiredPattern(
    "component-image-owner",
    "modules/image/shared/src/main/scala/scalafim/image/MorphismFields.scala",
    /\bComponentImage\b/,
    "ScalaFIM component fields must use the canonical image4s ComponentImage",
  ),
];

for (const check of required) {
  const file = path.join(scalaFimRoot, check.file);
  if (!fs.existsSync(file)) {
    findings.push({
      id: check.id,
      message: `${check.message}; source file is missing`,
      file: check.file,
      line: 0,
      source: "",
    });
  } else {
    const source = fs.readFileSync(file, "utf8");
    if (!check.pattern.test(source)) {
      findings.push({
        id: check.id,
        message: check.message,
        file: check.file,
        line: 0,
        source: "",
      });
    }
  }
}

const buildFile = path.join(scalaFimRoot, "build.sbt");
if (fs.existsSync(buildFile)) {
  const buildSource = fs.readFileSync(buildFile, "utf8");
  if (
    /Temporary source-composite admission route/.test(buildSource) ||
    /scalafim\.reframe4s\.build/.test(buildSource)
  ) {
    findings.push({
      id: "temporary-source-composite",
      message:
        "ScalaFIM still resolves image4s through the temporary mutable source composite",
      file: "build.sbt",
      line: lineOf(buildSource, /Temporary source-composite admission route|scalafim\.reframe4s\.build/),
      source: "",
    });
  }
}

const findingCounts = {};
const rawAccessByModule = {};
const rawAccessBySourceKind = {};
for (const item of findings) {
  findingCounts[item.id] = (findingCounts[item.id] ?? 0) + 1;
  if (item.id === rawAccess.id) {
    const parts = item.file.split(path.sep);
    const moduleName = parts[0] === "modules" ? parts[1] : "other";
    rawAccessByModule[moduleName] =
      (rawAccessByModule[moduleName] ?? 0) + 1;
    const sourceKind = parts.includes("test") ? "test" : "main";
    rawAccessBySourceKind[sourceKind] =
      (rawAccessBySourceKind[sourceKind] ?? 0) + 1;
  }
}
const summary = {
  scalaFimRoot,
  filesScanned: scalaFiles.length,
  passed: findings.length === 0,
  findingCounts,
  rawAccessBySourceKind,
  rawAccessByModule,
};

console.log(JSON.stringify(summary, null, 2));
if (!summaryOnly) {
  for (const item of findings) {
    const location = item.line > 0 ? `${item.file}:${item.line}` : item.file;
    console.log(`\n[${item.id}] ${item.message}`);
    console.log(`  ${location}`);
    if (item.source) console.log(`  ${item.source.trim()}`);
  }
}

if (!summary.passed && !reportOnly) process.exit(1);

function walk(directory, output) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const absolute = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      if (entry.name !== "target" && entry.name !== ".git") walk(absolute, output);
    } else if (entry.isFile() && entry.name.endsWith(".scala")) {
      output.push(absolute);
    }
  }
}

function finding(check, file, line, source) {
  return {
    id: check.id,
    message: check.message,
    file,
    line,
    source,
  };
}

function requiredPattern(id, file, pattern, message) {
  return { id, file, pattern, message };
}

function lineOf(source, pattern) {
  const lines = source.split(/\r?\n/);
  const index = lines.findIndex((line) => pattern.test(line));
  return index < 0 ? 0 : index + 1;
}
