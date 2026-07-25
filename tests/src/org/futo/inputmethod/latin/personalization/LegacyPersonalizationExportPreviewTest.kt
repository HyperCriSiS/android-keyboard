package org.futo.inputmethod.latin.personalization

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class LegacyPersonalizationExportPreviewTest {
    @Test
    fun manualInventoryProducesSelfValidatedPortableArchive() {
        val inventory = LegacyPersonalDictionaryInventory(
            words = listOf(
                LegacyManualWordInventoryItem(
                    stableId = LegacyPersonalizationIds.manualWord("de-DE", "FUTO", "futo"),
                    locale = "de-DE",
                    word = "FUTO",
                    shortcut = "futo",
                    frequency = 250,
                    appId = 0,
                ),
            ),
            truncated = false,
        )

        val preview = LegacyPersonalizationExportPreview.prepareManualWords(
            inventory = inventory,
            application = application(),
            createdAt = 10_000,
            exportIdFactory = fixedExportId(),
        )

        assertTrue(preview.isValid)
        assertTrue(preview.isComplete)
        assertEquals(1, preview.preparedArchive.data.manualWords.size)
        assertEquals(
            setOf(PersonalizationCategory.ManualWords),
            preview.preparedArchive.manifest.includedCategories.toSet(),
        )
        assertEquals("de-DE", preview.preparedArchive.manifest.locales.single())
        assertTrue(preview.migrationIssues.isEmpty())
        assertTrue(preview.archiveBytes.isNotEmpty())
        assertTrue(PersonalizationArchiveInspector.inspect(preview.archiveBytes).isValid)
    }

    @Test
    fun truncatedManualInventoryNeverClaimsCompleteExport() {
        val preview = LegacyPersonalizationExportPreview.prepareManualWords(
            inventory = LegacyPersonalDictionaryInventory(emptyList(), truncated = true),
            application = application(),
            createdAt = 10_000,
            exportIdFactory = fixedExportId(),
        )

        assertTrue(preview.isValid)
        assertFalse(preview.isComplete)
        assertTrue(preview.sourceTruncated)
    }

    @Test
    fun legacyPrivacyAndFrequencyChangesRemainVisible() {
        val item = LegacyManualWordInventoryItem(
            stableId = LegacyPersonalizationIds.manualWord(null, "ProjectName", null),
            locale = null,
            word = "ProjectName",
            shortcut = null,
            frequency = 999,
            appId = 12,
        )

        val preview = LegacyPersonalizationExportPreview.prepareManualWords(
            inventory = LegacyPersonalDictionaryInventory(listOf(item), truncated = false),
            application = application(),
            createdAt = 10_000,
            exportIdFactory = fixedExportId(),
        )

        assertEquals(255, preview.preparedArchive.data.manualWords.single().frequency)
        assertTrue(preview.migrationIssues.any { it.code == "frequency_clamped" })
        assertTrue(preview.migrationIssues.any { it.code == "legacy_app_id_not_exported" })
    }

    @Test
    fun userHistoryProducesSelfValidatedPortableArchive() {
        val word = "wahrscheinlich"
        val inventory = LegacyUserHistoryInventory(
            locale = "de-DE",
            words = listOf(
                LegacyUserHistoryWordInventoryItem(
                    stableId = LegacyPersonalizationIds.learnedWord("de-DE", word),
                    word = word,
                    evidence = historicalEvidence(
                        probability = 204,
                        timestampSeconds = 9,
                        count = 8,
                    ),
                    isNotAWord = false,
                    isPossiblyOffensive = false,
                    ngrams = listOf(
                        LegacyUserHistoryNgramInventoryItem(
                            stableId = LegacyPersonalizationIds.learnedNgram(
                                "de-DE",
                                listOf("sehr"),
                                word,
                            ),
                            contextTerms = listOf("sehr"),
                            targetWord = word,
                            evidence = historicalEvidence(
                                probability = 153,
                                timestampSeconds = 9,
                                count = 4,
                            ),
                        ),
                    ),
                ),
            ),
            truncated = false,
            complete = true,
        )

        val preview = LegacyPersonalizationExportPreview.prepareUserHistory(
            inventory = inventory,
            application = application(),
            createdAt = 10_000,
            exportIdFactory = fixedExportId(),
        )

        assertTrue(preview.isValid)
        assertTrue(preview.isComplete)
        assertTrue(preview.containsApproximations)
        assertEquals(1, preview.preparedArchive.data.learnedWords.size)
        assertEquals(1, preview.preparedArchive.data.learnedNgrams.size)
        assertEquals(
            setOf(
                PersonalizationCategory.LearnedWords,
                PersonalizationCategory.LearnedNgrams,
            ),
            preview.preparedArchive.manifest.includedCategories.toSet(),
        )
        assertTrue(preview.preparedArchive.manifest.privacy.containsNgrams)
        assertFalse(preview.preparedArchive.manifest.privacy.containsSentenceText)
        assertEquals("de-DE", preview.preparedArchive.manifest.locales.single())
        assertTrue(preview.archiveBytes.isNotEmpty())
        assertTrue(PersonalizationArchiveInspector.inspect(preview.archiveBytes).isValid)
    }

    @Test
    fun truncatedUserHistoryNeverClaimsCompleteExport() {
        val preview = LegacyPersonalizationExportPreview.prepareUserHistory(
            inventory = LegacyUserHistoryInventory(
                locale = "de-DE",
                words = emptyList(),
                truncated = true,
                complete = false,
            ),
            application = application(),
            createdAt = 10_000,
            exportIdFactory = fixedExportId(),
        )

        assertTrue(preview.isValid)
        assertFalse(preview.isComplete)
        assertTrue(preview.migration.sourceTruncated)
        assertFalse(preview.migration.sourceComplete)
    }

    private fun historicalEvidence(
        probability: Int,
        timestampSeconds: Int,
        count: Int,
    ): LegacyProbabilityEvidence {
        return LegacyProbabilityEvidence(
            probability = probability,
            hasHistoricalInfo = true,
            timestampRaw = timestampSeconds,
            levelRaw = 0,
            countRaw = count,
        )
    }

    private fun fixedExportId(): PersonalizationExportIdFactory {
        return PersonalizationExportIdFactory {
            "77777777-7777-4777-8777-777777777777"
        }
    }

    private fun application(): PersonalizationExportApplication {
        return PersonalizationExportApplication(
            id = "org.futo.inputmethod.latin",
            versionName = "0.0.0-test",
            versionCode = 1,
        )
    }
}
