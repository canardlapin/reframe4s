import {
  holmAdjust,
  mean,
  pairedContrast,
  quantile,
  studentizedLowerBound,
} from "./motion-statistics.mjs";

const candidate = "reframe4s_motion";
const headlineComparators = ["afni_3dvolreg", "nifreeze"];
const contextComparators = ["fsl_mcflirt"];
const implementations = [candidate, ...headlineComparators, ...contextComparators];
const coPrimaryMetrics = [
  "physical_landmark_displacement_p95_mm",
  "corrected_image_nrmse",
  "native_end_to_end_elapsed_seconds",
  "common_court_elapsed_seconds",
];
const guardrailMetrics = [
  "relative_rotation_error_p95_degrees",
  "framewise_displacement_error_p95_mm",
  "boundary_shell_nrmse",
  "temporal_difference_nrmse",
  "edge_energy_log_error",
  "ringing_fraction",
  "failure_rate",
];
const commonAccuracy = new Set([
  "physical_landmark_displacement_p95_mm",
  "corrected_image_nrmse",
  "relative_rotation_error_p95_degrees",
  "framewise_displacement_error_p95_mm",
]);
const nativeFidelity = new Set([
  "boundary_shell_nrmse",
  "temporal_difference_nrmse",
  "edge_energy_log_error",
  "ringing_fraction",
]);

function key(...parts) {
  return parts.join("\u001f");
}

function median(values) {
  return quantile(values, 0.5);
}

function metricRegistry(protocol) {
  return new Map(protocol.metrics.map((metric) => [metric.id, metric]));
}

function scenarioRegistry(protocol) {
  return new Map(
    protocol.scenarioGeneration.scenarios.map((scenario) => [
      scenario.id,
      scenario.weight,
    ]),
  );
}

function expectedSubjects(protocol, assets) {
  const synthetic = protocol.data.syntheticSubjects.seeds.map(
    (seed) => `synthetic-${seed}`,
  );
  const real = [
    ...new Set(
      assets.assets
        .filter((asset) => asset.stratum === "real_anatomy")
        .map((asset) => asset.subject),
    ),
  ].sort();
  return { synthetic, real_anatomy: real };
}

function metricCourt(metric) {
  if (commonAccuracy.has(metric)) return "common_resampler_estimation";
  if (nativeFidelity.has(metric)) return "native_end_to_end";
  return null;
}

function buildMetricIndex(records, errors) {
  const index = new Map();
  for (const record of records) {
    const recordKey = key(
      record.stratum,
      record.subject,
      record.scenario,
      record.court,
      record.implementation,
    );
    if (index.has(recordKey)) {
      errors.push(`duplicate metric record ${recordKey}`);
    } else {
      index.set(recordKey, record);
    }
  }
  return index;
}

function buildTimingIndex(records, measuredRuns, errors) {
  const grouped = Map.groupBy(
    records.filter(
      (record) =>
        record.phase === "measured" &&
        record.allocatedCoreCount === 4,
    ),
    (record) =>
      key(
        record.subject,
        record.scenario,
        record.court,
        record.implementation,
      ),
  );
  const index = new Map();
  for (const [groupKey, rows] of grouped) {
    if (rows.length !== measuredRuns) {
      errors.push(
        `timing group ${groupKey} has ${rows.length} rows, expected ${measuredRuns}`,
      );
      continue;
    }
    if (rows.some((row) => row.termination !== "success")) {
      errors.push(`timing group ${groupKey} contains a failed run`);
      continue;
    }
    const values = rows.map((row) => row.stageTimes?.endToEndSeconds);
    if (!values.every((value) => Number.isFinite(value) && value >= 0)) {
      errors.push(`timing group ${groupKey} contains invalid elapsed time`);
      continue;
    }
    index.set(groupKey, median(values));
  }
  return index;
}

function buildFailureIndex(records) {
  const grouped = Map.groupBy(
    records,
    (record) =>
      key(record.subject, record.scenario, record.implementation),
  );
  return new Map(
    [...grouped].map(([groupKey, rows]) => [
      groupKey,
      rows.filter((row) => row.termination !== "success").length / rows.length,
    ]),
  );
}

