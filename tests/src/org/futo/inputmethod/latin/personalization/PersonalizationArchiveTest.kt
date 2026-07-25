package org.futo.inputmethod.latin.personalization

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class PersonalizationArchiveTest {
    @Test
    fun preparedArchiveRoundTripsThroughStrictInspector() {
        val source = PersonalizationTestFixtures.validData()
        val prepared = PersonalizationArchiveBuilder.prepare(source, options())

        val inspection = PersonalizationArchiveInspector.inspect(prepared.toByteArray())

        assertTrue(inspection.issues.joinToString { it.message }, inspection.isValid)
        assertEquals(prepared.manifest, inspection.manifest)
        assertEquals(source, inspection.data)
        assertEquals(
            setOf("manifest.json", "data.json"),
            inspection.entries.keys,
        )
    }

    @Test
    fun selectedCategoriesAreFilteredBeforeManifestIsCreated() {
        val prepared = PersonalizationArchiveBuilder.prepare(
            PersonalizationTestFixtures.validData(),
            options(
                categories = setOf(
                    PersonalizationCategory.ManualWords,
                    PersonalizationCategory.WordRules,
                ),
            ),
        )

        assertEquals(1, prepared.data.manualWords.size)
        assertEquals(1, prepared.data.wordRules.size)
        assertTrue(prepared.data.learnedWords.isEmpty())
        assertTrue(prepared.data.learnedNgrams.isEmpty())
        assertTrue(prepared.data.correctionRules.isEmpty())
        assertTrue(prepared.data.tombstones.isEmpty())
        assertEquals(1, prepared.manifest.counts.manualWords)
        assertEquals(1, prepared.manifest.counts.wordRules)
        assertFalse(prepared.manifest.privacy.containsNgrams)
        assertFalse(prepared.manifest.privacy.containsAppScopes)
        assertTrue(PersonalizationArchiveInspector.inspect(prepared.toByteArray()).isValid)
    }

    @Test
    fun archiveWriterDoesNotCloseCallerOutputStream() {
        val prepared = PersonalizationArchiveBuilder.prepare(
            PersonalizationTestFixtures.validData(),
            options(),
        )
        val output = TrackingOutputStream()

        prepared.writeTo(output)
        output.write(0x7f)

        val written = output.toByteArray()
        assertFalse(output.closed)
        assertTrue(written.size > 1)
        assertEquals(0x7f.toByte(), written.last())
    }

    @Test
    fun modifiedDataPayloadIsRejectedByHashAndSizeValidation() {
        val prepared = PersonalizationArchiveBuilder.prepare(
            PersonalizationTestFixtures.validData(),
            options(),
        )
        val modifiedData = prepared.dataBytes + byteArrayOf('\n'.code.toByte())
        val archive = zipOf(
            "manifest.json" to prepared.manifestBytes,
            "data.json" to modifiedData,
        )

        val inspection = PersonalizationArchiveInspector.inspect(archive)
        val codes = inspection.issues.map { it.code }.toSet()

        assertFalse(inspection.isValid)
        assertTrue("data_size_mismatch" in codes)
        assertTrue("data_hash_mismatch" in codes)
    }

    @Test
    fun unsafeAndCaseCollidingPathsAreRejected() {
        val prepared = PersonalizationArchiveBuilder.prepare(
            PersonalizationTestFixtures.validData(),
            options(),
        )
        val unsafe = zipOf(
            "../manifest.json" to prepared.manifestBytes,
            "data.json" to prepared.dataBytes,
        )
        val collision = zipOf(
            "manifest.json" to prepared.manifestBytes,
            "Manifest.json" to prepared.manifestBytes,
            "data.json" to prepared.dataBytes,
        )

        assertTrue(
            PersonalizationArchiveInspector.inspect(unsafe).issues.any {
                it.code == "unsafe_archive_path"
            },
        )
        assertTrue(
            PersonalizationArchiveInspector.inspect(collision).issues.any {
                it.code == "duplicate_archive_path"
            },
        )
    }

    @Test
    fun missingRequiredEntryAndInvalidUtf8AreRejected() {
        val prepared = PersonalizationArchiveBuilder.prepare(
            PersonalizationTestFixtures.validData(),
            options(),
        )
        val missingData = zipOf("manifest.json" to prepared.manifestBytes)
        val invalidUtf8 = zipOf(
            "manifest.json" to prepared.manifestBytes,
            "data.json" to byteArrayOf(0xc3.toByte(), 0x28),
        )

        assertTrue(
            PersonalizationArchiveInspector.inspect(missingData).issues.any {
                it.code == "missing_data"
            },
        )
        assertTrue(
            PersonalizationArchiveInspector.inspect(invalidUtf8).issues.any {
                it.code == "invalid_data"
            },
        )
    }

    @Test
    fun entryAndPayloadLimitsAreEnforced() {
        val prepared = PersonalizationArchiveBuilder.prepare(
            PersonalizationTestFixtures.validData(),
            options(),
        )
        val archive = prepared.toByteArray()
        val payloadLimited = PersonalizationArchiveInspector.inspect(
            archive,
            PersonalizationArchiveLimits(maxDataBytes = 16),
        )
        val entryLimited = PersonalizationArchiveInspector.inspect(
            archive,
            PersonalizationArchiveLimits(maxEntryCount = 1),
        )

        assertTrue(payloadLimited.issues.any { it.code == "archive_entry_too_large" })
        assertTrue(entryLimited.issues.any { it.code == "archive_entry_limit_exceeded" })
    }

    @Test
    fun unknownEntryIsPreservedAsWarningButNeverInterpreted() {
        val prepared = PersonalizationArchiveBuilder.prepare(
            PersonalizationTestFixtures.validData(),
            options(),
        )
        val archive = zipOf(
            "manifest.json" to prepared.manifestBytes,
            "data.json" to prepared.dataBytes,
            "attachments/README.txt" to "human readable".toByteArray(),
        )

        val inspection = PersonalizationArchiveInspector.inspect(archive)

        assertTrue(inspection.isValid)
        assertTrue(inspection.issues.any { it.code == "unreferenced_archive_entry" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyCategorySelectionIsRejected() {
        PersonalizationArchiveBuilder.prepare(
            PersonalizationTestFixtures.validData(),
            options(categories = emptySet()),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun encryptionFlagIsRejectedUntilEncryptedContainerIsSpecified() {
        PersonalizationArchiveBuilder.prepare(
            PersonalizationTestFixtures.validData(),
            options(encrypted = true),
        )
    }

    private fun options(
        categories: Set<PersonalizationCategory> = PersonalizationCategory.entries.toSet(),
        encrypted: Boolean = false,
    ): PersonalizationExportOptions {
        return PersonalizationExportOptions(
            application = PersonalizationExportApplication(
                id = "org.futo.inputmethod.latin",
                versionName = "0.0.0-test",
                versionCode = 1,
            ),
            createdAt = 10_000,
            originDeviceId = "88888888-8888-4888-8888-888888888888",
            includedCategories = categories,
            encrypted = encrypted,
            exportIdFactory = PersonalizationExportIdFactory {
                "77777777-7777-4777-8777-777777777777"
            },
        )
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }
}
