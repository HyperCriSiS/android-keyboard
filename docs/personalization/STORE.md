# Transactional Personalization Source Store

**Status:** Experimental implementation on `dev`  
**Store format:** `0.1`

This document defines the local editable source-of-truth store for personal language data. It is separate from Android's existing `UserDictionary` provider, legacy `UserHistoryDictionary` files, portable `.futopersonal` exports, and compiled runtime lookup structures.

The store is implemented but is not connected to production learning or suggestions.

## Goals

- Make every edit atomic and recoverable.
- Keep committed history immutable and inspectable.
- Detect concurrent or stale writers instead of silently losing edits.
- Permit rollback without rewriting or deleting previous generations.
- Recover from an incomplete or corrupt newest generation.
- Keep the low-latency runtime isolated from editable files.
- Refuse unsafe reinitialization when potentially recoverable data exists.

## Directory layout

```text
personalization-source/
├── .lock
├── .staging/
└── generations/
    ├── 00000000000000000001-<hash-prefix>/
    │   ├── data.json
    │   └── commit.json
    ├── 00000000000000000002-<hash-prefix>/
    │   ├── data.json
    │   └── commit.json
    └── ...
```

Generation directory names contain:

1. a zero-padded monotonically increasing generation number;
2. the first twelve hexadecimal characters of the SHA-256 digest of `data.json`.

There is deliberately no mutable `current` pointer file. The highest valid generation is current.

## Commit protocol

A transaction follows this sequence:

1. acquire the in-process store lock;
2. acquire the operating-system file lock;
3. remove abandoned staging directories;
4. read and validate the current generation;
5. compare the caller's expected generation ID with the current generation ID;
6. validate the proposed personalization dataset;
7. encode canonical `data.json` bytes;
8. calculate SHA-256 and size;
9. create complete `commit.json` journal metadata;
10. write both files into a random staging directory;
11. flush both files to storage;
12. atomically rename the complete staging directory into `generations/`;
13. read the committed generation back and validate it again.

Until step 12 completes, the new generation is not visible. A crash before the rename leaves only a removable staging directory. A crash after the rename leaves a complete immutable generation.

## Optimistic concurrency

Every edit is based on an expected generation ID.

```text
read generation A
build edit against A
commit expected=A
```

If generation B became current before the commit, the store returns a conflict containing both IDs. It never applies the edit to B implicitly.

The caller may then:

- reload B and re-run the pure edit planner;
- show a merge preview;
- ask the user to resolve a conflict.

This avoids silent lost updates when the settings UI, migration, import, or a future sync process operate concurrently.

## Generation journal

`commit.json` records:

- store format version;
- generation number and generation ID;
- parent generation ID;
- commit time;
- commit reason;
- optional source generation ID;
- `data.json` SHA-256 and byte size;
- structured descriptions of the records added, updated, deleted, or tombstoned.

Commit reasons currently include:

- `initialization`;
- `user-edit`;
- `import`;
- `migration`;
- `rollback`.

The journal is diagnostic and auditable. `data.json` remains the authoritative state for that generation.

## Rollback

Rollback never changes an old generation and never moves a pointer backward.

Instead it:

1. validates the selected historical generation;
2. copies its dataset into a new generation;
3. records `rollback` as the reason;
4. records the restored generation as `sourceGenerationId`;
5. keeps every intermediate generation intact.

A rollback can therefore itself be rolled back.

## Recovery

At startup the store checks generations from highest number to lowest.

A generation is rejected when, for example:

- either required file is missing;
- file limits are exceeded;
- metadata is malformed or uses an unsupported store version;
- generation number, directory name, and metadata disagree;
- SHA-256 or byte size does not match;
- `data.json` cannot be decoded;
- the personalization dataset is semantically invalid.

The newest valid older generation becomes current and the skipped directories are reported as recovery issues.

Generation numbers found on disk remain reserved even when their contents are corrupt. A later commit uses a higher number and never reuses the damaged generation number.

If generation directories exist but none are valid, initialization fails. The store refuses to overwrite potentially recoverable data with a new empty generation.

## Limits

Default limits are intentionally conservative and configurable:

| Item | Default limit |
|---|---:|
| `data.json` | 64 MiB |
| `commit.json` | 8 MiB |
| journal changes per commit | 100,000 |

Limits are enforced before a generation becomes visible and again when it is read.

These limits are not expected to constrain ordinary users. They protect the keyboard from damaged, malicious, or accidentally enormous local data.

## Immutable snapshots

Store reads return deep immutable copies:

- record lists cannot be modified;
- nested n-gram term lists cannot be modified;
- journal change lists cannot be modified.

The runtime compiler produces another deep immutable representation with pre-built indexes for:

- locale and global manual words;
- learned-word lookup;
- word-learning rules;
- locale-specific and app-specific correction rules.

A held runtime snapshot never changes when a newer editable generation is committed.

## Correction-rule precedence

Runtime correction rules are resolved from most specific to least specific:

1. exact locale and exact application scope;
2. exact locale and global application scope;
3. global locale and exact application scope;
4. global locale and global application scope.

The semantic validator rejects conflicting rules with the same canonical key.

## Store-to-runtime activation

`PersonalizationSourceController` coordinates persisted generations and the active runtime reference.

A normal update follows this boundary:

```text
pure edit plan
    ↓
atomic store commit
    ↓
read-back and validation
    ↓
compile immutable runtime snapshot
    ↓
atomic reference replacement
```

The active runtime changes only after every preceding step succeeds.

### Failure behavior

| Failure | Persisted generation | Active runtime |
|---|---|---|
| invalid edit/import | unchanged | unchanged |
| stale generation conflict | unchanged | unchanged |
| write or validation failure | unchanged | unchanged |
| runtime compilation failure after commit | new generation retained | previous runtime retained |
| later successful reload | retained generation compiled | atomically replaced |

A runtime compilation failure is therefore recoverable without losing the committed user edit and without exposing a partially built runtime state.

## Current safety boundary

The implementation currently does **not**:

- migrate existing personal words into the store automatically;
- replace Android's system personal dictionary;
- replace or rewrite legacy user-history dictionaries;
- feed runtime snapshots into live suggestions;
- apply `do-not-learn` or correction rules while typing;
- expose destructive actions in the normal settings UI;
- delete historical generations automatically.

The next integration stage is shadow-mode parity testing. Existing learning remains authoritative while the new source store and runtime snapshot are built and compared in parallel.
