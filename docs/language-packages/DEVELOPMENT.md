# Language package development workflow

The current implementation supports inspection and debug installation. Installation stores a fully validated package privately and immutably, but it does not activate any dictionary, model, rule, adapter, or profile.

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

## Deliberately not implemented yet

- component activation;
- package selection per language;
- package update policy and rollback UI;
- removal UI;
- runtime compatibility probes;
- component resolution across installed packages;
- signature verification and trust policy;
- public package repository integration.

ZIP entries are always written as regular private files rather than restoring archive permissions or symbolic links. External ZIP attributes therefore cannot create links during installation.

The next implementation step is the component registry and resolver: enumerate installed components, evaluate compatibility, and produce a deterministic inactive/active selection plan before any runtime integration.
