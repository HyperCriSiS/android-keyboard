package org.futo.inputmethod.latin.languagepack

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlinx.serialization.SerializationException

private const val LANGUAGE_PACKAGE_MANIFEST_PATH = "manifest.json"

data class LanguagePackageArchiveLimits(
    val maxEntryCount: Int = 4096,
    val maxManifestBytes: Long = 2L * 1024L * 1024L,
    val maxEntryBytes: Long = 1024L * 1024L * 1024L,
    val maxTotalBytes: Long = 4L * 1024L * 1024L * 1024L,
)

data class LanguagePackageArchiveEntryInfo(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class LanguagePackageInspectionResult(
    val manifest: LanguagePackageManifest?,
    val entries: Map<String, LanguagePackageArchiveEntryInfo>,
    val issues: List<LanguagePackageValidationIssue>,
) {
    val isValid: Boolean
        get() = manifest != null && issues.none {
            it.severity == LanguagePackageValidationSeverity.Error
        }
}

object LanguagePackageArchiveInspector {
    /**
     * Consumes, but does not close, [input]. No archive entry is extracted to the file system.
     */
    fun inspect(
        input: InputStream,
        limits: LanguagePackageArchiveLimits = LanguagePackageArchiveLimits(),
    ): LanguagePackageInspectionResult {
        val issues = mutableListOf<LanguagePackageValidationIssue>()
        val entries = linkedMapOf<String, LanguagePackageArchiveEntryInfo>()
        val canonicalPaths = mutableMapOf<String, String>()
        var manifestBytes: ByteArray? = null
        var entryCount = 0
        var totalBytes = 0L

        fun error(code: String, path: String, message: String) {
            issues += LanguagePackageValidationIssue(
                severity = LanguagePackageValidationSeverity.Error,
                code = code,
                path = path,
                message = message,
            )
        }

        fun warning(code: String, path: String, message: String) {
            issues += LanguagePackageValidationIssue(
                severity = LanguagePackageValidationSeverity.Warning,
                code = code,
                path = path,
                message = message,
            )
        }

        if (limits.maxEntryCount < 1 || limits.maxManifestBytes < 1L ||
            limits.maxEntryBytes < 1L || limits.maxTotalBytes < 1L
        ) {
            error("invalid_archive_limits", "archive", "Archive inspection limits must be positive.")
            return LanguagePackageInspectionResult(null, emptyMap(), issues)
        }

        val zip = ZipInputStream(input)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

        try {
            while (true) {
                val zipEntry = zip.nextEntry ?: break
                entryCount += 1
                if (entryCount > limits.maxEntryCount) {
                    error(
                        "archive_entry_limit_exceeded",
                        "archive",
                        "Archive contains more than ${limits.maxEntryCount} entries.",
                    )
                    break
                }

                val path = zipEntry.name
                val safePath = validateAndCanonicalizeArchivePath(path)
                if (safePath == null) {
                    error("unsafe_archive_path", path, "Archive entry path is unsafe or not normalized.")
                    drainEntry(zip, buffer, limits.maxEntryBytes)
                    zip.closeEntry()
                    continue
                }

                val previousPath = canonicalPaths.put(safePath.canonicalKey, path)
                if (previousPath != null) {
                    error(
                        "duplicate_archive_path",
                        path,
                        "Archive path collides with '$previousPath' after normalization.",
                    )
                }

                if (zipEntry.isDirectory) {
                    zip.closeEntry()
                    continue
                }

                val digest = MessageDigest.getInstance("SHA-256")
                val manifestOutput = if (safePath.normalized == LANGUAGE_PACKAGE_MANIFEST_PATH) {
                    ByteArrayOutputStream()
                } else {
                    null
                }
                var entryBytes = 0L
                var entryTooLarge = false

                while (true) {
                    val read = zip.read(buffer)
                    if (read == -1) break
                    if (read == 0) continue

                    entryBytes += read
                    totalBytes += read

                    if (entryBytes > limits.maxEntryBytes) {
                        if (!entryTooLarge) {
                            error(
                                "archive_entry_too_large",
                                path,
                                "Archive entry exceeds ${limits.maxEntryBytes} bytes.",
                            )
                            entryTooLarge = true
                        }
                    }
                    if (totalBytes > limits.maxTotalBytes) {
                        error(
                            "archive_total_size_exceeded",
                            "archive",
                            "Archive expands beyond ${limits.maxTotalBytes} bytes.",
                        )
                        return LanguagePackageInspectionResult(null, entries, issues)
                    }

                    digest.update(buffer, 0, read)
                    if (manifestOutput != null) {
                        if (entryBytes <= limits.maxManifestBytes) {
                            manifestOutput.write(buffer, 0, read)
                        } else if (entryBytes - read <= limits.maxManifestBytes) {
                            error(
                                "manifest_too_large",
                                LANGUAGE_PACKAGE_MANIFEST_PATH,
                                "Manifest exceeds ${limits.maxManifestBytes} bytes.",
                            )
                        }
                    }
                }

                zip.closeEntry()

                val info = LanguagePackageArchiveEntryInfo(
                    path = safePath.normalized,
                    sizeBytes = entryBytes,
                    sha256 = digest.digest().toHexString(),
                )
                entries[safePath.normalized] = info

                if (manifestOutput != null && entryBytes <= limits.maxManifestBytes) {
                    if (manifestBytes != null) {
                        error(
                            "duplicate_manifest",
                            LANGUAGE_PACKAGE_MANIFEST_PATH,
                            "Archive contains more than one manifest entry.",
                        )
                    } else {
                        manifestBytes = manifestOutput.toByteArray()
                    }
                }
            }
        } catch (exception: ZipException) {
            error("invalid_zip", "archive", exception.message ?: "Archive is not a valid ZIP file.")
        } catch (exception: RuntimeException) {
            error("archive_read_failed", "archive", exception.message ?: exception.javaClass.simpleName)
        }

        val rawManifest = manifestBytes
        if (rawManifest == null) {
            error("missing_manifest", LANGUAGE_PACKAGE_MANIFEST_PATH, "Archive does not contain a readable root manifest.")
            return LanguagePackageInspectionResult(null, entries, issues)
        }

        val manifestText = try {
            decodeUtf8Strict(rawManifest)
        } catch (_: Exception) {
            error("invalid_manifest_encoding", LANGUAGE_PACKAGE_MANIFEST_PATH, "Manifest must be valid UTF-8.")
            return LanguagePackageInspectionResult(null, entries, issues)
        }

        val manifest = try {
            LanguagePackageManifestCodec.decode(manifestText)
        } catch (exception: SerializationException) {
            error("invalid_manifest", LANGUAGE_PACKAGE_MANIFEST_PATH, exception.message ?: "Manifest is invalid.")
            return LanguagePackageInspectionResult(null, entries, issues)
        } catch (exception: IllegalArgumentException) {
            error("invalid_manifest", LANGUAGE_PACKAGE_MANIFEST_PATH, exception.message ?: "Manifest is invalid.")
            return LanguagePackageInspectionResult(null, entries, issues)
        }

        issues += LanguagePackageManifestValidator.validate(manifest).issues
        verifyDeclaredPayloads(manifest, entries, ::error)

        val ownedPaths = buildSet {
            add(LANGUAGE_PACKAGE_MANIFEST_PATH)
            manifest.components.forEach { add(it.payload.path) }
            manifest.resources.forEach { add(it.path) }
        }
        entries.keys
            .filterNot { it in ownedPaths || it.startsWith("signatures/") }
            .forEach { path ->
                warning(
                    "unreferenced_archive_entry",
                    path,
                    "Archive entry is not referenced by the manifest.",
                )
            }

        return LanguagePackageInspectionResult(manifest, entries, issues)
    }

    private fun verifyDeclaredPayloads(
        manifest: LanguagePackageManifest,
        entries: Map<String, LanguagePackageArchiveEntryInfo>,
        error: (String, String, String) -> Unit,
    ) {
        data class ExpectedPayload(
            val path: String,
            val sha256: String,
            val sizeBytes: Long,
            val manifestPath: String,
        )

        val expected = buildList {
            manifest.components.forEachIndexed { index, component ->
                add(
                    ExpectedPayload(
                        path = component.payload.path,
                        sha256 = component.payload.sha256,
                        sizeBytes = component.payload.sizeBytes,
                        manifestPath = "components[$index].payload",
                    ),
                )
            }
            manifest.resources.forEachIndexed { index, resource ->
                add(
                    ExpectedPayload(
                        path = resource.path,
                        sha256 = resource.sha256,
                        sizeBytes = resource.sizeBytes,
                        manifestPath = "resources[$index]",
                    ),
                )
            }
        }

        expected.forEach { payload ->
            val actual = entries[payload.path]
            if (actual == null) {
                error(
                    "missing_payload",
                    payload.manifestPath,
                    "Declared payload '${payload.path}' is missing from the archive.",
                )
                return@forEach
            }
            if (!actual.sha256.equals(payload.sha256, ignoreCase = true)) {
                error(
                    "payload_hash_mismatch",
                    payload.manifestPath,
                    "SHA-256 mismatch for '${payload.path}'.",
                )
            }
            if (actual.sizeBytes != payload.sizeBytes) {
                error(
                    "payload_size_mismatch",
                    payload.manifestPath,
                    "Size mismatch for '${payload.path}': expected ${payload.sizeBytes}, got ${actual.sizeBytes}.",
                )
            }
        }
    }

    private data class SafeArchivePath(
        val normalized: String,
        val canonicalKey: String,
    )

    private fun validateAndCanonicalizeArchivePath(path: String): SafeArchivePath? {
        if (path.isBlank() || path.startsWith('/') || path.contains('\\') || path.contains('\u0000')) {
            return null
        }
        if (path.length >= 2 && path[1] == ':' && path[0].isLetter()) return null
        if (path.any { it.code < 0x20 }) return null

        val isDirectory = path.endsWith('/')
        val withoutTrailingSlash = if (isDirectory) path.dropLast(1) else path
        if (withoutTrailingSlash.isBlank()) return null

        val segments = withoutTrailingSlash.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return null

        val normalized = Normalizer.normalize(withoutTrailingSlash, Normalizer.Form.NFC)
        if (normalized != withoutTrailingSlash) return null

        return SafeArchivePath(
            normalized = normalized,
            canonicalKey = normalized.lowercase(Locale.ROOT),
        )
    }

    private fun drainEntry(zip: ZipInputStream, buffer: ByteArray, maximumBytes: Long) {
        var drained = 0L
        while (true) {
            val read = zip.read(buffer)
            if (read == -1) return
            if (read == 0) continue
            drained += read
            if (drained > maximumBytes) return
        }
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String = Charsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    private fun ByteArray.toHexString(): String {
        val chars = CharArray(size * 2)
        val digits = "0123456789abcdef"
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            chars[index * 2] = digits[value ushr 4]
            chars[index * 2 + 1] = digits[value and 0x0f]
        }
        return chars.concatToString()
    }
}
