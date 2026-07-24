# Personalization Development Status

This document tracks the implementation boundary for inspectable and editable personal language data.

## Implemented on `dev`

- portable `.futopersonal` architecture specification;
- manifest and data JSON Schemas for format `0.1`;
- Kotlin manifest and record model;
- strict JSON codecs with `x-` extension support;
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
- deterministic legacy IDs derived from normalized content;
- read-only inventory of Android's system personal dictionary;
- conversion of system personal words to portable manual-word records;
- explicit reporting when legacy app IDs are omitted or frequencies are clamped;
- a mapper from legacy `WordProperty` objects to transparent user-history inventory;
- instrumentation tests for codecs, validation, merge behavior, deterministic IDs, and legacy mapping.

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

Dictionary mutations are serialized on the single-threaded `KEYBOARD` executor and protected by a private read/write lock. A production inventory scan must participate in that lock rather than reading the native dictionary concurrently or copying files opportunistically.

The preferred implementation is a small snapshot API inside `ExpandableBinaryDictionary` that:

1. schedules work after queued mutations;
2. acquires the existing read lock;
3. traverses with token `0` until the next token returns to `0`;
4. enforces a maximum record count and cancellation checks;
5. returns word properties without exposing the native handle;
6. releases the lock before mapping or rendering data.

Reflection into the private lock and unsynchronized access to `BinaryDictionary` are deliberately rejected.

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
- no learned word is deleted through the new layer;
- no `do-not-learn` or correction rule affects typing yet;
- no personal export is written to user-selected storage;
- no import modifies local personal data;
- no UI lists automatic history records yet.

## Next implementation steps

1. add the safe bounded `ExpandableBinaryDictionary` snapshot API;
2. bind it to `UserHistoryDictionary` through a read-only inventory service;
3. add a Developer screen showing manual and automatic inventory by locale;
4. export a preview-only `.futopersonal` archive;
5. design the transactional editable source store;
6. compile a runtime snapshot from that store;
7. compare current and new learning in shadow mode;
8. add normal in-app search, forget, never-learn, pin, and pair-block actions;
9. add import/export with rollback;
10. implement bulk editing in the separate Model Studio repository.
