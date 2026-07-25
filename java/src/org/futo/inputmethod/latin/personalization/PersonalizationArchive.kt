package org.futo.inputmethod.latin.personalization

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.SerializationException

private const val PERSONALIZATION_MANIFEST_PATH = "manifest.json"
private const val PERSONALIZATION_DATA_PATH = "data.json"
private const val PERSONALIZATION_ARCHIVE_TIMESTAMP = 315_532_800_000L // 1980-01-01 UTC

fun interface PersonalizationExportIdFactory {
    fun newId(): String
}

object RandomPersonalizationExportIdFactory : PersonalizationExportIdFactory {
    override fun newId(): String = UUID.randomUUID().toString()
}

data class PersonalizationExportOptions(
    val application: PersonalizationExportApplication,
    val createdAt: Long,
    val originDeviceId: String? = null,
    val includedCategories: Set<PersonalizationCategory> = PersonalizationCategory.entries.toSet(),
    val encrypted: Boolean = false,
    val exportIdFactory: PersonalizationExportIdFactory = RandomPersonalizationExportIdFactory,
    val maxDataBytes: Int = 64 * 1024 * 1024,
)

data class PreparedPersonalizationArchive(
    val manifest: PersonalizationExportManifest,
    val data: PersonalizationDataSet,
    val manifestBytes: ByteArray,
    val dataBytes: ByteArray,
) {
    fun writeTo(output: OutputStream) {
        PersonalizationArchiveWriter.write(this, output)
    }

    fun toByteArray(): ByteArray {
        val output = ByteArrayOutputStream(manifestBytes.size + dataBytes.size + 1024)
        writeTo(output)
        return output.toByteArray()
    }
}

object PersonalizationArchiveBuilder {
    fun prepare(
        source: PersonalizationDataSet,
        options: PersonalizationExportOptions,
    ): PreparedPersonalizationArchive {
        require(options.createdAt >= 0L) { "createdAt must not be negative." }
        require(options.maxDataBytes > 0) { "maxDataBytes must be positive." }
        require(options.includedCategories.isNotEmpty()) {
            "At least one personalization category must be included."
        }
        require(!options.encrypted) {
            "Encrypted personalization containers are not defined by format 0.1."
        }

        val sourceValidation = PersonalizationDataValidator.validateData(source)
        require(sourceValidation.isValid) {
            "Source personalization data is invalid: " +
                sourceValidation.errors.joinToString { "${it.path}: ${it.message}" }
        }

        val filtered = filterCategories(source, options.includedCategories)
        val dataText = PersonalizationDataCodec.encodeData(filtered)
        val dataBytes = dataText.toByteArray(Charsets.UTF_8)
        require(dataBytes.size <= options.maxDataBytes) {
            "Encoded personalization data exceeds ${options.maxDataBytes} bytes."
        }

        val manifest = PersonalizationExportManifest(
            formatVersion = PERSONALIZATION_FORMAT_VERSION,
            exportId = options.exportIdFactory.newId(),
            createdAt = options.createdAt,
            application = options.application,
            originDeviceId = options.originDeviceId,
            includedCategories = options.includedCategories.sortedBy { it.ordinal },
            locales = filtered.locales().sortedWith(String.CASE_INSENSITIVE_ORDER),
            privacy = PersonalizationPrivacy(
                containsNgrams = filtered.learnedNgrams.isNotEmpty(),
                containsAppScopes = filtered.correctionRules.any { it.appScope != null },
                containsSentenceText = false,
                encrypted = false,
            ),
            data = PersonalizationPayload(
                sha256 = sha256(dataBytes),
                sizeBytes = dataBytes.size.toLong(),
            ),
            counts = PersonalizationCounts.from(filtered),
        )
        val validation = PersonalizationDataValidator.validate(manifest, filtered, dataBytes)
        check(validation.isValid) {
            "Prepared personalization export is invalid: " +
                validation.errors.joinToString { "${it.path}: ${it.message}" }
        }

        return PreparedPersonalizationArchive(
            manifest = manifest,
            data = filtered,
            manifestBytes = PersonalizationDataCodec.encodeManifest(manifest)
                .toByteArray(Charsets.UTF_8),
            dataBytes = dataBytes,
        )
    }

    private fun filterCategories(
        source: PersonalizationDataSet,
        included: Set<PersonalizationCategory>,
    ): PersonalizationDataSet {
        return PersonalizationDataSet(
            formatVersion = PERSONALIZATION_FORMAT_VERSION,
            manualWords = source.manualWords.takeIf {
                PersonalizationCategory.ManualWords in included
            }.orEmpty(),
            learnedWords = source.learnedWords.takeIf {
                PersonalizationCategory.LearnedWords in included
            }.orEmpty(),
            learnedNgrams = source.learnedNgrams.takeIf {
                PersonalizationCategory.LearnedNgrams in included
            }.orEmpty(),
            wordRules = source.wordRules.takeIf {
                PersonalizationCategory.WordRules in included
            }.orEmpty(),
            correctionRules = source.correctionRules.takeIf {
                PersonalizationCategory.CorrectionRules in included
            }.orEmpty(),
            tombstones = source.tombstones.takeIf {
                PersonalizationCategory.Tombstones in included
            }.orEmpty(),
        )
    }
}

