#!/usr/bin/env node

import { createHash } from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

import { analyzeMotionCourt } from "./analyze-motion-court.mjs";
import { readPlan } from "./verify-motion-run-records.mjs";
import { readScoringPlan } from "./verify-motion-metrics.mjs";

function hashFile(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

function countBy(values, select) {
  const counts = {};
  for (const value of values) {
    const selected = select(value);
    counts[selected] = (counts[selected] ?? 0) + 1;
  }
  return Object.fromEntries(
    Object.entries(counts).sort(([left], [right]) =>
      left.localeCompare(right),
    ),
  );
}

function visibleLosses(analysis) {
  const sections = [
    ["headline", analysis.headline],
    ["context", analysis.context],
    [
      "synthetic_co_primary_guardrail",
      analysis.syntheticCoPrimaryGuardrails,
    ],
    ["synthetic_guardrail", analysis.syntheticGuardrails],
    ["real_co_primary_guardrail", analysis.realCoPrimaryGuardrails],
    ["real_guardrail", analysis.realGuardrails],
    ["absolute_gate", analysis.absoluteGates],
    ["failure_gate", analysis.implementationFailureGates],
  ];
  return sections.flatMap(([family, values]) =>
    values
      .filter((value) => value.passed === false)
      .map((value) => ({ family, ...value })),
  );
}

function permittedLanguage(protocol, decision) {
  if (decision.status === "passed") return protocol.claim.acceptedLanguage;
  if (decision.status === "failed") return protocol.claim.unmetLanguage;
  return (
    "No motion superiority claim is permitted: the preregistered court " +
    "is incomplete or inadmissible."
  );
}

export function buildReceipt({
  protocol,
  protocolSha256,
  lock,
  assetsSha256,
  planSha256,
  scoringPlanSha256,
  executionAggregateSha256,
  requiredRunCount,
  requiredMetricRecordCount,
  runs,
  metricRecords,
  analysis,
}) {
  const runFailures = runs
    .filter((run) => run.termination !== "success")
    .map((run) => ({
      subject: run.subject,
      scenario: run.scenario,
      court: run.court,
      implementation: run.implementation,
      phase: run.phase,
      repetition: run.repetition,
      allocatedCoreCount: run.allocatedCoreCount,
      termination: run.termination,
      exitStatus: run.exitStatus,
      failure: run.failure,
      stdoutSha256: run.stdoutSha256,
      stderrSha256: run.stderrSha256,
    }));
  const stageCoverage = countBy(runs, (run) => run.stageTimes.source);
  const losses = visibleLosses(analysis);
  const decision = {
    ...analysis.decision,
    completePassRuleSatisfied: analysis.decision.passed,
    visibleLossCount: losses.length,
  };
  return {
    schemaVersion: "reframe4s.motion-superiority.receipt/v2",
    protocolSha256,
    candidateRevision: lock.candidate.revision,
    artifactSha256: lock.candidate.artifactSha256,
    environmentLockSha256: lock.environmentLockSha256,
    dataLockSha256: lock.dataLockSha256,
    hardware: lock.hardware,
    implementations: {
      candidate: lock.candidate,
      comparators: lock.implementations,
      workers: lock.workers,
      runner: lock.runner,
    },
    randomization: protocol.execution.randomization,
    runs: {
      requiredRunCount: requiredRunCount ?? runs.length,
      observedRunCount: runs.length,
      terminationCounts: countBy(runs, (run) => run.termination),
      stageTimingCoverage: stageCoverage,
      planSha256,
      executionAggregateSha256,
    },
    metrics: {
      definitionVersion: "motion-metrics-v1",
      requiredMetricRecordCount:
        requiredMetricRecordCount ?? metricRecords.length,
      rawMetricRecordCount: metricRecords.length,
      scoringPlanSha256,
      courtMapping: analysis.courtMapping,
      absoluteGates: analysis.absoluteGates,
    },
    hypotheses: {
      settings: analysis.settings,
      headline: analysis.headline,
      context: analysis.context,
    },
    guardrails: {
      syntheticCoPrimary: analysis.syntheticCoPrimaryGuardrails,
      syntheticAccuracyFidelityReliability:
        analysis.syntheticGuardrails,
      realCoPrimary: analysis.realCoPrimaryGuardrails,
      realAccuracyFidelityReliability: analysis.realGuardrails,
      implementationFailureRates: analysis.implementationFailureGates,
      peakResidentMemory: analysis.memory,
    },
    failures: {
      analysisErrors: analysis.errors,
      runFailures,
      visibleLosses: losses,
      unavailableOptionalEvidence: [
        {
          id: "native_tsnr",
          reason:
            "Prospectively optional downstream context; not an accuracy endpoint.",
        },
        {
          id: "native_dvars",
          reason:
            "Prospectively optional downstream context; not an accuracy endpoint.",
        },
      ],
    },
    claimDecision: decision,
    permittedLanguage: permittedLanguage(protocol, decision),
    sourceEvidence: {
      admissionLockSchemaVersion: lock.schemaVersion,
      benchmarkAssetManifestSha256: assetsSha256,
      environmentManifestSha256: lock.environmentLockSha256,
      protocolVersion: protocol.version,
      protocolId: protocol.protocolId,
    },
  };
}

function table(rows, columns) {
  const header = `| ${columns.map((column) => column.label).join(" | ")} |`;
  const separator = `| ${columns.map(() => "---").join(" | ")} |`;
  const body = rows.map(
    (row) =>
      `| ${columns
        .map((column) => String(column.value(row)))
        .join(" | ")} |`,
  );
  return [header, separator, ...body].join("\n");
}

function formatNumber(value) {
  return Number.isFinite(value) ? value.toPrecision(6) : "unavailable";
}

export function renderAudit(receipt) {
  const accuracy = receipt.hypotheses.headline.filter((result) =>
    [
      "physical_landmark_displacement_p95_mm",
      "corrected_image_nrmse",
    ].includes(result.metric),
  );
  const runtime = receipt.hypotheses.headline.filter((result) =>
    result.metric.endsWith("_elapsed_seconds"),
  );
  const failed = receipt.failures.visibleLosses;
  const lines = [
    "# reframe4s motion-superiority-v2 audit",
    "",
    `Decision: **${receipt.claimDecision.status}**.`,
    "",
    receipt.permittedLanguage,
    "",
    "## Absolute correctness",
    "",
    table(receipt.metrics.absoluteGates, [
      { label: "Stratum", value: (row) => row.stratum },
      { label: "Scenario", value: (row) => row.scenario },
      { label: "Metric", value: (row) => row.metric },
      { label: "Estimate", value: (row) => formatNumber(row.estimate) },
      { label: "Maximum", value: (row) => formatNumber(row.maximumAccepted) },
      { label: "Pass", value: (row) => row.passed },
    ]),
    "",
    "## Comparative accuracy",
    "",
    table(accuracy, [
      { label: "Comparator", value: (row) => row.comparator },
      { label: "Metric", value: (row) => row.metric },
      { label: "Effect", value: (row) => formatNumber(row.estimate) },
      {
        label: "Lower bound",
        value: (row) => formatNumber(row.lowerConfidenceBound),
      },
      { label: "Margin", value: (row) => formatNumber(row.margin) },
      {
        label: "Holm p",
        value: (row) => formatNumber(row.adjustedPValue),
      },
      { label: "Pass", value: (row) => row.passed },
    ]),
    "",
    "## Image fidelity",
    "",
    `Synthetic guardrails: ${receipt.guardrails.syntheticAccuracyFidelityReliability.filter((row) => row.passed).length}/${receipt.guardrails.syntheticAccuracyFidelityReliability.length} passed.`,
    "",
    `Real-anatomy guardrails: ${receipt.guardrails.realAccuracyFidelityReliability.filter((row) => row.passed).length}/${receipt.guardrails.realAccuracyFidelityReliability.length} passed.`,
    "",
    "## Runtime",
    "",
    table(runtime, [
      { label: "Comparator", value: (row) => row.comparator },
      { label: "Metric", value: (row) => row.metric },
      { label: "Log effect", value: (row) => formatNumber(row.estimate) },
      {
        label: "Lower bound",
        value: (row) => formatNumber(row.lowerConfidenceBound),
      },
      { label: "Margin", value: (row) => formatNumber(row.margin) },
      { label: "Pass", value: (row) => row.passed },
    ]),
    "",
    "## Memory",
    "",
    table(receipt.guardrails.peakResidentMemory, [
      { label: "Implementation", value: (row) => row.implementation },
      { label: "Court", value: (row) => row.court },
      { label: "Observed", value: (row) => `${row.observedRows}/${row.requiredRows}` },
      { label: "p95 bytes", value: (row) => row.p95Bytes ?? "unavailable" },
      { label: "Complete", value: (row) => row.complete },
    ]),
    "",
    "## Failures and visible losses",
    "",
    `Raw run failures: ${receipt.failures.runFailures.length}.`,
    "",
    `Analysis errors: ${receipt.failures.analysisErrors.length}.`,
    "",
    `Failed registered comparisons or gates: ${failed.length}.`,
    "",
    ...failed.map(
      (loss) =>
        `- ${loss.family}: ${loss.comparator ?? loss.stratum ?? loss.implementation ?? "candidate"} / ${loss.scenario ?? "all scenarios"} / ${loss.metric ?? "failure_rate"}`,
    ),
    "",
    "## Optional evidence",
    "",
    ...receipt.failures.unavailableOptionalEvidence.map(
      (entry) => `- ${entry.id}: unavailable — ${entry.reason}`,
    ),
    "",
  ];
  return `${lines.join("\n")}\n`;
}

function option(arguments_, name) {
  const index = arguments_.indexOf(name);
  if (index < 0 || index + 1 >= arguments_.length) {
    throw new Error(`${name} is required`);
  }
  return arguments_[index + 1];
}

export function loadReceiptInputs(arguments_) {
  const protocolPath = option(arguments_, "--protocol");
  const lockPath = option(arguments_, "--lock");
  const assetsPath = option(arguments_, "--assets");
  const planPath = option(arguments_, "--plan");
  const scoringPlanPath = option(arguments_, "--scoring-plan");
  const aggregatePath = option(arguments_, "--execution-aggregate");
  const protocolText = readFileSync(protocolPath, "utf8");
  const protocol = JSON.parse(protocolText);
  const lock = JSON.parse(readFileSync(lockPath, "utf8"));
  const assets = JSON.parse(readFileSync(assetsPath, "utf8"));
  const plan = readPlan(planPath);
  const scoringPlan = readScoringPlan(scoringPlanPath);
  const sourceLoadErrors = [];
  const loadJson = (path, label) => {
    try {
      return JSON.parse(readFileSync(path, "utf8"));
    } catch (error) {
      sourceLoadErrors.push(
        `${label} ${path}: ${String(error?.message ?? error)}`,
      );
      return null;
    }
  };
  const runs = plan
    .map((row) => {
      const path = resolve(row.output_directory, "run.json");
      return loadJson(path, "raw run");
    })
    .filter((value) => value !== null);
  const metricRecords = scoringPlan
    .map((row) => loadJson(resolve(row.output), "metric record"))
    .filter((value) => value !== null);
  const aggregateSha256 = existsSync(aggregatePath)
    ? hashFile(aggregatePath)
    : null;
  if (aggregateSha256 === null) {
    sourceLoadErrors.push(
      `execution aggregate ${aggregatePath}: file is missing`,
    );
  }
  return {
    protocol,
    protocolSha256: createHash("sha256")
      .update(protocolText)
      .digest("hex"),
    lock,
    assets,
    assetsSha256: hashFile(assetsPath),
    planSha256: hashFile(planPath),
    scoringPlanSha256: hashFile(scoringPlanPath),
    executionAggregateSha256: aggregateSha256,
    requiredRunCount: plan.length,
    requiredMetricRecordCount: scoringPlan.length,
    runs,
    metricRecords,
    sourceLoadErrors,
  };
}

export function applySourceLoadErrors(analysis, sourceLoadErrors) {
  if (sourceLoadErrors.length === 0) return analysis;
  return {
    ...analysis,
    errors: [...analysis.errors, ...sourceLoadErrors],
    decision: {
      admissible: false,
      passed: false,
      status: "inadmissible",
    },
  };
}

function main(arguments_) {
  const outputPath = option(arguments_, "--output");
  const auditPath = option(arguments_, "--audit");
  const loaded = loadReceiptInputs(arguments_);
  const analysis = applySourceLoadErrors(
    analyzeMotionCourt({
      protocol: loaded.protocol,
      assets: loaded.assets,
      runs: loaded.runs,
      metricRecords: loaded.metricRecords,
    }),
    loaded.sourceLoadErrors,
  );
  const receipt = buildReceipt({ ...loaded, analysis });
  writeFileSync(outputPath, `${JSON.stringify(receipt, null, 2)}\n`, {
    encoding: "utf8",
    flag: "wx",
  });
  writeFileSync(auditPath, renderAudit(receipt), {
    encoding: "utf8",
    flag: "wx",
  });
  process.stdout.write(
    `motion receipt: ${receipt.claimDecision.status} (${receipt.failures.visibleLosses.length} visible losses)\n`,
  );
  if (receipt.claimDecision.status === "inadmissible") process.exitCode = 2;
  else if (receipt.claimDecision.status === "failed") process.exitCode = 3;
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
