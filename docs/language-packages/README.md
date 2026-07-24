# FUTO Language Package Specification

**Status:** Draft  
**Format version:** `0.1`  
**Archive extension:** `.futolanguage`

This specification defines a portable and modular format for language resources used by FUTO Keyboard and by the future FUTO Keyboard Model Studio.

A package may provide:

- a complete recommended language setup;
- one reusable component;
- one or more profiles combining components from multiple installed packages.

Installation and activation are separate operations. Importing a package never silently changes typing behavior.

## Design goals

- Install a complete language setup with one file.
- Combine components from several packages.
- Keep dictionaries, rules, and small adapters stackable.
- Keep large runtime slots exclusive by default.
- Make conflicts and incompatibilities explicit.
- Preserve exact user selections across package updates.
- Validate archives deterministically before installation.
- Permit existing standard GGUF models to be wrapped without modifying their weights.
- Share contracts between Android, Model Studio, CI, and community tooling.
- Remain forward-compatible through versioned manifests, tasks, capabilities, and runtime adapters.

## Non-goals for format `0.1`

- Executing arbitrary package code.
- Downloading dependencies while importing a local package.
- Automatically activating every installed component.
- Running multiple large models for the same task by default.
- Replacing every existing legacy dictionary/model path immediately.
- Defining the final public repository and trust protocol.

## Archive layout

A language package is a ZIP archive using UTF-8 file names and `/` separators.

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

`manifest.json` must be located at the archive root.

Archives must not contain:

- absolute paths;
- path traversal;
- duplicate normalized paths;
- encrypted entries;
- executable package code;
- links restored from ZIP metadata.

Importers enforce configurable limits for compressed size, expanded size, entry count, individual entry size, manifest size, and compression ratio.

## Package identity and versions

Package IDs use reverse-domain notation:

```text
org.example.german.standard
```

Every component has a local ID and its own semantic version. Package and component versions are independent.

An immutable installed component coordinate is:

```text
<package-id>@<package-version>:<component-id>@<component-version>
```

Multiple package versions may be installed side by side.

## Package kinds

### `bundle`

Contains several components and normally one or more profiles. This is the standard one-click distribution format.

### `component`

Primarily distributes one reusable component, such as a dictionary or ranker.

### `profile`

Contains configuration only and combines components already installed from other packages.

## Component kinds

| Kind | Activation | Purpose |
|---|---|---|
| `dictionary` | stackable | Words, frequencies, shortcuts, names, or specialist vocabulary |
| `language-rules` | stackable | Capitalization, splitting, compounding, filtering, and punctuation rules |
| `candidate-generator` | exclusive | Produces correction candidates from typed input |
| `context-ranker` | exclusive | Scores complete candidates using context |
| `correction-model` | exclusive | Keyboard-native correction model, optionally using touch geometry |
| `swipe-model` | exclusive | Gesture typing decoder |
| `personalization-adapter` | stackable | Small preference, domain, dialect, or local-learning layer |

Stackable components are not activated merely because they are installed.

## Tasks and capabilities

Tasks describe callable contracts:

- `dictionary-lookup-v1`
- `candidate-ranking-v1`
- `tap-correction-v1`
- `swipe-decoding-v1`
- `german-compounding-v1`

Capabilities describe required or optional behavior:

- `unicode-graphemes`
- `full-candidate-logprob`
- `touch-coordinates`
- `word-frequency`
- `offline-only`

Unknown required tasks or capabilities make a component unavailable. Unknown optional capabilities may be ignored.

## Runtime bindings

A component may select a declarative runtime adapter outside its payload:

```json
{
  "runtime": {
    "id": "gguf-causal-ranker",
    "apiVersion": 1,
    "parameters": {
      "boundaryMode": "leading-separator",
      "maxContextTokens": 256,
      "maxBatchSize": 16,
      "supportsRightContext": true,
      "bosPolicy": "model-default",
      "addEos": false,
      "contextTruncation": "keep-last"
    }
  }
}
```

This allows Model Studio to package an unchanged standard GGUF and describe how FUTO should use it.

Runtime providers are registered by stable ID and supported API-version range. Adapter-specific parameters are strictly validated by that provider.

## Profiles

Profiles provide named configurations such as:

- Standard
- Fast
- Maximum accuracy
- Low memory

A profile selection targets a component slot and uses:

- `replace` — replace the inherited selection;
- `append` — add components to a stackable selection.

References may target components inside the same package or components from another installed package.

Profiles are recommendations, not permanent user state.

## Reference resolution

An internal reference omits `packageId` and remains inside the exact owning package version.

An external reference declares `packageId` and may include a component-version range:

