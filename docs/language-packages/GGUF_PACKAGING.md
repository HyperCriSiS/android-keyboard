# Packaging a standard GGUF candidate ranker

**Tool status:** Reference CLI for development and Model Studio interoperability  
**Runtime:** `gguf-causal-ranker` API `1`

An existing causal GGUF model can be used as a FUTO context-ranker component without modifying its weights or GGUF metadata.

The package manifest supplies the FUTO-specific runtime contract outside the model payload.

## Portable entry point

Use:

```text
tools/language-packages/wrap-gguf-ranker.py
```

The tool uses only the Python standard library and is tested on Windows and Linux.

The current split between `wrap-gguf-ranker.py` and the reference implementation is temporary. Model Studio will eventually call the same package-building core directly rather than invoking a subprocess.

## Example

PowerShell:

```powershell
python.exe tools/language-packages/wrap-gguf-ranker.py `
  D:\Models\german-small.gguf `
  D:\Models\german-small.futolanguage `
  --package-id org.example.german.small-ranker `
  --package-name "German Small Ranker" `
  --package-version 1.0.0 `
  --component-version 1.0.0 `
  --author "Example Community" `
  --license Apache-2.0 `
  --language de `
  --language de-DE `
  --layout qwertz `
  --boundary-mode leading-separator `
  --max-context-tokens 256 `
  --max-batch-size 16
```

Bash:

```bash
python3 tools/language-packages/wrap-gguf-ranker.py \
  ./german-small.gguf \
  ./german-small.futolanguage \
  --package-id org.example.german.small-ranker \
  --package-name "German Small Ranker" \
  --package-version 1.0.0 \
  --component-version 1.0.0 \
  --author "Example Community" \
  --license Apache-2.0 \
  --language de \
  --language de-DE \
  --layout qwertz \
  --boundary-mode leading-separator
```

## Required metadata

The author must explicitly provide:

- stable reverse-domain package ID;
- package name and version;
- component version;
- author;
- redistributable license;
- at least one BCP-47 language tag;
- candidate-boundary mode.

The tool does not guess a license, language, or tokenizer boundary convention.

## Candidate boundaries

### `leading-separator`

Use when normal replacement candidates contain their separator before the word:

```text
" wahrscheinlich"
```

This is common for tokenizers that attach whitespace to the following token.

### `trailing-separator`

Use when replacement candidates normally contain the separator after the word:

```text
"wahrscheinlich "
```

This matches FUTO's earlier suffix-whitespace KeyboardLM strategy.

### `exact-text`

Use when the package makes no general separator claim. Every candidate is still passed as exact text.

Boundary choice must be validated with the target model and benchmark suite. The packager does not infer it automatically.

## Runtime options

### Context tokens

```text
--max-context-tokens 256
```

Accepted manifest range: 16 to 32768.

The current Android native scorer has an additional implementation context limit of 2048 tokens. Declaring a larger value does not expand the compiled model context.

### Native candidate batch

```text
--max-batch-size 16
```

Accepted range: 1 to 64.

The Kotlin runtime partitions larger contract requests while preserving candidate order.

### BOS policy

```text
--bos-policy model-default
--bos-policy always
--bos-policy never
```

`model-default` follows the tokenizer metadata embedded in the GGUF.

### Right context

Right-context scoring is enabled by default.

Disable it with:

```text
--disable-right-context
```

### EOS

EOS scoring is disabled by default.

Enable it with:

```text
--disable-right-context --add-eos
```

EOS and right-context scoring cannot be enabled together because EOS would declare the sequence complete before the continuation.

### Context truncation

```text
--context-truncation keep-last
--context-truncation reject
```

`keep-last` retains the most recent left-context tokens when the configured limit is exceeded.

## Package output

The generated archive contains exactly:

```text
manifest.json
components/ranker.gguf
```

The model is stored without ZIP compression because GGUF data is already dense and must be copied efficiently.

The tool:

1. validates CLI metadata;
2. streams SHA-256 and byte-size calculation over the input model;
3. creates a strict runtime-bound manifest;
4. writes a temporary ZIP beside the requested output;
5. closes all native file handles before replacement, including on Windows;
6. atomically replaces the output path;
7. reopens the package;
8. verifies entry order, manifest bytes, model bytes, SHA-256, and size.

A failed build removes the temporary file and returns exit code `2`.

## Model requirements

The Android runtime currently expects:

- a causal GGUF loadable by the embedded llama.cpp version;
- a tokenizer embedded in the standard GGUF, or a legacy explicitly embedded KeyboardLM SentencePiece tokenizer;
- usable BOS behavior according to the selected policy;
- enough context capacity for left context, candidate, and optional continuation;
- acceptable latency and memory on the target device.

Packaging success does not prove model compatibility or quality.

After installation, use:

```text
Developer → Language package registry → Probe
```

Then run:

```text
Score sample
```

A full benchmark is required before any production activation.

## Reproducibility

The manifest uses stable UTF-8 JSON formatting and fixed ZIP timestamps. Model bytes are never transformed.

The resulting package is deterministic for identical:

- model bytes;
- command-line metadata;
- Python/ZIP implementation behavior.

Model Studio will record the package hash, model hash, benchmark suite hash, conversion source, quantization, and exported manifest in its project lock file.

## Automated tests

The portable tool test:

- runs the CLI as a subprocess;
- verifies model byte identity;
- verifies SHA-256 and size;
- validates the runtime binding;
- confirms invalid metadata does not create an output file.

GitHub Actions runs these tests on:

- Ubuntu;
- Windows;
- Python 3.11;
- Python 3.13.