function observation({
  metric,
  subject,
  scenario,
  implementation,
  stratum,
  metricIndex,
  timingIndex,
  failureIndex,
}) {
  if (metric === "failure_rate") {
    return failureIndex.get(key(subject, scenario, implementation));
  }
  if (metric === "native_end_to_end_elapsed_seconds") {
    return timingIndex.get(
      key(subject, scenario, "native_end_to_end", implementation),
    );
  }
  if (metric === "common_court_elapsed_seconds") {
    return timingIndex.get(
      key(
        subject,
        scenario,
        "common_resampler_estimation",
        implementation,
      ),
    );
  }
  const court = metricCourt(metric);
  return metricIndex.get(
    key(stratum, subject, scenario, court, implementation),
  )?.metrics?.[metric];
}

function pairedEffect(candidateValue, comparatorValue, metric) {
  if (
    metric === "native_end_to_end_elapsed_seconds" ||
    metric === "common_court_elapsed_seconds"
  ) {
    return Math.log(comparatorValue / candidateValue);
  }
  return comparatorValue - candidateValue;
}

function valuesForContrast({
  subjects,
  scenarios,
  weights,
  stratum,
  comparator,
  metric,
  indexes,
  errors,
}) {
  const values = [];
  for (const subject of subjects) {
    let total = 0;
    let complete = true;
    for (const scenario of scenarios) {
      const candidateValue = observation({
        metric,
        subject,
        scenario,
        implementation: candidate,
        stratum,
        ...indexes,
      });
      const comparatorValue = observation({
        metric,
        subject,
        scenario,
        implementation: comparator,
        stratum,
        ...indexes,
      });
      if (
        !Number.isFinite(candidateValue) ||
        !Number.isFinite(comparatorValue)
      ) {
        errors.push(
          `missing ${stratum} ${subject}/${scenario} ${metric} contrast against ${comparator}`,
        );
        complete = false;
      } else {
        total +=
          weights.get(scenario) *
          pairedEffect(candidateValue, comparatorValue, metric);
      }
    }
    if (complete) values.push(total);
  }
  return values;
}

function valuesForScenario({
  subjects,
  scenario,
  stratum,
  comparator,
  metric,
  indexes,
  errors,
}) {
  const values = [];
  for (const subject of subjects) {
    const candidateValue = observation({
      metric,
      subject,
      scenario,
      implementation: candidate,
      stratum,
      ...indexes,
    });
    const comparatorValue = observation({
      metric,
      subject,
      scenario,
      implementation: comparator,
      stratum,
      ...indexes,
    });
    if (
      !Number.isFinite(candidateValue) ||
      !Number.isFinite(comparatorValue)
    ) {
      errors.push(
        `missing ${stratum} ${subject}/${scenario} ${metric} scenario contrast against ${comparator}`,
      );
    } else {
      values.push(pairedEffect(candidateValue, comparatorValue, metric));
    }
  }
  return values;
}

function candidateScenarioValues({
  subjects,
  scenario,
  stratum,
  metric,
  indexes,
  errors,
}) {
  const values = [];
  for (const subject of subjects) {
    const value = observation({
      metric,
      subject,
      scenario,
      implementation: candidate,
      stratum,
      ...indexes,
    });
    if (!Number.isFinite(value)) {
      errors.push(
        `missing candidate ${stratum} ${subject}/${scenario} ${metric}`,
      );
    } else {
      values.push(value);
    }
  }
  return values;
}

function contrastFamily({
  comparators,
  metrics,
  subjects,
  scenarios,
  weights,
  stratum,
  registry,
  indexes,
  errors,
  confidence,
  settings,
}) {
  const raw = [];
  for (const comparator of comparators) {
    for (const metric of metrics) {
      const values = valuesForContrast({
        subjects,
        scenarios,
        weights,
        stratum,
        comparator,
        metric,
        indexes,
        errors,
      });
      if (values.length !== subjects.length) continue;
      const registration = registry.get(metric);
      raw.push({
        id: `${comparator}:${metric}`,
        comparator,
        metric,
        ...pairedContrast({
          values,
          margin: registration.practicalSuperiorityMargin,
          confidence,
          bootstrapDraws: settings.bootstrapDraws,
          bootstrapSeed: settings.bootstrapSeed,
          signFlipDraws: settings.signFlipDraws,
          signFlipSeed: settings.signFlipSeed,
        }),
      });
    }
  }
  return holmAdjust(raw).map((result) => ({
    ...result,
    passed:
      result.lowerConfidenceBound > result.margin &&
      result.adjustedPValue <= 0.05,
  }));
}

