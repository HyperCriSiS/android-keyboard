# Candidate ranker runtime contract

**Task:** `candidate-ranking-v1`  
**Required capability:** `full-candidate-logprob`  
**Status:** Draft implementation contract with experimental Android runtime

This contract defines the boundary between FUTO Keyboard candidate generation and a context-ranking model.

The first runtime implementation uses an unchanged causal GGUF model through the declarative `gguf-causal-ranker` adapter. Final score fusion with dictionary, touch, personalization, and correction-risk signals remains a separate layer.

## Purpose

A candidate ranker receives:

- exact left context;
- the originally typed text;
- optional exact right context;
- a bounded list of complete replacement candidates.

It returns one full-sequence language-model score for every candidate.

The ranker must not score only the first token of a multi-token candidate.

## Separation of responsibilities

The candidate generator is responsible for candidate recall:

- edit distance;
- keyboard geometry;
- touch coordinates;
- word frequency;
- personal dictionary entries;
- language-specific correction rules.

The context ranker is responsible for contextual likelihood:

```text
P(candidate tokens | left context)
```

When supported, it may additionally evaluate a bounded continuation from the right context:

```text
P(right-context tokens | left context, candidate tokens)
```

The final keyboard scorer will combine these values with generator, touch, personalization, and correction-risk signals.

## Exact text contract

The request contains separate fields:

- `leftContext`;
- `typedText`;
- candidate `replacementText`;
- `rightContext`.

`replacementText` is exact text, including any separator required at its boundary. The runtime does not infer spaces or punctuation.

Example:

```text
leftContext:    "Das ist"
typedText:      " warscheinlich"
replacement:    " wahrscheinlich"
rightContext:   " richtig."
```

This avoids imposing English word-boundary assumptions on all languages.

## Boundary modes

A descriptor declares the boundary convention used during training and validation:

### `ExactText`

No general separator convention is declared. The supplied replacement text is used exactly.

### `LeadingSeparator`

Normal word candidates are expected to contain a leading separator. This is common for tokenizers that attach spaces to the following token.

### `TrailingSeparator`

Normal word candidates are expected to contain a trailing separator. This matches FUTO's earlier suffix-whitespace tokenizer strategy.

Boundary violations currently produce validation warnings because punctuation, sentence-start, and script-specific candidates may legitimately differ.

## Request purposes

A request declares one of:

- `Correction` — replace mistyped text;
- `Completion` — complete a partially typed token;
- `NextWord` — insert a following token or phrase.

The score structure supports all purposes. A concrete component must still declare every task it actually implements. The first generic GGUF adapter is registered for `candidate-ranking-v1` only.

## Score output

Every candidate produces:

- candidate ID;
- sum of candidate-token log probabilities;
- number of candidate tokens;
- optional sum of right-context log probabilities;
- number of scored right-context tokens.

Raw sums are retained so the caller can apply a documented normalization policy without discarding information.

A runtime must return exactly one finite score for every requested candidate ID and no unknown IDs.

## Normalization

The current shared policy computes:

```text
candidate_score = candidate_logprob / candidate_token_count ^ length_exponent
```

When right context is available:

```text
continuation_score = right_logprob / right_token_count ^ length_exponent
combined_score = candidate_score + right_weight * continuation_score
```

Defaults:

```text
length_exponent = 0.7
right_weight = 0.25
```

Both values are configurable contract inputs. They are not embedded in the model runtime.

This design prevents long German compounds from being penalized purely because they use more model tokens while still retaining a controlled length preference.

## Runtime binding

A component selects its adapter outside the model payload:

```json
{
  "runtime": {
    "id": "gguf-causal-ranker",
    "apiVersion": 1,
    "parameters": {
      "boundaryMode": "leading-separator",
      "maxContextTokens": 256,
      "maxBatchSize": 16,
      "supportsRightContext": true,
      "bosPolicy": "model-default",
      "addEos": false,
      "contextTruncation": "keep-last"
    }
  }
}
```

This permits the Model Studio to wrap an existing standard GGUF without rewriting its metadata or weights.

Runtime providers are registered by runtime ID and non-overlapping API-version ranges. Probe and open results are validated against the exact installed component coordinate.

## Generic GGUF adapter

Runtime ID:

```text
gguf-causal-ranker
```

API version:

```text
1
```

Supported configuration:

- exact, leading-separator, or trailing-separator candidate boundaries;
- 16 to 32768 declared context tokens, limited further by the native model context;
- 1 to 64 candidates per native call;
- optional right-context scoring;
- model-default, forced, or disabled BOS handling;
- optional EOS scoring when right-context scoring is disabled;
- keep-last or reject behavior for overlong left context.

