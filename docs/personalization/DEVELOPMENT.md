# Personalization Development Status

This document tracks the implementation boundary for inspectable and editable personal language data.

## Implemented on `dev`

- portable `.futopersonal` architecture specification;
- manifest and data JSON Schemas for format `0.1`;
- Kotlin manifest and record model;
- strict JSON codecs with `x-` extension support and required default-field encoding;
- semantic validation for:
  - UUIDs and revisions;
  - timestamps and evidence ranges;
  - NFC-normalized text;
  - locales and application scopes;
  - unique record IDs;
  - duplicate logical records;
  - conflicting word and correction rules;
  - tombstone/live-record conflicts;
  - manifest counts, locale inventory, privacy flags, hashes, and sizes;
- conservative merge planning;
- deterministic revision handling for records and tombstones;
- explicit conflicts for different IDs describing the same logical item;
- pure edit planning for forget, never-learn, pin, word-rule, and correction-pair actions;
- deterministic legacy IDs derived from normalized content;
- read-only inventory of Android's system personal dictionary;
- conversion of system personal words to portable manual-word records;
- explicit reporting when legacy app IDs are omitted or frequencies are clamped;
- bounded and cancellable binary-dictionary snapshots;
- a read-only per-locale user-history inventory service;
- a mapper from legacy `WordProperty` objects to transparent user-history inventory;
- read-only Developer inventory and in-memory export-preview screens;
- strict `.futopersonal` archive creation and inspection;
- an immutable generation-based editable source store with:
  - staging and atomic generation commits;
  - operating-system and in-process locking;
  - optimistic generation conflict detection;
  - structured commit journals;
  - rollback as a new generation;
  - fallback from a corrupt newest generation;
  - refusal to overwrite an all-corrupt store;
  - configurable data, metadata, and journal limits;
- a deeply immutable, pre-indexed runtime snapshot;
- atomic store-to-runtime activation through `PersonalizationSourceController`;
- retention of the previous runtime when compilation of a committed generation fails;
- conservative and idempotent migration of Android personal-dictionary words into the separate
  experimental store;
- tombstone protection and conflict reporting during repeated migration;
- a Developer store screen showing migration preview, recovery issues, and immutable generations;
- a debug-only personalization shadow mode with:
  - observation of non-swipe production dictionary candidates;
  - no candidate mutation or production decision changes;
  - a bounded single-thread background worker and queue-drop accounting;
  - a bounded in-memory event ring;
  - per-process salted word fingerprints instead of retained text;
  - manual, learned-word, pin, preference, and block-rule findings;
  - a Developer status and event console;
- instrumentation tests for codecs, validation, archives, merge behavior, edit behavior, snapshots,
  store transactions, recovery, rollback, runtime indexing, activation, migration, shadow evaluation,
  deterministic IDs, and legacy mapping;
- a draft pull request used as the long-running CI and review channel;
- successful CI compilation of `unstableDebug`, the Android test APK, Kotlin, Java, JNI, and NDK;
- successful portable-tool tests on Windows and Ubuntu with Python 3.11 and 3.13.

The main implementation contracts are documented in:

```text
docs/personalization/README.md
docs/personalization/STORE.md
docs/personalization/MIGRATION.md
docs/personalization/SHADOW_MODE.md
```

## Existing storage sources

### Android personal dictionary

Manual words are stored through Android's `UserDictionary` provider. The current FUTO settings UI can already add, edit, remove, import, and list these records.

The inventory reader migrates them without direct access to private dictionary files. Original creation and modification timestamps are unavailable, so the migration time is recorded and the source is marked `migrated-android-user-dictionary`.

The legacy `appId` field is not included in portable data because app-specific scope is privacy-sensitive and the numeric value is not portable across devices.

The migration writes only to the experimental source store. Android's provider remains authoritative for production typing.

### User-history dictionaries

Automatic learning is stored in per-locale `UserHistoryDictionary` binary dictionaries.

The binary API already supports:

- full word traversal;
- word probability;
- historical raw fields;
- attached n-gram properties;
- dynamic unigram removal;
- complete dictionary clearing.

However, `ProbabilityInfo` explicitly documents that legacy timestamp, level, and count fields are native implementation details. The first inventory therefore exposes them only as raw diagnostic values. They must not be labeled as exact dates, use counts, or portable confidence values.

## Safe history snapshot boundary

Dictionary mutations are queued on the single-threaded `KEYBOARD` executor. The bounded snapshot reader queues its traversal on the same executor, after any reload requested by the reader. Previously queued mutations therefore complete before traversal and later mutations cannot begin until traversal returns.

