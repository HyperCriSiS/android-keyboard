# Candidate ranker runtime contract

**Task:** `candidate-ranking-v1`  
**Required capability:** `full-candidate-logprob`  
**Status:** Draft implementation contract

This contract defines the boundary between FUTO Keyboard candidate generation and a context-ranking model.

It deliberately does not define GGUF metadata, a tokenizer implementation, or the final score mixture with dictionary and touch signals. Those layers build on this contract.

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

The final keyboard scorer will later combine these values with generator, touch, personalization, and correction-risk signals.

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

Normal word candidates are expected to contain a trailing separator. This matches FUTOs earlier suffix-whitespace tokenizer strategy.

Boundary violations currently produce validation warnings because punctuation, sentence-start, and script-specific candidates may legitimately differ.

## Request purposes

A request declares one of:

- `Correction` — replace mistyped text;
- `Completion` — complete a partially typed token;
- `NextWord` — insert a following token or phrase.

The same score structure is used for all purposes. A model may later declare task-specific support in its metadata.

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

## Prefix reuse and batching

A runtime should evaluate the left context once and branch candidate sequences from the resulting state.

The descriptor declares `maxBatchSize`. Requests may contain more candidates than one native batch, up to the global contract limit. The shared batch planner partitions candidates while preserving their original order.

Diagnostics report:

- elapsed microseconds;
- evaluated candidate tokens;
- evaluated right-context tokens;
- reused prefix tokens;
- batch count.

These values will be used by the benchmark suite and device performance profiles.

## Cancellation

`rank` is a suspending operation.

Coroutine cancellation must propagate as cancellation. It must not be converted to a normal runtime failure. This allows a stale request to stop immediately when the user types another character.

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

Read model metadata and perform lightweight validation without keeping the model loaded.

Probe returns either:

- a ready descriptor plus warnings;
- an unavailable result with structured issues.

### Open

Allocate the actual runtime and return a loaded `CandidateRankerRuntime`.

A loaded runtime is `Closeable` and processes sequential requests. Implementations may serialize concurrent calls internally.

## Component requirements

A component opened through this contract must:

- have kind `context-ranker`;
- use exclusive activation;
- declare task `candidate-ranking-v1`;
- require capability `full-candidate-logprob`;
- have a present payload with the declared byte size.

GGUF-specific metadata checks will be added in the runtime probe implementation.

## Validation limits

The initial shared validator enforces:

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

These limits protect both Android and future desktop tooling from malformed test inputs and unbounded allocations.

## Not defined yet

The following remain separate milestones:

- tokenizer boundary implementation for generic GGUF models;
- prefix KV-cache branching in llama.cpp;
- right-context scoring strategy per tokenizer family;
- model metadata keys and probe implementation;
- dictionary/touch/model score fusion;
- confidence and automatic-correction thresholds;
- benchmark dataset format;
- runtime pooling and memory-pressure behavior.