function scenarioGuardrails({
  comparators,
  metrics,
  subjects,
  scenarios,
  stratum,
  registry,
  indexes,
  errors,
  settings,
}) {
  const results = [];
  const confidence = 1 - 0.05 / (comparators.length * scenarios.length);
  for (const metric of metrics) {
    const registration = registry.get(metric);
    for (const comparator of comparators) {
      for (const scenario of scenarios) {
        const values = valuesForScenario({
          subjects,
          scenario,
          stratum,
          comparator,
          metric,
          indexes,
          errors,
        });
        if (values.length !== subjects.length) continue;
        const lower = studentizedLowerBound(
          values,
          confidence,
          settings.bootstrapDraws,
          settings.bootstrapSeed,
        );
        results.push({
          comparator,
          metric,
          scenario,
          clusterCount: values.length,
          estimate: mean(values),
          confidence,
          lowerConfidenceBound: lower,
          minimumAcceptedEffect:
            -registration.scenarioNonInferiorityMargin,
          passed:
            lower > -registration.scenarioNonInferiorityMargin,
        });
      }
    }
  }
  return results;
}

function absoluteGates({
  subjectsByStratum,
  scenarios,
  registry,
  indexes,
  errors,
}) {
  const results = [];
  for (const [stratum, subjects] of Object.entries(subjectsByStratum)) {
    for (const metric of [...coPrimaryMetrics, ...guardrailMetrics]) {
      const gate = registry.get(metric)?.candidateAbsoluteGate;
      if (gate === null || gate === undefined) continue;
      for (const scenario of scenarios) {
        const values = candidateScenarioValues({
          subjects,
          scenario,
          stratum,
          metric,
          indexes,
          errors,
        });
        if (values.length !== subjects.length) continue;
        const estimate = mean(values);
        results.push({
          stratum,
          metric,
          scenario,
          clusterCount: values.length,
          estimate,
          maximumAccepted: gate,
          passed: estimate <= gate,
        });
      }
    }
  }
  return results;
}

function implementationFailureGates(records) {
  return implementations.map((implementation) => {
    const rows = records.filter(
      (record) => record.implementation === implementation,
    );
    const failures = rows.filter(
      (record) => record.termination !== "success",
    ).length;
    const rate = rows.length === 0 ? Number.NaN : failures / rows.length;
    return {
      implementation,
      requiredRuns: rows.length,
      failures,
      failureRate: rate,
      maximumAccepted: 0.02,
      passed: Number.isFinite(rate) && rate <= 0.02,
    };
  });
}

function memoryEvidence(records) {
  return implementations.flatMap((implementation) =>
    ["common_resampler_estimation", "native_end_to_end"].map((court) => {
      const rows = records.filter(
        (record) =>
          record.implementation === implementation &&
          record.court === court &&
          record.phase === "measured" &&
          record.allocatedCoreCount === 4,
      );
      const values = rows
        .map((record) => record.peakRssBytes)
        .filter(Number.isFinite);
      return {
        implementation,
        court,
        requiredRows: rows.length,
        observedRows: values.length,
        p95Bytes:
          values.length === 0 ? null : quantile(values, 0.95),
        complete: values.length === rows.length && rows.length > 0,
      };
    }),
  );
}

