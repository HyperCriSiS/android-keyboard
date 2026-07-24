# FUTO Language Package Specification

**Status:** Draft  
**Format version:** `0.1`  
**Archive extension:** `.futolanguage`

This document defines the first portable package format for language resources used by FUTO Keyboard and by the FUTO Keyboard Model Studio.

The format is deliberately modular. A package may provide a complete recommended configuration, a single reusable component, or only a profile that combines components from other installed packages.

## Goals

- Install a complete language setup with one file.
- Allow components from multiple packages to be combined.
- Keep dictionaries and rule sets stackable.
- Make model conflicts explicit instead of silently choosing one.
- Permit deterministic validation before importing or activating anything.
- Keep projects and model-building tools independent from the Android runtime.
- Remain forward-compatible through versioned manifests and capabilities.

## Non-goals for version 0.1

- Executing arbitrary code from a package.
- Downloading dependencies during package import.
- Defining the final public package repository protocol.
- Defining model-training recipes.
- Allowing multiple large neural models to run for the same task by default.
- Replacing the existing legacy dictionary format immediately.

## Archive layout

A language package is a ZIP archive using UTF-8 file names and `/` as the path separator.

```text
GermanStandard.futolanguage
├── manifest.json
├── components/
│   ├── dictionary.dict
│   ├── ranker.gguf
│   └── rules.json
├── benchmarks/
│   └── results.json
├── licenses/
│   └── NOTICE.txt
└── signatures/
    └── package.sig
```

`manifest.json` MUST be located at the archive root.

Archives MUST NOT contain:

- absolute paths;
- `..` path traversal;
- symbolic links;
- duplicate normalized paths;
- encrypted entries;
- executable code that is loaded or run during import.

Importers MUST apply configurable limits for total uncompressed size, entry count, individual entry size, and compression ratio.

## Package identity

Package and component identifiers use reverse-domain notation:

```text
org.example.german.standard
org.example.german.ranker
```

Identifiers are stable. Renaming a package creates a different package.

Versions use semantic versioning. Package versions and component versions are independent because a bundle may update metadata without changing every included component.

## Package kinds

A manifest declares one of three package kinds:

### `bundle`

Contains multiple components and one or more recommended profiles. This is the normal one-click installation format.

### `component`

Primarily distributes one reusable component, such as a dictionary or ranker. It may still contain supporting files and profiles.

### `profile`

Contains configuration only. It combines components already installed from other packages.

## Component kinds

Version 0.1 defines these component kinds:

| Kind | Default cardinality | Purpose |
|---|---:|---|
| `dictionary` | stackable | Words, frequencies, shortcuts, names, or domain vocabulary |
| `language-rules` | stackable | Locale-specific capitalization, splitting, compounding, filtering, and punctuation rules |
| `candidate-generator` | exclusive | Produces correction candidates from typed input |
| `context-ranker` | exclusive | Scores complete candidates using context |
| `correction-model` | exclusive | Native keyboard model that may use touch geometry and generate corrections |
| `swipe-model` | exclusive | Gesture typing decoder |
| `personalization-adapter` | stackable | Optional small adapter or local preference layer |

`stackable` means multiple active components may contribute to the same slot.  
`exclusive` means only one component may be active in that slot unless an explicitly supported ensemble mode is introduced later.

A manifest records the activation mode so custom component kinds can be handled safely.

## Tasks and capabilities

A component declares one or more tasks, for example:

- `candidate-ranking-v1`
- `next-word-prediction-v1`
- `tap-correction-v1`
- `swipe-decoding-v1`
- `dictionary-lookup-v1`
- `german-compounding-v1`

Tasks describe the callable contract. Capabilities describe optional behavior, such as:

- `unicode-graphemes`
- `qwertz`
- `touch-coordinates`
- `full-candidate-logprob`
- `offline-only`

Unknown required tasks or capabilities make a component unavailable. Unknown optional capabilities may be ignored.

## Profiles

Profiles provide a named configuration such as:

- Standard
- Fast
- Maximum accuracy
- Low memory

A profile selection targets a component slot and uses one of two strategies:

- `replace`: use the listed component for an exclusive slot or replace the inherited stack;
- `append`: add listed components to a stackable slot.

Component references may point to:

