const mask64 = (1n << 64n) - 1n;
const golden = 0x9e3779b97f4a7c15n;

export class SplitMix64 {
  constructor(seed) {
    this.state = BigInt(seed) & mask64;
  }

  nextUint64() {
    this.state = (this.state + golden) & mask64;
    let value = this.state;
    value = ((value ^ (value >> 30n)) * 0xbf58476d1ce4e5b9n) & mask64;
    value = ((value ^ (value >> 27n)) * 0x94d049bb133111ebn) & mask64;
    return (value ^ (value >> 31n)) & mask64;
  }

  nextDouble() {
    return Number(this.nextUint64() >> 11n) / 9007199254740992;
  }

  nextInt(bound) {
    if (!Number.isSafeInteger(bound) || bound <= 0) {
      throw new Error(`invalid random bound ${bound}`);
    }
    return Math.floor(this.nextDouble() * bound);
  }
}

export function mean(values) {
  if (!Array.isArray(values) || values.length === 0) {
    throw new Error("mean requires at least one value");
  }
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

export function sampleStandardDeviation(values) {
  if (values.length < 2) return 0;
  const center = mean(values);
  const sum = values.reduce((total, value) => {
    const difference = value - center;
    return total + difference * difference;
  }, 0);
  return Math.sqrt(sum / (values.length - 1));
}

export function quantile(values, probability) {
  if (
    !Array.isArray(values) ||
    values.length === 0 ||
    !Number.isFinite(probability) ||
    probability < 0 ||
    probability > 1
  ) {
    throw new Error("quantile arguments are invalid");
  }
  const sorted = [...values].sort((left, right) => left - right);
  const position = probability * (sorted.length - 1);
  const lower = Math.floor(position);
  const upper = Math.ceil(position);
  const fraction = position - lower;
  return sorted[lower] * (1 - fraction) + sorted[upper] * fraction;
}

export function studentizedLowerBound(
  values,
  confidence,
  draws,
  seed,
) {
  if (values.length < 2) {
    throw new Error("studentized bootstrap requires at least two clusters");
  }
  if (
    !values.every(Number.isFinite) ||
    !Number.isSafeInteger(draws) ||
    draws <= 0 ||
    !(confidence > 0 && confidence < 1)
  ) {
    throw new Error("studentized bootstrap arguments are invalid");
  }
  const observed = mean(values);
  const observedSe =
    sampleStandardDeviation(values) / Math.sqrt(values.length);
  if (observedSe === 0) return observed;
  const random = new SplitMix64(seed);
  const pivots = [];
  for (let draw = 0; draw < draws; draw += 1) {
    const sample = Array.from(
      { length: values.length },
      () => values[random.nextInt(values.length)],
    );
    const estimate = mean(sample);
    const standardError =
      sampleStandardDeviation(sample) / Math.sqrt(sample.length);
    if (standardError > 0 && Number.isFinite(standardError)) {
      pivots.push((estimate - observed) / standardError);
    }
  }
  if (pivots.length < Math.ceil(draws * 0.99)) {
    throw new Error("studentized bootstrap produced too many zero-SE draws");
  }
  return observed - quantile(pivots, confidence) * observedSe;
}

export function oneSidedSignFlipPValue(values, margin, draws, seed) {
  if (
    values.length < 2 ||
    !values.every(Number.isFinite) ||
    !Number.isFinite(margin) ||
    !Number.isSafeInteger(draws) ||
    draws <= 0
  ) {
    throw new Error("sign-flip arguments are invalid");
  }
  const shifted = values.map((value) => value - margin);
  const observed = mean(shifted);
  const random = new SplitMix64(seed);
  let atLeastObserved = 0;
  for (let draw = 0; draw < draws; draw += 1) {
    let sum = 0;
    for (const value of shifted) {
      sum += random.nextDouble() < 0.5 ? -value : value;
    }
    if (sum / shifted.length >= observed - 1e-15) {
      atLeastObserved += 1;
    }
  }
  return (atLeastObserved + 1) / (draws + 1);
}

export function holmAdjust(entries) {
  const sorted = entries
    .map((entry, index) => ({ ...entry, originalIndex: index }))
    .sort((left, right) => left.pValue - right.pValue);
  let previous = 0;
  const adjusted = new Array(entries.length);
  for (const [index, entry] of sorted.entries()) {
    const value = Math.min(
      1,
      Math.max(previous, entry.pValue * (sorted.length - index)),
    );
    previous = value;
    adjusted[entry.originalIndex] = {
      ...entries[entry.originalIndex],
      adjustedPValue: value,
    };
  }
  return adjusted;
}

export function pairedContrast({
  values,
  margin,
  confidence,
  bootstrapDraws,
  bootstrapSeed,
  signFlipDraws,
  signFlipSeed,
}) {
  const estimate = mean(values);
  const lowerConfidenceBound = studentizedLowerBound(
    values,
    confidence,
    bootstrapDraws,
    bootstrapSeed,
  );
  const pValue = oneSidedSignFlipPValue(
    values,
    margin,
    signFlipDraws,
    signFlipSeed,
  );
  return {
    clusterCount: values.length,
    estimate,
    margin,
    lowerConfidenceBound,
    confidence,
    pValue,
  };
}
