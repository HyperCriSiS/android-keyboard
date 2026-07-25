# Experimental Personalization Migration

**Status:** Implemented on `dev`, Developer-only  
**Production effect:** None

This document describes migration from the current Android and binary personalization sources into
the transactional portable personalization architecture.

Two stages now exist:

1. Android personal-dictionary words can be previewed and applied to the experimental source store.
2. Automatic `UserHistoryDictionary` data can be converted into a transparent portable preview, but
   is not yet applied or activated.

Neither stage changes production suggestions, production learning callbacks, or legacy dictionary
files.

## Manual Android personal dictionary

### Source

The migration reads Android's `UserDictionary.Words` provider through
`AndroidPersonalDictionaryInventoryReader`.

The source fields are:

- word;
- locale;
- shortcut;
- frequency;
- legacy application ID.

The legacy application ID is deliberately omitted because it is not portable and may reveal an
application-specific scope. Frequencies outside the portable `0..255` range are clamped and
reported as migration notices.

### Stable identity

Every source word receives a deterministic UUID derived from normalized:

- record kind;
- locale;
- word;
- shortcut.

The same Android personal word therefore receives the same record ID on repeated scans. Migration
time is not part of the identity.

### Planning rules

Migration is previewed before any generation is written.

For every incoming word, the planner chooses exactly one decision:

| Decision | Meaning |
|---|---|
| `Add` | No matching record or tombstone exists; add it to the preview. |
| `AlreadyPresent` | The same stable ID and user-visible content already exist. |
| `SuppressedByTombstone` | The user previously deleted this stable ID; do not resurrect it. |
| `Conflict` | Existing state disagrees and must not be overwritten automatically. |

The planner blocks application when:

- the Android source inventory is truncated;
- a tombstone with the same ID targets another record kind;
- the same stable ID has different user-visible content;
- a logically equivalent word exists under another record ID;
- the resulting dataset fails semantic validation.

A local frequency or shortcut edit therefore wins by producing a visible conflict instead of being
silently replaced by a later migration scan.

### Idempotency

Repeated migration is idempotent.

Timestamps, revision, provenance, and origin-device metadata are not compared when deciding whether
an equivalent stable record is already present. A second scan at a later time therefore reports the
word as `AlreadyPresent` and creates no new generation.

### Apply protocol

When the experimental store does not exist:

1. read and validate the complete Android source inventory;
2. build the migration preview;
3. initialize generation 1 with commit reason `migration`;
4. read the generation back;
5. compile an immutable runtime snapshot;
6. activate that snapshot only inside the experimental controller instance.

When the store already exists:

1. build the preview against the current generation;
2. commit with that generation ID as the optimistic concurrency token;
3. reject the operation if another writer committed first;
4. compile and atomically activate the new experimental runtime snapshot.

No production dictionary, user-history file, suggestion source, or learning callback is modified.

### Developer screen

The flow is available through:

```text
Developer
→ Personalization export preview
→ Open experimental store
```

The screen displays:

- Android source count and truncation state;
- additions, already-present records, tombstone-protected records, and conflicts;
- migration notices;
- current experimental generation and SHA-256;
- recovery issues;
- recent immutable generation history;
- up to 250 planned record decisions.

The apply button writes only to `filesDir/personalization-source`.

### Manual migration tests

Instrumentation tests cover:

- first migration into a new store;
- repeated idempotent migration;
- tombstone protection against resurrection;
- changed local records producing conflicts;
- truncated source rejection;
- logical duplicates under different IDs requiring review.

## Automatic user-history mapping

The read-only inventory adapter now feeds a versioned conservative converter:

```text
UserHistoryDictionary binary file
        ↓ bounded snapshot on KEYBOARD executor
LegacyUserHistoryInventory
        ↓ low-priority mapping worker
portable learned words and n-grams
        ↓ validation and archive self-inspection
Developer-only in-memory preview
```

Mapping policy `0.1` preserves only defensible semantics:

- retained native count becomes portable `observationCount` when valid;
- native timestamp seconds become `lastSeenAt` when plausible;
- unrecoverable `firstSeenAt` is explicitly approximated as `lastSeenAt`;
- current probability is normalized from `0..255` to confidence `0.0..1.0`;
- implementation-specific native level is not exported;
- invalid evidence is replaced conservatively and reported;
- complete sentences and application scopes are never introduced.

The complete normative explanation is in:

```text
docs/personalization/USER_HISTORY_MAPPING.md
```

`LegacyPersonalizationExportPreview.prepareUserHistory(...)` creates a normal self-validated
`.futopersonal` archive in memory. The preview contains `learned-words` and `learned-ngrams` and
carries migration diagnostics outside the portable payload.

This stage deliberately does not yet write automatic-history records into the transactional source
store. A valid mapping is necessary but not sufficient for production parity.

### Automatic-history tests

Instrumentation tests cover:

- retained count, timestamp, and probability mapping;
- missing historical-information fallback;
- invalid and future timestamp handling;
- probability clamping;
- suppression of legacy `isNotAWord` entries;
- invalid n-gram rejection;
- deterministic duplicate collapse;
- truncated-source completeness;
- self-validated portable archive creation.

## Load measurements

`PersonalizationLoadMeasurementTest` records diagnostic measurements for 1,000, 10,000, and 25,000
learned words, with a stored bigram for every fifth word. It measures:

- inventory construction;
- portable mapping;
- semantic validation;
- canonical JSON encoding;
- immutable runtime compilation;
- encoded byte size;
- heap observations at each stage.

The generated `personalization-load-report.json` is pulled from the debug application and retained as
a per-API GitHub Actions artifact.

Timing values are measurements, not hard pass/fail thresholds. Correctness, rejection counts,
validation, and the source store's 64 MiB data limit are hard assertions. Performance thresholds
must be derived from repeated emulator and real-device results rather than invented in advance.

## Android-version coverage

The personalization device workflow runs the suite on:

- API 24, the current minimum supported Android version;
- API 29, a representative middle release;
- API 35, the current target and compile level.

Each API produces separate diagnostics, instrumentation reports, and load measurements.

## Remaining production gates

Before automatic history can influence production suggestions:

1. add transactional planning and tombstone-safe application for learned words and n-grams;
2. compare migrated evidence against legacy candidates and ranking in shadow mode;
3. establish reviewed parity thresholds by locale and evidence strength;
4. measure snapshot occupancy on the keyboard executor using real histories;
5. measure shadow queue-drop rates under sustained rapid typing;
6. repeat memory and latency measurements on representative physical devices;
7. test rollback after corrupt generations and failed runtime activation;
8. complete privacy review for user-selected portable export;
9. retain a feature flag that restores the legacy path immediately.

A failure at any stage leaves the existing learning system authoritative.