private object PersonalizationArchiveWriter {
    fun write(
        archive: PreparedPersonalizationArchive,
        output: OutputStream,
    ) {
        val zip = ZipOutputStream(NonClosingOutputStream(output))
        try {
            writeEntry(zip, PERSONALIZATION_MANIFEST_PATH, archive.manifestBytes)
            writeEntry(zip, PERSONALIZATION_DATA_PATH, archive.dataBytes)
            zip.finish()
        } finally {
            zip.close()
        }
    }

    private fun writeEntry(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        val entry = ZipEntry(path).apply {
            time = PERSONALIZATION_ARCHIVE_TIMESTAMP
            method = ZipEntry.DEFLATED
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun close() {
            flush()
        }
    }
}

data class PersonalizationArchiveLimits(
    val maxEntryCount: Int = 32,
    val maxManifestBytes: Long = 2L * 1024L * 1024L,
    val maxDataBytes: Long = 64L * 1024L * 1024L,
    val maxTotalBytes: Long = 70L * 1024L * 1024L,
    val maxCompressionRatio: Double = 200.0,
)

data class PersonalizationArchiveEntryInfo(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class PersonalizationArchiveInspection(
    val manifest: PersonalizationExportManifest?,
    val data: PersonalizationDataSet?,
    val entries: Map<String, PersonalizationArchiveEntryInfo>,
    val issues: List<PersonalizationValidationIssue>,
) {
    val isValid: Boolean
        get() = manifest != null && data != null && issues.none {
            it.severity == PersonalizationValidationSeverity.Error
        }
}

object PersonalizationArchiveInspector {
    fun inspect(
        bytes: ByteArray,
        limits: PersonalizationArchiveLimits = PersonalizationArchiveLimits(),
    ): PersonalizationArchiveInspection {
        return inspect(ByteArrayInputStream(bytes), limits)
    }

    fun inspect(
        input: InputStream,
        limits: PersonalizationArchiveLimits = PersonalizationArchiveLimits(),
    ): PersonalizationArchiveInspection {
        val issues = mutableListOf<PersonalizationValidationIssue>()
        val entries = linkedMapOf<String, PersonalizationArchiveEntryInfo>()
        val canonicalPaths = mutableSetOf<String>()
        var manifestBytes: ByteArray? = null
        var dataBytes: ByteArray? = null
        var totalBytes = 0L
        var entryCount = 0

        fun error(code: String, path: String, message: String) {
            issues += PersonalizationValidationIssue(
                PersonalizationValidationSeverity.Error,
                code,
                path,
                message,
            )
        }

        fun warning(code: String, path: String, message: String) {
            issues += PersonalizationValidationIssue(
                PersonalizationValidationSeverity.Warning,
                code,
                path,
                message,
            )
        }

        if (
            limits.maxEntryCount < 1 ||
            limits.maxManifestBytes < 1L ||
            limits.maxDataBytes < 1L ||
            limits.maxTotalBytes < 1L ||
            !limits.maxCompressionRatio.isFinite() ||
            limits.maxCompressionRatio <= 0.0
        ) {
            error("invalid_archive_limits", "archive", "Archive limits must be positive and finite.")
            return PersonalizationArchiveInspection(null, null, emptyMap(), issues)
        }

        val zip = ZipInputStream(input)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        try {
            while (true) {
                val zipEntry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > limits.maxEntryCount) {
                    error(
                        "archive_entry_limit_exceeded",
                        "archive",
                        "Archive contains more than ${limits.maxEntryCount} entries.",
                    )
                    return PersonalizationArchiveInspection(null, null, entries, issues)
                }

                val safePath = safeArchivePath(zipEntry.name)
                if (safePath == null) {
                    error(
                        "unsafe_archive_path",
                        zipEntry.name,
                        "Archive path is unsafe or not normalized.",
                    )
                    return PersonalizationArchiveInspection(null, null, entries, issues)
                }
                val canonical = safePath.lowercase(Locale.ROOT)
                if (!canonicalPaths.add(canonical)) {
                    error(
                        "duplicate_archive_path",
                        safePath,
                        "Archive contains a case-insensitive or normalized path collision.",
                    )
                    return PersonalizationArchiveInspection(null, null, entries, issues)
                }
                if (zipEntry.isDirectory) {
                    zip.closeEntry()
                    continue
                }

                val allowedLimit = when (safePath) {
                    PERSONALIZATION_MANIFEST_PATH -> limits.maxManifestBytes
                    PERSONALIZATION_DATA_PATH -> limits.maxDataBytes
                    else -> limits.maxDataBytes
                }
                val output = ByteArrayOutputStream()
                val digest = MessageDigest.getInstance("SHA-256")
                var entryBytes = 0L
                while (true) {
                    val read = zip.read(buffer)
                    if (read == -1) break
                    if (read == 0) continue
                    entryBytes += read
                    totalBytes += read
                    if (entryBytes > allowedLimit) {
                        error(
                            "archive_entry_too_large",
                            safePath,
                            "Archive entry exceeds its configured size limit.",
                        )
                        return PersonalizationArchiveInspection(null, null, entries, issues)
                    }
                    if (totalBytes > limits.maxTotalBytes) {
                        error(
                            "archive_total_size_exceeded",
                            "archive",
                            "Archive expands beyond ${limits.maxTotalBytes} bytes.",
                        )
                        return PersonalizationArchiveInspection(null, null, entries, issues)
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
                zip.closeEntry()

                val compressedSize = zipEntry.compressedSize
                if (
                    compressedSize > 0L &&
                    entryBytes.toDouble() / compressedSize.toDouble() > limits.maxCompressionRatio
                ) {
                    error(
                        "archive_compression_ratio_exceeded",
                        safePath,
                        "Archive entry exceeds the allowed compression ratio.",
                    )
                    return PersonalizationArchiveInspection(null, null, entries, issues)
                }

                val content = output.toByteArray()
                entries[safePath] = PersonalizationArchiveEntryInfo(
                    path = safePath,
                    sizeBytes = entryBytes,
                    sha256 = digest.digest().toHexString(),
                )
                when (safePath) {
                    PERSONALIZATION_MANIFEST_PATH -> manifestBytes = content
                    PERSONALIZATION_DATA_PATH -> dataBytes = content
                    else -> warning(
                        "unreferenced_archive_entry",
                        safePath,
                        "Format 0.1 ignores archive entries other than manifest.json and data.json.",
                    )
                }
            }
        } catch (exception: ZipException) {
            error("invalid_zip", "archive", exception.message ?: "Invalid ZIP archive.")
            return PersonalizationArchiveInspection(null, null, entries, issues)
        } catch (exception: Exception) {
            error("archive_read_failed", "archive", exception.message ?: exception.javaClass.simpleName)
            return PersonalizationArchiveInspection(null, null, entries, issues)
        }

        val rawManifest = manifestBytes
        val rawData = dataBytes
        if (rawManifest == null) {
            error("missing_manifest", PERSONALIZATION_MANIFEST_PATH, "Archive has no manifest.json.")
        }
        if (rawData == null) {
            error("missing_data", PERSONALIZATION_DATA_PATH, "Archive has no data.json.")
        }
        if (rawManifest == null || rawData == null) {
            return PersonalizationArchiveInspection(null, null, entries, issues)
        }

        val manifest = try {
            PersonalizationDataCodec.decodeManifest(decodeUtf8Strict(rawManifest))
        } catch (exception: Exception) {
            error(
                "invalid_manifest",
                PERSONALIZATION_MANIFEST_PATH,
                exception.message ?: "Manifest is invalid.",
            )
            null
        }
        val data = try {
            PersonalizationDataCodec.decodeData(decodeUtf8Strict(rawData))
        } catch (exception: Exception) {
            error(
                "invalid_data",
                PERSONALIZATION_DATA_PATH,
                exception.message ?: "Data payload is invalid.",
            )
            null
        }
        if (manifest == null || data == null) {
            return PersonalizationArchiveInspection(manifest, data, entries, issues)
        }

        issues += PersonalizationDataValidator.validate(manifest, data, rawData).issues
        return PersonalizationArchiveInspection(manifest, data, entries, issues)
    }

    private fun safeArchivePath(path: String): String? {
        if (path.isBlank() || path.startsWith('/') || path.contains('\\') || path.contains('\u0000')) {
            return null
        }
        if (path.length >= 2 && path[1] == ':' && path[0].isLetter()) return null
        if (path.any { it.code < 0x20 }) return null
        val withoutTrailingSlash = path.removeSuffix("/")
        if (withoutTrailingSlash.isBlank()) return null
        val segments = withoutTrailingSlash.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return null
        val normalized = Normalizer.normalize(withoutTrailingSlash, Normalizer.Form.NFC)
        if (normalized != withoutTrailingSlash) return null
        return normalized
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String {
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }
}

private fun sha256(bytes: ByteArray): String {
    return MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
}

private fun ByteArray.toHexString(): String {
    val digits = "0123456789abcdef"
    val output = CharArray(size * 2)
    forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        output[index * 2] = digits[value ushr 4]
        output[index * 2 + 1] = digits[value and 0x0f]
    }
    return output.concatToString()
}