Unknown configuration fields are rejected.

## Tokenizers

The native loader supports two tokenizer paths:

### Standard GGUF tokenizer

When no `keyboardlm.ext_tokenizer_type` is declared, tokenization, detokenization, BOS, and EOS behavior come from the tokenizer embedded in the GGUF through llama.cpp.

This is the normal path for existing community models.

### External SentencePiece tokenizer

Existing KeyboardLM models may continue to declare and embed their external SentencePiece tokenizer. The legacy vocabulary alignment and suffix-whitespace behavior remain supported.

External tokenizer bytes are required only when this mode is explicitly selected.

## Complete-sequence native scoring

The experimental native scorer:

1. tokenizes and evaluates the left context once;
2. retains its logits and KV-cache prefix;
3. copies that prefix to an isolated candidate sequence;
4. evaluates every candidate token;
5. calculates a stable selected-token log-softmax over the complete vocabulary;
6. optionally evaluates a bounded right-context continuation;
7. removes the candidate sequence before scoring the next candidate;
8. clears request-specific KV state after completion or failure.

This fixes the previous prototype behavior that considered only the first token of each candidate.

## Prefix reuse and batching

The descriptor declares `maxBatchSize`. Requests may contain more candidates than one native call, up to the global contract limit. The Kotlin batch planner partitions candidates while preserving their original order.

The native scorer reuses the left-context KV prefix for every candidate. Candidate branches are isolated with llama.cpp sequence IDs.

Diagnostics report:

- elapsed microseconds;
- evaluated candidate tokens;
- evaluated right-context tokens;
- reused prefix tokens;
- native batch count.

These values will be used by the benchmark suite and device performance profiles.

## Cancellation and concurrency

`rank` is a suspending operation. Requests are checked for coroutine cancellation before every native batch and before publishing a result.

A native call itself is currently synchronous and cannot yet be interrupted mid-decode. Stale work stops at the next batch boundary.

Each loaded runtime serializes native calls and closes idempotently. A closed runtime returns a structured component-unavailable failure.

## Failures

Expected runtime failures are returned as structured data:

- invalid request;
- component unavailable;
- unsupported language;
- unsupported task or capability;
- model probe or load failure;
- context too long;
- out of memory;
- general runtime failure.

Each failure declares whether retrying may succeed.

Programming errors and coroutine cancellation are not normal failure outcomes.

## Probe and open lifecycle

A runtime provider has two stages:

### Probe

Load and validate enough model state to prove the configured tokenizer and runtime can open, then release it immediately.

Probe returns either:

- a ready descriptor plus warnings;
- an unavailable result with structured issues.

### Open

Allocate the actual runtime and return a loaded `CandidateRankerRuntime`.

A loaded runtime is `Closeable` and processes sequential requests.

## Component requirements

A component opened through this contract must:

- have kind `context-ranker`;
- use exclusive activation;
- declare task `candidate-ranking-v1`;
- require capability `full-candidate-logprob`;
- have a present payload with the declared byte size;
- declare a supported runtime binding.

The generic GGUF probe currently verifies model loading and tokenizer availability. Deeper architecture, vocabulary, memory, and quality checks belong to later probe and benchmark stages.

## Validation limits

The shared validator enforces:

- 1 to 64 candidates per request;
- unique candidate IDs;
- bounded request and candidate text sizes in UTF-8 bytes;
- right-context token limit from 0 to 64;
- positive descriptor context and batch sizes;
- exact request/result ID matching;
- finite log probabilities;
- positive candidate token counts;
- non-negative diagnostics;
- complete candidate score coverage.

The Kotlin runtime validates native output again before returning success.

## Debug verification

A debug build exposes:

```text
Developer → Language package registry
```

For every installed ranker, the screen can:

- show manifest/runtime compatibility issues;
- probe model and tokenizer loading;
- temporarily open the model;
- score `wahrscheinlich`, `anscheinend`, and `wirklich` in a German sample sentence;
- display normalized scores, token counts, latency, and native batch count.

The operation never activates the component.

## Not defined yet

The following remain separate milestones:

- production score fusion with dictionary, touch, and personalization signals;
- confidence and automatic-correction thresholds;
- benchmark dataset and result formats;
- runtime pooling and memory-pressure behavior;
- asynchronous native cancellation;
- architecture-specific performance tuning;
- automatic tokenizer-boundary detection;
- production activation and persisted per-language selection.
