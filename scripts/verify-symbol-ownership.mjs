#!/usr/bin/env node

import {
  readdirSync,
  readFileSync,
  statSync,
} from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const root = resolve(scriptDirectory, "..");
const modulesRoot = resolve(root, "modules");
const image4sRoot = resolve(
  process.env.IMAGE4S_ROOT ?? join(root, "..", "image4s"),
);
const image4sModulesRoot = resolve(image4sRoot, "modules");
const locus4sRoot = resolve(
  process.env.LOCUS4S_ROOT ?? join(root, "..", "locus4s"),
);
const locus4sModulesRoot = resolve(locus4sRoot, "modules");
const declarations = new Map();
const errors = [];

const canonicalOwners = new Map([
  ["image4s.geometry.Dim", "image4s-geometry"],
  ["image4s.geometry.D2", "image4s-geometry"],
  ["image4s.geometry.D3", "image4s-geometry"],
  ["image4s.geometry.Dimension", "image4s-geometry"],
  ["image4s.geometry.Frame", "image4s-geometry"],
  ["image4s.geometry.FrameId", "image4s-geometry"],
  ["image4s.geometry.Point", "image4s-geometry"],
  ["image4s.geometry.Vec", "image4s-geometry"],
  ["image4s.geometry.Grid", "image4s-geometry"],
  ["image4s.geometry.GridId", "image4s-geometry"],
  ["image4s.geometry.Affine", "image4s-geometry"],
  ["image4s.Sampled", "image4s-core"],
  ["image4s.SomeSampled", "image4s-core"],
  ["image4s.Image", "image4s-core"],
  ["image4s.ContinuousImage", "image4s-core"],
  ["image4s.CategoricalImage", "image4s-core"],
  ["image4s.MaskImage", "image4s-core"],
  ["image4s.nifti.Nifti", "image4s-nifti"],
  ["image4s.nifti.NiftiHeader", "image4s-nifti"],
  ["reframe4s.core.SpatialMap", "reframe4s-core"],
  ["reframe4s.core.SmoothMap", "reframe4s-core"],
  ["reframe4s.core.SmoothIso", "reframe4s-core"],
  ["reframe4s.core.AffineMap", "reframe4s-core"],
  ["reframe4s.core.InverseEstimate", "reframe4s-core"],
  ["reframe4s.core.CertifiedBidirectionalPair", "reframe4s-core"],
  ["reframe4s.core.TopologyCertificate", "reframe4s-core"],
  ["reframe4s.resample.Interpolation", "reframe4s-resample"],
  ["reframe4s.resample.SampledInterpolator", "reframe4s-resample"],
  ["reframe4s.resample.ResamplingPlan", "reframe4s-resample"],
  ["reframe4s.resample.CategoricalResamplingPlan", "reframe4s-resample"],
  ["reframe4s.resample.ResamplingWorkspace", "reframe4s-resample"],
  ["reframe4s.resample.LinearValueGradientSampler3", "reframe4s-resample"],
  ["reframe4s.resample.SparseValueGradientBuffer3", "reframe4s-resample"],
  ["reframe4s.field.DenseField", "reframe4s-field"],
  ["reframe4s.field.DenseMap", "reframe4s-field"],
  ["reframe4s.field.Displacement", "reframe4s-field"],
  ["reframe4s.field.Velocity", "reframe4s-field"],
  ["reframe4s.field.Momentum", "reframe4s-field"],
  ["reframe4s.field.TopologyAssessor", "reframe4s-field"],
  ["reframe4s.flow.ExplicitEuler", "reframe4s-flow"],
  ["reframe4s.flow.FlowResult", "reframe4s-flow"],
  ["reframe4s.graph.TransformGraph", "reframe4s-graph"],
  ["reframe4s.graph.TransformEdge", "reframe4s-graph"],
  ["reframe4s.multiscale.ScaleSpec", "reframe4s-multiscale"],
  ["reframe4s.multiscale.ScaleSchedule", "reframe4s-multiscale"],
  ["reframe4s.multiscale.GridTower", "reframe4s-multiscale"],
  ["reframe4s.multiscale.IsotropicGaussianPsf3", "reframe4s-multiscale"],
  ["reframe4s.multiscale.SupportAwarePyramid3", "reframe4s-multiscale"],
  [
    "reframe4s.multiscale.SupportAwarePyramidWorkspace3",
    "reframe4s-multiscale",
  ],
  ["reframe4s.register.RegistrationResult", "reframe4s-register"],
  ["reframe4s.register.BidirectionalRegistrationResult", "reframe4s-register"],
  ["reframe4s.motion.PoseSeries", "reframe4s-motion"],
  ["reframe4s.motion.RigidPose", "reframe4s-motion"],
  ["reframe4s.halfflow.ExperimentalCapabilityLedger", "reframe4s-halfflow"],
  ["reframe4s.spectral.Radix2FftPlan", "reframe4s-spectral"],
  ["reframe4s.spectral.LinearCorrelation3Plan", "reframe4s-spectral"],
  ["reframe4s.flashalign.Flashalign", "reframe4s-flashalign"],
  ["reframe4s.flashalign.FlashalignPlan", "reframe4s-flashalign"],
  ["reframe4s.flashalign.FlashalignWorkspace", "reframe4s-flashalign"],
  ["reframe4s.flashalign.FlashalignResult", "reframe4s-flashalign"],
  ["locus4s.DomainId", "locus4s-core"],
  ["locus4s.FiniteSpace", "locus4s-core"],
  ["locus4s.Region", "locus4s-core"],
  ["locus4s.Selection", "locus4s-core"],
  ["locus4s.Relation", "locus4s-core"],
  ["locus4s.TotalMap", "locus4s-core"],
]);

