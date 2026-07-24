# Language package development workflow

The current implementation is intentionally inspection-only. It validates a package and displays its contents, but does not extract, install, activate, or persist any component.

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

## Inspect the package on Android

1. Build and install an `unstableDebug` APK from the `dev` branch.
2. Open FUTO Keyboard settings.
3. Open **Developer**.
4. Select **Language package inspector**.
5. Select the generated `.futolanguage` file.

The expected result is:

- package status: valid;
- package ID: `org.futo.example.german.dictionary`;
- one stackable dictionary component;
- zero validation errors;
- zero validation warnings.

The inspector performs all archive reading on `Dispatchers.IO`. It does not route through the existing theme/model importer and does not modify the active keyboard configuration.

## What is currently validated

- safe normalized relative ZIP paths;
- case-insensitive path collisions;
- archive entry and expanded-size limits;
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
- unreferenced archive entries.

## Deliberately not implemented yet

- component installation;
- atomic package storage;
- package update and rollback;
- runtime compatibility probes;
- component resolution across installed packages;
- signature verification and trust policy;
- symlink detection from ZIP external attributes;
- public package repository integration.

The next implementation step is an immutable package store that installs a fully validated archive side by side without activating its components.
