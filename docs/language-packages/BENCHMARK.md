# Correction benchmark and score fusion

**Benchmark format:** `0.1`  
**Fusion status:** Experimental, not connected to production suggestions

This document defines how candidate generators, context rankers, and autocorrect policies are evaluated before they are allowed to influence live keyboard output.

The benchmark treats suggestion ranking and automatic correction as separate quality problems.

## Why ranking accuracy is not enough

A system can improve Top-1 accuracy while becoming more irritating or dangerous if it automatically replaces valid words too aggressively.

The benchmark therefore records both:

- whether the expected text is ranked well;
- whether the system makes the correct automatic-correction decision.

Examples that must be represented in both directions include:

```text
Ich bin seit gestern hier.       → keep "seit"
Ihr seid heute früh dran.        → replace "seit" with "seid"
```

A model cannot solve these cases reliably through word frequency alone.

## Benchmark suite structure

A suite contains:

- format version;
- stable suite ID and name;
- language tags;
- license;
- benchmark cases.

Each case contains:

- stable case ID;
- language and keyboard layout;
- exact left, typed, and right text;
- expected action: `keep` or `replace`;
- one or more acceptable texts;
- candidate signals;
- optional descriptive tags.

Example:

```json
{
  "id": "replace-warscheinlich",
  "languageTag": "de-DE",
  "layout": "qwertz",
  "leftContext": "Das ist",
  "typedText": " warscheinlich",
  "rightContext": " richtig.",
  "expectation": {
    "action": "replace",
    "acceptableTexts": [" wahrscheinlich"]
  },
  "candidates": []
}
```

Text includes its exact boundary separators. NFC normalization is applied while comparing expected and produced text.

## Candidate signals

Every candidate may contain:

- `generatorConfidence` — normalized candidate-generator confidence in `[0, 1]`;
- `editRisk` — normalized risk or distance in `[0, 1]`;
- normalized complete-candidate `rankerScore`;
- optional touch confidence;
- optional frequency confidence;
- optional personalization confidence;
- exact-dictionary-match flag;
- typed-text flag;
- offensive and blocked flags.

Exactly one candidate in every benchmark case represents the typed text.

The benchmark data format can omit `rankerScore`. A runner may load a model, score all candidates, and inject the scores before fusion.

## Experimental fusion

The first fusion implementation is an explicit linear evidence model followed by a sigmoid.

Probability-like signals are centered from `[0, 1]` to `[-1, 1]` before weighting. Missing optional signals contribute zero evidence rather than an artificial negative value.

A raw ranker score is calibrated through:

```text
ranker_probability = sigmoid((ranker_score - center) / scale)
```

The initial linear score is:

```text
score = generator evidence
      + ranker evidence
      + touch evidence
      + frequency evidence
      + personalization evidence
      - edit-risk penalty
      + exact-dictionary bonus
      + typed-text bias
```

Every contribution is preserved in `CandidateFusionEvidence` for diagnostics and parameter tuning.

The current defaults are starting values only. They are not claimed to be production-optimal.

## Model calibration

Different models and quantizations may produce different raw log-probability distributions.

Every model profile therefore needs calibration values:

- ranker center;
- positive ranker scale.

Model Studio will derive these values from a calibration split rather than hard-coding language-specific assumptions into Android.

Calibration, policy tuning, and final evaluation must use separate data splits.

## Automatic-correction policy

Ranking candidates does not automatically authorize replacement.

A correction must pass independent checks:

- candidate is not blocked;
- candidate is not marked potentially offensive;
- candidate confidence exceeds a minimum;
- edit risk stays below a maximum;
- margin over the typed candidate exceeds a minimum;
- margin over the runner-up exceeds a minimum;
- optional generator/ranker agreement.

Possible decisions include:

- correct;
- keep typed top;
- no correction candidate;
- blocked or offensive candidate;
- insufficient confidence;
- insufficient typed or runner-up margin;
- excessive edit risk;
- generator/ranker disagreement.

This makes false-correction behavior observable and tunable independently from ranking.

## Required metrics

The evaluator reports:

### Candidate coverage

Fraction of cases where at least one acceptable text exists in the candidate list.

A ranker cannot recover a candidate that the generator never produced.

### Top-1 and Top-3 accuracy

Fraction of cases where an acceptable text is ranked in the first or first three positions.

For `keep` cases, the typed text is the expected text.

### Mean reciprocal rank

Average reciprocal position of the first acceptable candidate. Missing candidates contribute zero.

### False-correction rate

Fraction of `keep` cases where any automatic replacement is made.

This is one of the most important safety metrics.

### Correct autocorrect rate

Fraction of `replace` cases where an acceptable replacement is automatically selected.

### Wrong autocorrect rate

Fraction of all cases producing a false or incorrect automatic replacement.

### Missed-correction rate

Fraction of `replace` cases where no automatic replacement is made.

### Latency

Nearest-rank p50 and p95 elapsed microseconds across cases.

Later run metadata will additionally include memory and energy measurements.

## Dataset composition

A representative language suite should not consist mainly of easy synthetic typos.

Recommended groups include:

- common keyboard-neighbor errors;
- missing, repeated, and transposed letters;
- valid-word confusables;
- capitalization;
- compounds and inflections;
- names and technical vocabulary;
- punctuation boundaries;
- sentence-start and sentence-end cases;
- short high-risk words;
- right-context-dependent cases;
- deliberately correct rare words;
- personal-dictionary scenarios;
- layouts other than the language default.

Every major confusable should appear as both a `keep` and a `replace` case where linguistically possible.

## Data provenance and privacy

A public benchmark case must contain only data that can legally and safely be redistributed.

Each suite declares a license. Dataset tooling must preserve source attribution and exclusion records.

Private user text must not be included in public benchmark packages. Real typing data should be collected through explicit transcription tasks using public prompts rather than by uploading private messages.

## Splits

A complete benchmark project should maintain:

- training data;
- calibration split;
- policy-tuning split;
- final hidden or frozen evaluation split.

Near-duplicate sentences and typo variants must remain in the same split to prevent leakage.

Model Studio should display hashes for every split and store them in the exported model card.

## Example suite

A small illustrative German suite is available at:

```text
docs/language-packages/examples/german-correction-benchmark.json
```

It exists to test tooling and contracts. Its size is far too small to support quality claims.

## Strict parsing

`CorrectionBenchmarkCodec` rejects unknown fields except fields prefixed with `x-`.

The semantic validator additionally checks:

- format version;
- unique case and candidate IDs;
- exactly one typed candidate per case;
- typed candidate text matching `typedText`;
- non-empty acceptable texts;
- `keep` cases accepting the typed text;
- finite and bounded probability signals;
- finite ranker scores.

## Current implementation boundary

Implemented:

- benchmark data classes and strict codec;
- semantic validation;
- experimental transparent score fusion;
- independent autocorrect decision policy;
- case evaluation and aggregate metrics;
- unit/instrumentation coverage for protection scenarios and metrics.

Not yet implemented:

- loading a suite from the debug UI;
- running a real ranker over an entire suite;
- automatic calibration and weight search;
- candidate extraction from the current AOSP suggestion pipeline;
- benchmark result serialization and model cards;
- memory and energy measurement;
- production score fusion.
