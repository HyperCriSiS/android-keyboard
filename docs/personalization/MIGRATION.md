# Experimental Manual-Dictionary Migration

**Status:** Implemented on `dev`, Developer-only  
**Production effect:** None

This document describes the first migration into the transactional personalization source store.
It imports only Android personal-dictionary words. It does not migrate automatic user-history
evidence and does not change production suggestions or learning.

## Source

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

## Stable identity

Every source word receives a deterministic UUID derived from normalized:

- record kind;
- locale;
- word;
- shortcut.

The same Android personal word therefore receives the same record ID on repeated scans. Migration
time is not part of the identity.

## Planning rules

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

## Idempotency

Repeated migration is idempotent.

Timestamps, revision, provenance, and origin-device metadata are not compared when deciding whether
an equivalent stable record is already present. A second scan at a later time therefore reports the
word as `AlreadyPresent` and creates no new generation.

## Apply protocol

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

## Developer screen

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

## Tests

Instrumentation tests cover:

- first migration into a new store;
- repeated idempotent migration;
- tombstone protection against resurrection;
- changed local records producing conflicts;
- truncated source rejection;
- logical duplicates under different IDs requiring review.

## Remaining migration work

Automatic `UserHistoryDictionary` data is not included yet. Its legacy probability and historical
fields require measured mapping and shadow-mode parity tests before they may become portable learned
records.

The next stage is to build the experimental runtime beside the current personalization sources and
compare decisions without changing user-visible suggestions.
