# Legacy User-History Mapping

**Status:** Implemented on `dev`, Developer-only  
**Mapping version:** `0.1`  
**Production effect:** None

This document defines the conservative conversion of the existing per-locale
`UserHistoryDictionary` binary dictionaries into portable `LearnedWordRecord` and
`LearnedNgramRecord` data.

The legacy dictionary remains authoritative. Mapping creates only an inspectable in-memory data set
or a self-validated preview archive. It does not replace production suggestions, change learning
callbacks, write the experimental source store, or modify legacy dictionary files.

## Why a separate mapping version exists

The binary dictionaries expose:

- a current effective probability;
- an optional native timestamp;
- an optional native level;
- an optional retained count;
- word flags;
- stored n-gram context.

These values are implementation details rather than a complete event history. In particular, the
first observation time and an exact lifetime observation count cannot be reconstructed after native
retention, decay, garbage collection, or counter compaction.

The mapping policy is therefore versioned independently from portable format `0.1`. A later mapping
may improve conversion without changing the archive schema.

## Mapping policy `0.1`

| Legacy value | Portable value | Rule |
|---|---|---|
| locale | `locale` | Canonicalized as a BCP-47 language tag. |
| word | `word` | Preserved as stored; stable identity remains normalized and deterministic. |
| retained count | `observationCount` | Used when historical information exists and the count is positive. Otherwise `1` is used and reported as an approximation. |
| native timestamp | `lastSeenAt` | Interpreted as Unix epoch seconds only when non-negative and not later than migration time; then converted to milliseconds. Invalid or future values use migration time and are reported. |
| no recoverable first timestamp | `firstSeenAt` | Set equal to `lastSeenAt`. Every such conversion emits `first_seen_approximated`. |
| current probability | `confidence` | Clamped to `0..255` and divided by `255.0`. This preserves ordering but does not claim to reproduce the legacy score formula. |
| `isNotAWord` | `state` | Maps to `suppressed`; other entries map to `active`. |
| native level | none | Ignored and reported when non-zero because its semantics are implementation-specific. |
| possibly-offensive flag | none | Not representable in portable format `0.1`; omission is reported. |

Every converted record uses:

- deterministic legacy stable ID;
- revision `1`;
- `createdAt` and `updatedAt` equal to migration time;
- source `migrated-user-history`;
- optional random application-generated origin device ID.

`createdAt` describes creation of the portable record. It is not presented as the first time the
user typed the word.

## N-grams

Each stored legacy n-gram becomes one portable term sequence:

```text
previous context terms + target word
```

Only sequences containing two to four non-blank terms are accepted. Full sentences, arbitrary
surrounding text, application package names, and touch data are never added.

An invalid n-gram is rejected and prevents the conversion from claiming to be complete. The
associated valid unigram may still appear in the preview.

## Bounded diagnostics

Large histories must not allocate one warning object for every entry. Diagnostics are grouped by
code and contain:

- total occurrence count;
- at most eight stable record-ID samples;
- one fixed explanation.

Current diagnostic codes include:

| Code | Meaning |
|---|---|
| `first_seen_approximated` | The true first observation cannot be recovered. |
| `missing_historical_evidence` | No historical fields were available; one import observation was used. |
| `invalid_legacy_count` | Retained count was missing or non-positive. |
| `invalid_legacy_timestamp` | Timestamp was invalid or later than migration time. |
| `invalid_legacy_probability` | Probability was outside `0..255` and was clamped. |
| `legacy_level_ignored` | A non-zero implementation-specific level was deliberately omitted. |
| `offensive_flag_not_portable` | Format `0.1` has no equivalent field. |
| `invalid_ngram_rejected` | Sequence was blank or outside the portable two-to-four-term bound. |
| `duplicate_*_collapsed` | Equivalent records sharing one stable ID were collapsed. |
| `conflicting_*_stable_id` | One stable ID resolved to different content and requires review. |

## Completeness

A migration result is complete only when all conditions hold:

1. the snapshot reader reported a complete source;
2. the configured entry limit did not truncate the snapshot;
3. no word was rejected;
4. no n-gram was rejected;
5. the generated portable data passes semantic validation.

Approximations do not by themselves make a result incomplete because they are unavoidable and
explicitly declared. They do prevent the data from being described as an exact historical event log.

## Portable preview

`LegacyPersonalizationExportPreview.prepareUserHistory(...)` creates a normal `.futopersonal`
archive in memory with categories:

- `learned-words`;
- `learned-ngrams`.

The archive is immediately read back through `PersonalizationArchiveInspector`. A generated preview
that fails its own archive validation is rejected as an implementation error.

The preview remains developer-only and unencrypted. It must not be presented as a normal user backup
until user-selected export, encryption warnings, migration review, and rollback behavior are wired
through the production UI.

## What mapping `0.1` does not claim

It does not claim that:

- retained count equals all lifetime typing events;
- `firstSeenAt` is historically exact;
- normalized confidence reproduces native ranking;
- migrated entries are safe to activate merely because they validate;
- one snapshot proves behavior across Android versions or native dictionary variants.

## Acceptance work still required

Before automatic-history migration can affect production suggestions:

1. compare migrated word and n-gram presence against legacy candidates in shadow mode;
2. establish reviewed parity thresholds by locale and evidence strength;
3. measure snapshot duration and keyboard-executor occupancy at realistic and maximum sizes;
4. measure mapping time, peak heap usage, allocation pressure, and archive size;
5. measure shadow queue drops under sustained rapid typing;
6. run instrumentation on the selected minimum, middle, and current Android API levels;
7. test rollback after corrupt generations and failed runtime activation;
8. retain an immediate feature flag restoring the legacy authoritative path.

Until those gates pass, the result is a transparent migration candidate, not a production learning
replacement.