function scalaSources(directory) {
  const results = [];
  for (const entry of readdirSync(directory)) {
    const path = join(directory, entry);
    const status = statSync(path);
    if (status.isDirectory()) {
      results.push(...scalaSources(path));
    } else if (entry.endsWith(".scala")) {
      results.push(path);
    }
  }
  return results;
}

const artifactRoots = readdirSync(modulesRoot).map((artifact) => [
  artifact,
  join(modulesRoot, artifact),
]);

for (const artifact of [
  "image4s-geometry",
  "image4s-core",
  "image4s-nifti",
  "image4s-reference",
  "image4s-laws",
  "image4s-locus",
]) {
  const artifactRoot = join(image4sModulesRoot, artifact);
  try {
    if (statSync(artifactRoot).isDirectory()) {
      artifactRoots.push([artifact, artifactRoot]);
    }
  } catch {
    errors.push(
      `standalone image4s artifact ${artifact} is missing at ` +
        relative(root, artifactRoot),
    );
  }
}

for (const artifact of ["locus4s-core", "locus4s-data", "locus4s-laws"]) {
  const artifactRoot = join(locus4sModulesRoot, artifact);
  try {
    if (statSync(artifactRoot).isDirectory()) {
      artifactRoots.push([artifact, artifactRoot]);
    }
  } catch {
    errors.push(
      `standalone locus4s artifact ${artifact} is missing at ` +
        relative(root, artifactRoot),
    );
  }
}

for (const [artifact, artifactRoot] of artifactRoots) {
  if (!statSync(artifactRoot).isDirectory()) {
    continue;
  }
  const sourceRoots = [
    join(artifactRoot, "src", "main", "scala"),
    join(artifactRoot, "shared", "src", "main", "scala"),
    join(artifactRoot, "jvm", "src", "main", "scala"),
    join(artifactRoot, "js", "src", "main", "scala"),
  ].filter((path) => {
    try {
      return statSync(path).isDirectory();
    } catch {
      return false;
    }
  });

  for (const sourceRoot of sourceRoots) {
    for (const source of scalaSources(sourceRoot)) {
      const text = readFileSync(source, "utf8");
      const packageMatch = text.match(
        /^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$/mu,
      );
      if (packageMatch === null) {
        errors.push(`${relative(root, source)} has no simple package declaration`);
        continue;
      }
      const packageName = packageMatch[1];
      const declarationPattern =
        /^(?!\s*private(?:\[|\s))\s*(?:sealed\s+|abstract\s+|final\s+|case\s+)*(?:class|trait|object|enum|opaque\s+type|type)\s+([A-Z][A-Za-z0-9_]*)/gmu;
      for (const match of text.matchAll(declarationPattern)) {
        const qualifiedName = `${packageName}.${match[1]}`;
        if (!declarations.has(qualifiedName)) {
          declarations.set(qualifiedName, new Map());
        }
        const byArtifact = declarations.get(qualifiedName);
        if (!byArtifact.has(artifact)) {
          byArtifact.set(artifact, []);
        }
        byArtifact.get(artifact).push(relative(root, source));
      }
    }
  }
}

for (const [qualifiedName, byArtifact] of declarations) {
  if (byArtifact.size > 1) {
    errors.push(
      `${qualifiedName} is publicly declared by multiple artifacts: ` +
        [...byArtifact.keys()].sort().join(", "),
    );
  }
}

for (const [qualifiedName, expectedArtifact] of canonicalOwners) {
  const byArtifact = declarations.get(qualifiedName);
  if (byArtifact === undefined) {
    errors.push(`canonical symbol ${qualifiedName} is not declared`);
  } else if (
    byArtifact.size !== 1 ||
    !byArtifact.has(expectedArtifact)
  ) {
    errors.push(
      `canonical symbol ${qualifiedName} must be owned only by ` +
        `${expectedArtifact}; found ${[...byArtifact.keys()].sort().join(", ")}`,
    );
  }
}

if (errors.length > 0) {
  process.stderr.write(
    `Symbol ownership validation failed with ${errors.length} ` +
      `error${errors.length === 1 ? "" : "s"}:\n`,
  );
  for (const error of errors) {
    process.stderr.write(`- ${error}\n`);
  }
  process.exit(1);
}

process.stdout.write(
  `Symbol ownership is unique across ${declarations.size} public qualified names; ` +
    `${canonicalOwners.size} canonical owners verified.\n`,
);