The reader:

1. requests a reload when required;
2. enters the same single-threaded mutation queue;
3. traverses with token `0` until the next token returns to `0`;
4. detects repeated traversal tokens and invalid properties;
5. enforces a maximum record count and cancellation checks;
6. returns detached `WordProperty` values without exposing the native handle;
7. transfers mapping and sorting to a separate low-priority executor.

Normal suggestion lookups may continue concurrently as read-only operations. Closing, reloading, clearing, garbage collection, and entry mutation are serialized around the snapshot by the keyboard executor.

Reflection into the private lock, unsynchronized access from arbitrary threads, and opportunistic dictionary-file copying are deliberately rejected.

## Edit semantics

The edit planner is pure and does not modify current production dictionaries. It defines the future transactional behavior:

- **Forget** removes matching learned words and optionally related n-grams, then creates tombstones;
- **Never learn** performs the same cleanup and adds or updates a `do-not-learn` rule;
- **Pin** creates or updates a manual word and adds or updates a `pin` rule;
- **Correction rule** creates or updates one typed/replacement decision without banning either word globally.

Every edit validates its output before returning. The same operations can therefore be used by the Android UI and the future Model Studio.

## Transaction and runtime boundary

The editable store uses immutable numbered generations. There is no mutable current-pointer file. A new generation becomes visible only after its complete staging directory has been flushed and atomically renamed.

Every writer supplies the generation ID it read. A stale writer receives a conflict and must rebuild its edit against the new current generation.

Rollback copies an older dataset into a new generation instead of changing history. If the newest generation is corrupt, the newest older valid generation is selected and the damaged generation number remains reserved.

The runtime snapshot is a deeply immutable and pre-indexed copy of one committed generation. The source controller replaces the active runtime reference only after persistence, read-back validation, and runtime compilation all succeed.

If runtime compilation fails after persistence, the previous runtime remains active and the committed generation can be compiled again later.

## Shadow-mode boundary

The production dictionary path remains authoritative. Shadow mode receives a bounded copy of non-swipe `SuggestionResults` and evaluates it against one immutable experimental runtime snapshot on a background thread.

It does not retain words, previous-word context, application IDs, touch coordinates, or sentence text. Completed events contain process-local salted fingerprints, lengths, counts, source types, rule findings, generation identity, and evaluation latency.

The same production `SuggestionResults` instance continues into the existing FUTO transformation and autocorrection logic. No shadow finding currently suppresses, promotes, adds, or removes a candidate.

## Merge policy boundary

The current merge planner automatically resolves only unambiguous cases:

- newer revision of the same record ID;
- identical same-revision records;
- newer tombstone deleting an older live record;
- newer live revision explicitly superseding an older tombstone;
- independent records with distinct logical keys.

It reports a conflict for:

- same ID and revision with different content;
- record-kind disagreement;
- tombstone-kind disagreement;
- different IDs with the same logical key;
- invalid input datasets.

Observation counts from two different record IDs are not added automatically. The source intervals may overlap, and summing them would produce false statistics. The future Model Studio can present resolution choices and preserve a merge audit trail.

## Not yet connected to production learning

- no existing user-history file is rewritten;
- no portable record changes a visible suggestion or autocorrection;
- edit plans are not applied to current runtime dictionaries;
- no `do-not-learn`, pin, preference, or correction-block rule affects typing;
- no personal export is written to user-selected storage;
- no import modifies local personal data;
- no normal settings UI lists automatic history records;
- instrumentation tests are compiled in CI but have not yet been executed on a real device or emulator;
- shadow events are diagnostic in-memory data and not a stable report format.

## Next implementation steps

1. compile the current shadow implementation and Android test APK in CI;
2. execute instrumentation tests on an emulator and at least one real device;
3. measure typing-thread overhead, background evaluation latency, queue drops, and memory use;
4. define a versioned privacy-reviewed shadow report with aggregate statistics and no raw text;
5. validate a conservative mapping from legacy user-history evidence into portable learned records;
6. collect enough shadow evidence to define manual-word, learned-word, and rule parity thresholds;
7. add normal in-app search and transactional application of forget, never-learn, pin, and
   pair-block actions only after those thresholds pass;
8. add user-selected `.futopersonal` export and preview-only import;
9. add confirmed import with rollback;
10. start the separate Model Studio repository once package, benchmark, personalization, and
    report contracts are stable enough to consume without Android-internal assumptions.
