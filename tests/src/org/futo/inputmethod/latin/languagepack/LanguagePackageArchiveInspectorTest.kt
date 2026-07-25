package org.futo.inputmethod.latin.languagepack

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class LanguagePackageArchiveInspectorTest {
    @Test
    fun validArchivePassesInspection() {
        val payload = "dictionary payload".toByteArray()
        val manifest = manifestForPayload(payload)
        val archive = buildArchive(
            manifest = manifest,
            files = mapOf("components/dictionary.dict" to payload),
        )

        val result = LanguagePackageArchiveInspector.inspect(ByteArrayInputStream(archive))

        assertTrue(result.issues.joinToString { it.message }, result.isValid)
    }

    @Test
    fun unsafeEntryPathAbortsInspection() {
        val payload = "dictionary payload".toByteArray()
        val archive = buildArchive(
            manifest = manifestForPayload(payload),
            files = linkedMapOf(
                "../outside.txt" to "unsafe".toByteArray(),
                "components/dictionary.dict" to payload,
            ),
        )

        val result = LanguagePackageArchiveInspector.inspect(ByteArrayInputStream(archive))

        assertFalse(result.isValid)
        assertTrue(result.issues.any { it.code == "unsafe_archive_path" })
    }

    @Test
    fun payloadHashMismatchIsRejected() {
        val payload = "dictionary payload".toByteArray()
        val manifest = manifestForPayload(payload).let { original ->
            original.copy(
                components = listOf(
                    original.components.single().copy(
                        payload = original.components.single().payload.copy(
                            sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
                        ),
                    ),
                ),
            )
        }
        val archive = buildArchive(
            manifest = manifest,
            files = mapOf("components/dictionary.dict" to payload),
        )

        val result = LanguagePackageArchiveInspector.inspect(ByteArrayInputStream(archive))

        assertFalse(result.isValid)
        assertTrue(result.issues.any { it.code == "payload_hash_mismatch" })
    }

    @Test
    fun caseInsensitivePathCollisionIsRejected() {
        val payload = "dictionary payload".toByteArray()
        val archive = buildArchive(
            manifest = manifestForPayload(payload),
            files = linkedMapOf(
                "components/dictionary.dict" to payload,
                "Components/Dictionary.dict" to payload,
            ),
        )

        val result = LanguagePackageArchiveInspector.inspect(ByteArrayInputStream(archive))

        assertFalse(result.isValid)
        assertTrue(result.issues.any { it.code == "duplicate_archive_path" })
    }

    private fun manifestForPayload(payload: ByteArray): LanguagePackageManifest {
        return LanguagePackageManifest(
            formatVersion = CURRENT_LANGUAGE_PACKAGE_FORMAT_VERSION,
            packageInfo = LanguagePackageInfo(
                id = "org.futo.community.german.dictionary",
                name = "German Dictionary",
                version = "1.0.0",
                kind = LanguagePackageKind.Component,
                authors = listOf(LanguagePackageAuthor(name = "FUTO Community")),
                license = "Apache-2.0",
                languages = listOf("de"),
            ),
            components = listOf(
                LanguagePackageComponent(
                    id = "dictionary",
                    name = "German Dictionary",
                    version = "1.0.0",
                    kind = LanguagePackageComponentKind.Dictionary,
                    activation = LanguagePackageActivation.Stackable,
                    languages = listOf("de"),
                    tasks = listOf("dictionary-lookup-v1"),
                    payload = LanguagePackagePayload(
                        path = "components/dictionary.dict",
                        mediaType = "application/vnd.futo.keyboard.dictionary",
                        sha256 = sha256(payload),
                        sizeBytes = payload.size.toLong(),
                    ),
                ),
            ),
        )
    }

    private fun buildArchive(
        manifest: LanguagePackageManifest,
        files: Map<String, ByteArray>,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(LanguagePackageManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            files.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