```json
{
  "packageId": "org.example.german.models",
  "componentId": "ranker-small",
  "versionRange": ">=1.2.0 <2.0.0",
  "required": true,
  "weight": 1.0
}
```

Supported range forms include exact versions, comparator sets, caret ranges, tilde ranges, and `*`.

Resolution is deterministic and documented in:

```text
docs/language-packages/RESOLUTION.md
```

## Selection precedence

The inactive resolver applies:

1. explicit user override;
2. selected custom or package profile;
3. deterministic automatic selection;
4. built-in keyboard fallback outside the package resolver.

An explicit user selection must not be replaced by a package update.

Required dependency closure is added to the plan. A disabled dependency slot or a conflicting exclusive dependency makes the plan invalid.

## Candidate ranker

The first model runtime contract scores complete candidate sequences rather than only the first candidate token.

It supports:

- exact left context;
- exact candidate replacement text;
- optional bounded right context;
- complete token log-probability sums;
- candidate and right-context token counts;
- prefix KV-cache reuse;
- deterministic batching and diagnostics;
- standard GGUF tokenizers;
- legacy external SentencePiece tokenizers.

The contract and experimental generic GGUF adapter are documented in:

```text
docs/language-packages/CANDIDATE_RANKER.md
```

## Benchmark and fusion

Suggestion ranking and automatic correction are evaluated separately.

The benchmark records:

- candidate coverage;
- Top-1 and Top-3 accuracy;
- mean reciprocal rank;
- false-correction rate;
- correct, wrong, and missed autocorrection rates;
- p50 and p95 latency.

The first transparent fusion harness keeps every signal contribution visible and applies separate confidence, margin, risk, blocked-word, and offensive-word protections before autocorrect.

The format is documented in:

```text
docs/language-packages/BENCHMARK.md
```

## Integrity

Every payload declares:

- safe relative archive path;
- media type;
- SHA-256 digest;
- uncompressed byte size.

The importer validates payloads before installation and revalidates extracted bytes.

Package signatures are separate from payload hashes. A signature file must never be treated as trusted until a later trust-store specification validates it.

## Compatibility

A component may declare:

- minimum and maximum keyboard API;
- Android ABIs;
- minimum RAM;
- languages and layouts;
- tasks and capabilities;
- required runtime features;
- component dependencies.

Components are evaluated independently. One incompatible optional model must not prevent usable dictionaries or rules from the same package from being installed.

## Validation and installation stages

1. archive safety and resource limits;
2. strict UTF-8 and JSON parsing;
3. JSON Schema validation;
4. semantic manifest validation;
5. digest and size validation;
6. staging inside app-private storage;
7. declared-file extraction only;
8. extracted-byte revalidation;
9. atomic side-by-side installation;
10. independent runtime compatibility and probe checks;
11. inactive resolution planning;
12. explicit activation in a later production layer.

A failed operation must not modify the active keyboard configuration.

## Immutable store

Installed packages are stored side by side by package ID and package version.

Identical repeated imports are idempotent. Different bytes under the same ID and version produce a conflict.

Runtime-generated caches, personal learning, calibration data, and benchmark output stay outside immutable package directories.

## Forward compatibility

Unknown fields are accepted only when prefixed with `x-`.

A future incompatible format increments `formatVersion`. Adapter behavior evolves through runtime API versions rather than ambiguous interpretation of old parameters.

## Normative and supporting files

```text
docs/language-packages/schema/manifest-v0.1.schema.json
docs/language-packages/RESOLUTION.md
docs/language-packages/CANDIDATE_RANKER.md
docs/language-packages/BENCHMARK.md
docs/language-packages/DEVELOPMENT.md
docs/language-packages/examples/german-standard.manifest.json
docs/language-packages/examples/german-correction-benchmark.json
```

## Current implementation milestones

Completed on the development branch:

1. manifest schema and Kotlin model;
2. strict codec and semantic validation;
3. safe archive inspection;
4. immutable atomic package store;
5. component registry and deterministic resolver;
6. candidate-ranker contract and provider registry;
7. standard-GGUF tokenizer support;
8. experimental complete-sequence native GGUF scorer;
9. debug package, registry, probe, and sample-score screens;
10. transparent experimental fusion and correction benchmark contracts.

Next:

1. full Gradle/NDK and device verification;
2. suite runner and model calibration;
3. candidate extraction from the existing suggestion pipeline;
4. offline comparison against the current FUTO algorithm;
5. guarded shadow-mode integration without changing output;
6. production score fusion and persisted per-language selection;
7. Model Studio package, conversion, training, and benchmark UI;
8. signing and public repository protocol.
