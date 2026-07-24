# Language package development workflow

The current implementation supports inspection, immutable debug installation, component registry construction, compatibility evaluation, and deterministic resolution planning.

No installed dictionary, model, rule, adapter, or profile is loaded or activated yet.

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

## Inspect and install the package on Android

1. Build and install an `unstableDebug` APK from the `dev` branch.
2. Open FUTO Keyboard settings.
3. Open **Developer**.
4. Select **Language package inspector**.
5. Select the generated `.futolanguage` file.
6. Confirm that the package is valid.
7. Select **Install package without activating components**.

The expected inspection result is:

- package status: valid;
- package ID: `org.futo.example.german.dictionary`;
- one stackable dictionary component;
- zero validation errors;
- zero validation warnings.

The expected installation result is **Package installed**. Importing the exact same archive again must report **Package already installed** rather than creating a duplicate.

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

## What is currently validated

- safe normalized relative ZIP paths;
- case-insensitive path collisions;
- archive entry and expanded-size limits;
- compressed archive size before validation;
- manifest size;
- compression-ratio limit when the ZIP metadata exposes compressed size;
- strict UTF-8 manifest decoding;
- strict JSON fields, except explicitly permitted `x-` extensions;
- package, component, profile, and reference semantics;
- component activation cardinality;
- internal dependency cycles;
- declared payload existence;
- SHA-256 digests;
- uncompressed payload sizes;
- unreferenced archive entries;
- extracted content matching the inspected archive.

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
- required dependency closure and conflicts.

## Deliberately not implemented yet

- component activation;
- persisted package selection per language;
- package update policy and rollback UI;
- removal UI;
- component-specific runtime probes;
- candidate ranker runtime implementation;
- signature verification and trust policy;
- public package repository integration.

ZIP entries are always written as regular private files rather than restoring archive permissions or symbolic links. External ZIP attributes therefore cannot create links during installation.

The next implementation step is the candidate-ranker runtime contract: define complete-candidate scoring, batching, cancellation, model probes, and deterministic error reporting before connecting any GGUF model.
