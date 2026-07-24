# Personalization Data Architecture

**Status:** Draft  
**Portable export format:** `0.1`  
**Recommended extension:** `.futopersonal`

This document defines how FUTO Keyboard should expose, edit, export, merge, and apply personal language data without making immutable language packages or neural model files user-specific.

Personalization data is private mutable user state. It is not part of `.futolanguage` packages.

## Goals

- Let users inspect and correct what the keyboard learned.
- Make one incorrect learned word removable directly in the app.
- Provide explicit controls for words or corrections that must not be learned again.
- Preserve useful frequency and recency information without storing private sentences.
- Support bulk editing, merge, backup, and analysis in the future Model Studio.
- Keep runtime lookup fast by separating editable source data from optimized dictionary snapshots.
- Make imports deterministic, validated, reversible, and privacy-preserving.

## Non-goals for format 0.1

- Exporting raw private messages or complete sentence histories.
- Editing neural model weights as if they were word-list entries.
- Treating contacts, clipboard contents, or app text as automatically exportable personal data.
- Replacing Android's system personal dictionary immediately.
- Defining the final encrypted container or cloud-sync protocol.
- Making the desktop tool mandatory for normal cleanup.

## Data layers

The effective suggestion system should combine independent layers:

```text
immutable base dictionary
immutable language-package dictionaries and rules
manual personal words
learned unigram and n-gram evidence
word-learning rules
correction-pair rules
runtime caches and compiled snapshots
```

Immutable resources are never modified by user cleanup. Personal data overlays them and can suppress, prefer, or augment their behavior.

## User-visible categories

### Manual words

Words explicitly added by the user, including optional shortcuts. These are authoritative and should survive forgetting-curve decay.

Examples:

- names;
- product or project names;
- abbreviations;
- technical vocabulary;
- deliberate non-standard spelling.

### Learned words

Words inferred from normal typing behavior. A learned word records evidence rather than becoming an unconditional dictionary truth.

Recommended fields include:

- locale;
- normalized word;
- observation count;
- first and last observation times;
- confidence;
- whether the word is currently active or suppressed;
- provenance that does not include private sentence text.

### Learned n-grams

Short word sequences used to improve contextual ranking. The portable format limits sequences to four normalized terms and does not carry the original sentence.

N-grams must be separately controllable because deleting a unigram alone may leave contextual evidence that keeps producing the same unwanted suggestion.

### Word-learning rules

Explicit user decisions about one word:

- `allow-learning`;
- `do-not-learn`;
- `pin`.

`do-not-learn` removes active learned evidence and prevents the learning pipeline from immediately recreating it. It does not remove the word from immutable dictionaries.

### Correction rules

Explicit decisions about a typed/candidate pair:

- `allow`;
- `prefer`;
- `block-autocorrect`;
- `block-suggestion`.

A rule can therefore express that `im` must not be automatically replaced by `ihm` without globally banning either valid word.

## Required in-app operations

The app must provide these operations without requiring a desktop computer:

- search and filter by locale, data kind, activity state, and source;
- inspect a word's origin, count, confidence, and last-use time;
- remove current learned evidence;
- remove associated n-grams;
- mark a word as `do-not-learn`;
- pin a word as an explicit personal word;
- block one automatic-correction pair;
- undo a recent destructive operation;
- clear learned data for one locale or date range;
- export and import personal data.

The UI should distinguish these actions:

- **Forget**: remove current learned evidence; the word may be learned again.
- **Never learn**: remove current evidence and create a `do-not-learn` rule.
- **Add to personal dictionary**: create an authoritative manual word.
- **Never autocorrect this pair**: retain both words but block that replacement.

## Desktop-tool responsibilities

The future Model Studio may additionally provide:

- virtualized tables for hundreds of thousands of records;
- regex and batch operations;
- duplicate and spelling-variant detection;
- merge preview across devices and backups;
- frequency, recency, and correction statistics;
- CSV/JSON interchange;
- suspicious-entry detection;
- bulk creation of word and correction rules;
- comparison of two snapshots;
- encrypted backup management.

The desktop tool must use the same portable schema and validation rules as Android. It must not require direct access to Android's internal binary dictionaries.

## Editable source and compiled snapshot

Personalization should use two representations:

### Editable source store

A transactional database containing user-visible records, stable IDs, timestamps, rules, and tombstones.

This is the source of truth for:

- inspection;
- editing;
- export;
- merge;
- rollback.

### Compiled runtime snapshot

An optimized dictionary or lookup structure generated atomically from the editable source store.

This is used for low-latency suggestions. It may be deleted and rebuilt at any time. Runtime caches and snapshots are never exported as authoritative personal data.

This separation avoids trying to use an opaque mutable binary dictionary as both the fast runtime structure and the user's editable database.

