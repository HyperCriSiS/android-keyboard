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
- instrumentation tests for codecs, validation, archives, merge behavior, edit behavior, snapshots,
  store transactions, recovery, rollback, runtime indexing, activation, deterministic IDs, and
  legacy mapping;
- a draft pull request used as the long-running CI and review channel;
- successful CI compilation of `unstableDebug`, the Android test APK, Kotlin, Java, JNI, and NDK;
- successful portable-tool tests on Windows and Ubuntu with Python 3.11 and 3.13.

The transactional store contract is documented in:

```text
docs/personalization/STORE.md
```

## Existing storage sources

### Android personal dictionary

Manual words are stored through Android's `UserDictionary` provider. The current FUTO settings UI can already add, edit, remove, import, and list these records.

The new inventory reader can migrate them without direct access to private dictionary files. Original creation and modification timestamps are unavailable, so the migration time is recorded and the source is marked `migrated-android-user-dictionary`.

The legacy `appId` field is not included in portable data because app-specific scope is privacy-sensitive and the numeric value is not portable across devices.

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
- no portable record is used for live suggestions;
- edit plans are not applied to current runtime dictionaries;
- the new source controller is not connected to the production suggestion pipeline;
- no `do-not-learn` or correction rule affects typing yet;
- no personal export is written to user-selected storage;
- no import modifies local personal data;
- no normal settings UI lists automatic history records yet;
- instrumentation tests are compiled in CI but have not yet been executed on a real device or emulator.

## Next implementation steps

1. create a migration plan from manual Android personal words into a separate experimental store;
2. expose source-store generations and recovery status in a Developer-only screen;
3. compile the experimental runtime snapshot beside the current dictionaries;
4. compare current and new personalization decisions in shadow mode;
5. define parity and rollback acceptance thresholds;
6. add normal in-app search and transactional application of forget, never-learn, pin, and
   pair-block actions only after shadow-mode validation;
7. add user-selected `.futopersonal` export and preview-only import;
8. add confirmed import with rollback;
9. implement bulk editing in the separate Model Studio repository.