- a component inside the same package;
- a component in another installed package;
- a compatible component selected automatically when the reference is optional.

Profiles are recommendations, not permanent user state.

## Resolution and user overrides

The keyboard resolves active components in this order, highest priority first:

1. explicit user selection;
2. the user's saved custom profile;
3. selected package profile;
4. package recommendation;
5. automatic compatibility selection;
6. built-in fallback.

An explicit user selection MUST NOT be replaced by a package update.

For stackable slots, the resolver merges components in ascending `priority` order and applies user-level sources last. Duplicate words or rules retain their source information so conflicts remain inspectable.

For exclusive slots, the resolver activates exactly one compatible component. If several equally preferred candidates remain, the UI MUST ask the user or use a documented deterministic tie-breaker. It MUST NOT silently switch between models.

## Component references

An internal reference contains only `componentId`.

An external reference additionally contains `packageId` and may contain a semantic `versionRange`.

```json
{
  "packageId": "org.example.german.models",
  "componentId": "ranker-small",
  "versionRange": ">=1.2.0 <2.0.0",
  "required": true,
  "weight": 1.0
}
```

A required missing reference makes the profile unavailable, but does not make unrelated components in the package unusable.

## Integrity

Every component payload MUST declare:

- relative archive path;
- media type;
- SHA-256 digest;
- uncompressed byte size.

The importer verifies these fields before making a component available.

Package signing is intentionally separated from component hashes. Version 0.1 reserves `signatures/` for detached signatures. The exact trust-store and signature envelope are defined in a later security specification; an importer MUST NOT claim a package is trusted merely because a signature file exists.

## Compatibility

A component may declare:

- minimum and maximum keyboard API;
- supported Android ABIs;
- minimum RAM;
- supported locales;
- supported layouts;
- tokenizer requirements;
- runtime features.

Incompatible components may remain installed but cannot be activated.

Compatibility is checked independently for every component. One incompatible optional model must not prevent dictionaries or rules from the same package from being imported.

## Media types

Recommended initial media types:

| Payload | Media type |
|---|---|
| GGUF model | `application/vnd.futo.keyboard.model+gguf` |
| FUTO binary dictionary | `application/vnd.futo.keyboard.dictionary` |
| Language rules | `application/vnd.futo.keyboard.rules+json` |
| Benchmark results | `application/vnd.futo.keyboard.benchmark+json` |

Unknown media types are preserved but not activated unless a compatible runtime is installed.

## Validation stages

Importers perform validation in this order:

1. archive safety and resource limits;
2. `manifest.json` JSON parsing;
3. JSON Schema validation;
4. semantic validation;
5. component digest and size validation;
6. runtime compatibility checks;
7. component-specific probe or self-test;
8. atomic installation.

A failed package import must not modify the active configuration.

## Updates and rollback

Package updates are installed side by side. Activation changes only after all required files pass validation.

The package manager retains enough metadata to:

- roll back to the previous package version;
- keep project-pinned component versions;
- distinguish installed, active, and recommended versions;
- avoid replacing a user-selected component.

## File ownership

Imported package files are immutable. Runtime-generated data such as personal learning, caches, benchmarks, or converted models is stored outside the imported package directory.

This permits reliable hash verification and clean removal.

## Manifest schema

The normative machine-readable schema is:

```text
docs/language-packages/schema/manifest-v0.1.schema.json
```

The schema validates structure. The keyboard and Model Studio MUST also perform semantic checks that JSON Schema cannot express, including:

- unique component IDs;
- valid internal references;
- exclusive slot selections containing at most one component;
- component kind and profile slot agreement;
- safe normalized archive paths;
- task and runtime compatibility;
- digest and actual file size agreement.

## Forward compatibility

Unknown fields are accepted only when prefixed with `x-`.

A future incompatible format increments `formatVersion`. New optional fields may be introduced without changing the major format version when old readers can safely ignore them.

## Initial implementation milestones

1. Manifest schema and Kotlin data model.
2. Pure semantic validator with no Android UI dependency.
3. Safe archive reader and package inspection screen.
4. Debug-only package import.
5. Full component resolver.
6. Candidate-ranker runtime contract.
7. Model Studio package builder and validator.
8. Public package signing and repository protocol.