## Existing FUTO migration

The current keyboard stores automatic learning in per-locale `UserHistoryDictionary` binary dictionaries and manually added words through Android's `UserDictionary` provider.

Migration must therefore be staged:

1. introduce the portable data model and editable store;
2. expose read-only inventory adapters for current manual and learned data;
3. import existing records into the editable store with migration provenance;
4. compile equivalent runtime snapshots;
5. compare old and new suggestions in shadow mode;
6. switch writes only after parity and rollback testing;
7. retain a reversible backup of the old files for at least one release cycle.

A migration failure must leave the existing learning system active.

## Record identity and merge

Every mutable record has:

- stable UUID `id`;
- monotonically increasing `revision`;
- `createdAt` and `updatedAt` epoch milliseconds;
- optional originating device ID;
- canonical content key derived from normalized fields.

Merge rules:

1. a newer revision of the same ID replaces an older revision;
2. tombstones suppress records with the same ID at an older or equal revision;
3. records with different IDs but the same canonical content key are deduplicated;
4. observation counts may be summed only when their source intervals do not overlap;
5. otherwise the maximum count is retained and the merge is reported as ambiguous;
6. explicit rules and manual words take precedence over learned evidence;
7. `do-not-learn` and `block-*` rules are never silently weakened by an import.

The first implementation may reject ambiguous count merges rather than pretending they are exact.

## Privacy

Portable exports must not contain:

- complete sentences;
- arbitrary text surrounding a word;
- clipboard history;
- contact details unless explicitly added as manual words;
- application package names by default;
- raw touch coordinates;
- stable hardware identifiers.

The optional `originDeviceId` is a random export identity generated by the keyboard, not an Android device ID.

N-grams are limited to short normalized term sequences. Export UI must display exactly which categories and locales will be included.

Plain `.futopersonal` files are sensitive. The app must warn before creating an unencrypted export. A future encrypted container should wrap the same canonical JSON payload rather than define a second data model.

## Deletion semantics

Deletion is represented by tombstones in portable data so that a later merge cannot resurrect removed records from an older backup.

A tombstone contains:

- target record ID;
- target record kind;
- revision;
- deletion time;
- optional reason such as `user-delete`, `never-learn`, or `retention-policy`.

Local compaction may permanently discard old tombstones after a documented retention period and after every known peer has advanced beyond them.

## Retention and decay

Learned evidence may decay over time, but explicit user intent does not:

- learned counts and confidence may decay;
- stale learned n-grams may expire;
- manual words remain until removed;
- pinned words remain until unpinned or removed;
- `do-not-learn` and correction-block rules remain until explicitly removed.

The UI must not describe decayed evidence as deleted when it still exists in backups or tombstones.

## Portable archive layout

A `.futopersonal` file is a ZIP archive:

```text
Personalization-2026-07-25.futopersonal
├── manifest.json
├── data.json
└── attachments/
    └── README.txt
```

Format 0.1 requires only `manifest.json` and `data.json`. Attachments are reserved for human-readable reports and future encrypted metadata; executable content is forbidden.

The archive safety, path-normalization, size-limit, hash, staging, and atomic-import requirements are the same as for `.futolanguage` packages.

## Export manifest

The manifest records:

- format version;
- export ID;
- creation time;
- application and schema versions;
- random origin-device ID;
- included categories and locales;
- privacy flags;
- SHA-256 and byte size of `data.json`;
- record and tombstone counts.

## Data payload

`data.json` contains arrays for:

- `manualWords`;
- `learnedWords`;
- `learnedNgrams`;
- `wordRules`;
- `correctionRules`;
- `tombstones`.

Unknown fields are accepted only when prefixed with `x-`.

The normative machine-readable schemas are:

```text
docs/personalization/schema/manifest-v0.1.schema.json
docs/personalization/schema/data-v0.1.schema.json
```

## Safety boundaries

- Imported data is never activated before complete validation.
- A preview shows additions, changes, conflicts, and deletions.
- Destructive imports require explicit confirmation.
- Imports create a rollback snapshot.
- Package updates cannot overwrite personal records.
- Model output cannot directly create permanent manual words or rules.
- Learning writes are ignored when a matching `do-not-learn` rule exists.
- An app-specific scope is opt-in and omitted from exports by default.

## Implementation milestones

1. Portable data classes, strict codecs, schemas, and semantic validation.
2. In-memory merge planner with conflict reporting.
3. Read-only adapters for Android personal words and current user-history dictionaries.
4. Debug inventory and export preview.
5. Transactional editable source store.
6. Compiled runtime snapshot builder.
7. Shadow-mode parity tests against current learning.
8. In-app learned-data browser and single-record actions.
9. Portable import/export with rollback.
10. Bulk editor in the separate Model Studio repository.
