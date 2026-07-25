# Language package development workflow

The current implementation supports:

- package inspection and immutable debug installation;
- component registry construction;
- compatibility evaluation and deterministic resolution planning;
- declarative runtime bindings;
- an experimental generic GGUF candidate-ranker provider;
- complete candidate-sequence scoring with optional right context;
- on-device debug probing and sample scoring.

No installed dictionary, rule, adapter, profile, or model is activated in normal keyboard operation yet. The debug ranker console may load a selected model temporarily and closes it after the operation.

## Build an example package

From the repository root:

```bash
python tools/language-packages/create-example-package.py
```

The command creates:

```text
build/language-packages/german-example.futolanguage
```

An alternative output path may be supplied as the first argument:

```bash
python tools/language-packages/create-example-package.py D:/Temp/example.futolanguage
```

The builder uses only the Python standard library. It calculates the payload SHA-256 and byte size and writes a deterministic ZIP structure suitable for repeated manual tests.

The generated package currently contains only a placeholder dictionary payload. The larger example manifest showing a dictionary, rules, profile, and GGUF ranker binding is:

```text
docs/language-packages/examples/german-standard.manifest.json
```

## Inspect and install a package on Android

1. Build and install an `unstableDebug` APK from the `dev` branch.
2. Open FUTO Keyboard settings.
3. Open **Developer**.
4. Select **Language package inspector**.
5. Select a `.futolanguage` file.
6. Confirm that the package is valid.
7. Select **Install package without activating components**.

For the generated example package, the expected result is:

- package ID: `org.futo.example.german.dictionary`;
- one stackable dictionary component;
- zero validation errors;
- zero validation warnings;
- installation result: **Package installed**.

Importing the exact same archive again must report **Package already installed** rather than creating a duplicate.

The inspector and installer perform file work on `Dispatchers.IO`. They do not route through the existing theme/model importer and do not modify the active keyboard configuration.

## Store layout

Installed packages are stored below the app-private files directory:

```text
language-packages/
├── .staging/
└── packages/
    └── <package-id>/
        └── <version>/
            ├── package.futolanguage
            ├── archive.sha256
            ├── archive.size
            └── content/
                ├── manifest.json
                └── ... declared payloads and signatures
```

The installer:

1. copies the selected archive into a unique staging directory;
2. enforces a compressed archive size limit during the copy;
3. validates that exact staged file;
4. extracts only the manifest, declared payloads/resources, and signature files;
5. verifies extracted sizes and SHA-256 values again;
6. syncs files to storage;
7. renames the complete staging directory into place.

A package ID and version are immutable. Installing different bytes under an already installed ID/version produces a conflict. Identical bytes are treated as an idempotent reinstall.

## Component registry

`LanguagePackageStore.buildRegistry()` creates a pure registry from all readable installed package versions.

The registry exposes:

- exact package, component, and profile coordinates;
- all installed alternatives;
- internal and external component reference resolution;
- SemVer component ranges;
- payload paths;
- recursive dependency evaluation.

Internal references remain inside their exact owning package version. External references may select from multiple installed package versions.

## Compatibility evaluation

Compatibility is evaluated for a target language, layout, and explicit runtime environment.

Current checks include:

- target language and layout;
- keyboard API range;
- Android ABI;
- minimum RAM;
- supported tasks and required capabilities;
- runtime features;
- payload existence and byte size;
- required and optional dependencies;
- cross-package dependency cycles.

Compatibility checks do not instantiate model or dictionary runtimes.

## Resolution planning

`LanguagePackageResolver` creates an inactive selection plan using this precedence:

1. explicit user override;
2. selected package profile;
3. deterministic automatic selection;
4. built-in fallback outside the resolver.

Stackable components are never activated merely because they are installed. Exclusive model slots may be selected automatically, with a warning when alternatives exist.

Required dependency closure is included in the plan. Disabled dependency slots and conflicting exclusive dependencies make the plan invalid.

The full contract is documented in:

```text
docs/language-packages/RESOLUTION.md
```

## Generic GGUF candidate ranker

A standard causal GGUF can be wrapped without modifying its weights or metadata by declaring:

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

The adapter supports the tokenizer embedded in a normal GGUF. Existing KeyboardLM GGUFs using an explicitly embedded external SentencePiece tokenizer remain supported.

The experimental scorer evaluates the left context once, reuses the KV prefix for every candidate, scores every candidate token, and optionally scores a bounded right-context continuation.

The full contract is documented in:

```text
docs/language-packages/CANDIDATE_RANKER.md
```

## Inspect registry and rankers on Android

1. Install at least one package.
2. Open **Developer**.
3. Select **Language package registry**.
4. Review the inactive automatic `de-DE` / `qwertz` resolution plan.
5. Select **Probe** on a bound context ranker.
6. Select **Score sample** to run the German debug sentence.

The sample compares:

```text
Das ist wahrscheinlich richtig.
Das ist anscheinend richtig.
Das ist wirklich richtig.
```

The screen reports normalized scores, candidate/right-context token counts, elapsed time, and native batch count. The model is never persisted as active.

## What is currently validated

- safe normalized relative ZIP paths;
- case-insensitive path collisions;
- archive entry and expanded-size limits;
- compressed archive size before validation;
- manifest size;
- compression-ratio limit when the ZIP metadata exposes compressed size;
- strict UTF-8 manifest decoding;
- strict JSON fields, except explicitly permitted `x-` extensions;
- package, component, profile, reference, and runtime-binding semantics;
- component activation cardinality;
- internal dependency cycles;
- declared payload existence;
- SHA-256 digests;
- uncompressed payload sizes;
- unreferenced archive entries;
- extracted content matching the inspected archive;
- ranker request and result completeness;
- GGUF adapter parameter bounds;
- runtime provider ID and API-version selection.

## Automated coverage

Instrumentation tests currently cover:

- manifest encoding and strict extension handling;
- semantic manifest validation;
- archive inspection and malicious path rejection;
- immutable store installation and conflicts;
- SemVer precedence and ranges;
- internal and external component references;
- runtime compatibility filtering;
- profile and user selection precedence;
- stackable append behavior;
- automatic exclusive selection;
- required dependency closure and conflicts;
- candidate-ranker request/result validation;
- normalization and batch planning;
- runtime-provider selection and descriptor validation;
- generic GGUF adapter configuration;
- multi-batch score ordering and diagnostics through a fake native API;
- runtime errors, disabled right context, and idempotent close.

## Build verification status

The pure SemVer core has been compiled independently with `kotlinc`, which exposed and allowed correction of a type-inference issue.

A full Gradle/NDK build has not yet run in the current execution environment because that container cannot resolve `github.com` to obtain the repository and dependencies. Direct commits to `dev` currently receive no automatic GitHub status checks. The Android instrumentation tests and native sources are therefore committed but still require execution in a normal development environment.

## Deliberately not implemented yet

- production component activation;
- persisted package/profile selection per language;
- integration of ranker scores into live keyboard suggestions;
- dictionary/touch/personalization score fusion;
- confidence and automatic-correction thresholds;
- benchmark dataset and result formats;
- package update policy and rollback UI;
- removal UI;
- deeper model architecture and memory probes;
- runtime pooling and memory-pressure behavior;
- asynchronous cancellation inside one native decode;
- signature verification and trust policy;
- public package repository integration.

ZIP entries are always written as regular private files rather than restoring archive permissions or symbolic links. External ZIP attributes therefore cannot create links during installation.

The next implementation step is the benchmark contract and score-fusion harness. It will let the same German test cases compare dictionary order, raw model scores, fused scores, false-correction risk, latency, and memory before the ranker is connected to production suggestions.