export function analyzeMotionCourt({
  protocol,
  assets,
  runs,
  metricRecords,
  settings = {},
}) {
  const resolvedSettings = {
    bootstrapDraws: settings.bootstrapDraws ?? 10000,
    bootstrapSeed: settings.bootstrapSeed ?? 7301,
    signFlipDraws: settings.signFlipDraws ?? 100000,
    signFlipSeed: settings.signFlipSeed ?? 7302,
  };
  const errors = [];
  const registry = metricRegistry(protocol);
  const weights = scenarioRegistry(protocol);
  const scenarios = [...weights.keys()];
  const subjectsByStratum = expectedSubjects(protocol, assets);
  const metricIndex = buildMetricIndex(metricRecords, errors);
  const timingIndex = buildTimingIndex(
    runs,
    protocol.execution.otherImplementations.measuredRuns,
    errors,
  );
  const failureIndex = buildFailureIndex(runs);
  const indexes = { metricIndex, timingIndex, failureIndex };

  const expectedMetricCount =
    (subjectsByStratum.synthetic.length +
      subjectsByStratum.real_anatomy.length) *
    scenarios.length *
    2 *
    implementations.length;
  if (metricRecords.length !== expectedMetricCount) {
    errors.push(
      `metric evidence has ${metricRecords.length} rows, expected ${expectedMetricCount}`,
    );
  }

  const headline = contrastFamily({
    comparators: headlineComparators,
    metrics: coPrimaryMetrics,
    subjects: subjectsByStratum.synthetic,
    scenarios,
    weights,
    stratum: "synthetic",
    registry,
    indexes,
    errors,
    confidence: 0.99375,
    settings: resolvedSettings,
  });
  const context = contrastFamily({
    comparators: contextComparators,
    metrics: coPrimaryMetrics,
    subjects: subjectsByStratum.synthetic,
    scenarios,
    weights,
    stratum: "synthetic",
    registry,
    indexes,
    errors,
    confidence: 0.95,
    settings: resolvedSettings,
  });
  const syntheticCoPrimaryGuardrails = scenarioGuardrails({
    comparators: headlineComparators,
    metrics: coPrimaryMetrics,
    subjects: subjectsByStratum.synthetic,
    scenarios,
    stratum: "synthetic",
    registry,
    indexes,
    errors,
    settings: resolvedSettings,
  });
  const syntheticGuardrails = scenarioGuardrails({
    comparators: headlineComparators,
    metrics: guardrailMetrics,
    subjects: subjectsByStratum.synthetic,
    scenarios,
    stratum: "synthetic",
    registry,
    indexes,
    errors,
    settings: resolvedSettings,
  });
  const realCoPrimaryGuardrails = scenarioGuardrails({
    comparators: headlineComparators,
    metrics: coPrimaryMetrics,
    subjects: subjectsByStratum.real_anatomy,
    scenarios,
    stratum: "real_anatomy",
    registry,
    indexes,
    errors,
    settings: resolvedSettings,
  });
  const realGuardrails = scenarioGuardrails({
    comparators: headlineComparators,
    metrics: guardrailMetrics,
    subjects: subjectsByStratum.real_anatomy,
    scenarios,
    stratum: "real_anatomy",
    registry,
    indexes,
    errors,
    settings: resolvedSettings,
  });
  const absolutes = absoluteGates({
    subjectsByStratum,
    scenarios,
    registry,
    indexes,
    errors,
  });
  const failureGates = implementationFailureGates(runs);
  const memory = memoryEvidence(runs);

  const sections = {
    headline,
    context,
    syntheticCoPrimaryGuardrails,
    syntheticGuardrails,
    realCoPrimaryGuardrails,
    realGuardrails,
    absoluteGates: absolutes,
    implementationFailureGates: failureGates,
    memory,
  };
  const allRequiredResults = [
    ...headline,
    ...syntheticCoPrimaryGuardrails,
    ...syntheticGuardrails,
    ...realCoPrimaryGuardrails,
    ...realGuardrails,
    ...absolutes,
    ...failureGates,
  ];
  const completeCounts =
    headline.length === 8 &&
    context.length === 4 &&
    syntheticCoPrimaryGuardrails.length === 32 &&
    syntheticGuardrails.length === 56 &&
    realCoPrimaryGuardrails.length === 32 &&
    realGuardrails.length === 56;
  if (!completeCounts) {
    errors.push("registered hypothesis or guardrail families are incomplete");
  }
  if (memory.some((entry) => !entry.complete)) {
    errors.push("peak-RSS evidence is incomplete");
  }
  const admissible = errors.length === 0;
  const passed =
    admissible &&
    allRequiredResults.every((result) => result.passed === true);
  return {
    errors,
    settings: resolvedSettings,
    courtMapping: {
      commonAccuracy: [...commonAccuracy],
      nativeFidelity: [...nativeFidelity],
      failureRate: "all required run rows",
      timing: "twenty measured four-core process-boundary repetitions",
    },
    ...sections,
    decision: {
      admissible,
      passed,
      status: !admissible ? "inadmissible" : passed ? "passed" : "failed",
    },
  };
}
