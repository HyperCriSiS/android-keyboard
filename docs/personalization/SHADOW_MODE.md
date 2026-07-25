# Personalization Shadow Mode

**Status:** Experimental, debug-only implementation on `dev`

Shadow mode compares the current production dictionary output with one immutable runtime snapshot compiled from the experimental personalization source store. It never changes the candidates returned to the keyboard, autocorrection thresholds, dictionary writes, or user-history files.

## Purpose

The migration and runtime formats are not considered production-safe merely because they compile. Shadow mode provides evidence for whether the new representation preserves useful personal behavior and whether explicit rules would have prevented known unwanted decisions.

The initial implementation answers questions such as:

- Does a migrated manual or pinned word appear in the production candidate set?
- Does portable learned evidence correspond to a visible production user-history candidate?
- Is a candidate visible even though an explicit `block-suggestion` rule would hide it?
- Is a `block-autocorrect` candidate ranked first and therefore potentially dangerous?
- Is a preferred correction absent from the production candidate set?
- Which committed personalization generation and data hash produced the comparison?

## Observation point

The hook runs immediately after `DictionaryFacilitator.getSuggestionResults(...)` for non-batch typing and before normal `Suggest` transformation and autocorrection logic.

```text
production dictionaries
        ↓
SuggestionResults
        ├── unchanged normal FUTO path
        └── bounded copy → shadow worker
```

The exact same `SuggestionResults` instance continues through the existing production path. Shadow mode cannot reorder, add, delete, or mutate a candidate.

Swipe input is deliberately excluded. Swipe has a separate model, rejection flow, and candidate semantics and must be evaluated through its existing benchmark path.

## Activation

Shadow mode is off after every process start. It can be enabled only from the debug Developer UI:

```text
Developer
→ Personalization export preview
→ Open shadow mode
```

Enabling it:

1. reads the newest valid experimental store generation;
2. validates and compiles an immutable runtime snapshot on the background worker;
3. starts accepting bounded non-swipe observations;
4. keeps all observations in process memory only.

Disabling it immediately stops new observations. Existing in-memory events remain visible until cleared or the process exits.

## Privacy boundary

Shadow mode does not store:

- typed words;
- candidate words;
- previous words or sentence context;
- application package names;
- touch coordinates;
- clipboard or contact data;
- persistent device or user identifiers.

Words are normalized and converted to SHA-256 fingerprints using a random 256-bit salt generated for the current process. Only the first 64 bits of the digest are displayed. The salt is not persisted, so fingerprints cannot be correlated across process restarts.

Each event may contain:

- locale;
- process-local word fingerprints and code-point lengths;
- candidate rank, score source type, and bounded counts;
- runtime generation ID and data SHA-256;
- explicit rule actions and finding categories;
- evaluation latency;
- suggestion input style and session number.

The background work item temporarily contains the current typed word and at most sixteen candidate words until evaluation completes. It is never written to disk or retained in the event ring buffer.

## Performance boundary

The typing thread performs only:

1. debug/enabled checks;
2. a copy of at most sixteen candidates;
3. submission to a bounded queue.

The worker uses:

- one daemon thread at minimum priority;
- a queue capacity of 64 observations;
- a ring buffer of 256 completed events;
- drop accounting instead of blocking when the queue is full.

Store reads, JSON decoding, runtime compilation, normalization, rule matching, hashing, and statistics run outside the typing thread.

## Findings

### `ManualPrefixCandidateMissing`

At least one manual word matching the current typed prefix exists in the experimental snapshot but is absent from the bounded production candidates.

### `PinnedTypedWordMissing`

The exact typed word has an effective `pin` rule but is absent from production dictionary candidates.

### `BlockedSuggestionVisible`

A production candidate has an effective `block-suggestion` rule. This candidate should not be visible once the new runtime is integrated.

### `BlockedAutocorrectCandidateRankedFirst`

The first production dictionary candidate has an effective `block-autocorrect` rule. The candidate may still be displayed, but the future autocorrection decision must reject it.

### `PreferredCorrectionMissing`

An effective global or locale-specific preferred correction for the typed word is absent from production candidates.

### `LearnedTypedWordMissing`

The experimental runtime contains learned evidence for the exact typed word, but production candidates do not expose an exact match from `TYPE_USER_HISTORY`.

A finding is diagnostic evidence, not automatically proof of a production bug. Prefix length, candidate truncation, later filtering, casing transformations, and legacy probability semantics must be considered during review.

## Current limitations

- Only manual Android personal words have a validated migration into the experimental store.
- Legacy automatic-history evidence is inspectable but is not yet converted into portable counts or confidence.
- Shadow mode compares candidate presence and explicit rules; it does not yet reproduce the legacy binary dictionary score.
- Events are in-memory diagnostics, not a stable export format.
- Instrumentation tests are compiled by CI but still require execution on a device or emulator.
- Process-local enablement is intentional; there is no release setting or persistent opt-in.

## Acceptance gates before production use

The new personalization runtime must not affect normal typing until all of the following are available:

1. instrumentation tests executed on supported Android API levels;
2. sustained typing tests showing no user-visible latency regression;
3. measured queue-drop rate under rapid typing;
4. reviewed samples for every finding category;
5. validated mapping for legacy user-history evidence;
6. parity thresholds for migrated manual words and learned entries;
7. rollback tests after simulated corrupt generations and activation failures;
8. a versioned, privacy-reviewed diagnostic report format;
9. an explicit feature flag that can restore the legacy path immediately.

The first production integration should remain dual-path: the legacy dictionaries stay authoritative while the new runtime only suppresses or prefers candidates after the relevant rule behavior has passed these gates.
