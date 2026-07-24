package org.futo.inputmethod.latin.personalization

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import java.util.ArrayList
import org.futo.inputmethod.latin.makedict.ProbabilityInfo
import org.futo.inputmethod.latin.makedict.WeightedString
import org.futo.inputmethod.latin.makedict.WordProperty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class LegacyPersonalizationInventoryTest {
    @Test
    fun deterministicIdsNormalizeLocaleCaseAndUnicode() {
        val first = LegacyPersonalizationIds.manualWord("de-DE", "Café", null)
        val second = LegacyPersonalizationIds.manualWord("DE-de", "Cafe\u0301", null)
        val different = LegacyPersonalizationIds.manualWord("de-DE", "Café", "cafe")

        assertEquals(first, second)
        assertFalse(first == different)
    }

    @Test
    fun manualMigrationPreservesWordsAndReportsPrivacyChanges() {
        val item = LegacyManualWordInventoryItem(
            stableId = LegacyPersonalizationIds.manualWord("de-DE", "FUTO", "futo"),
            locale = "de-DE",
            word = "FUTO",
            shortcut = "futo",
            frequency = 300,
            appId = 42,
        )
        val result = LegacyManualWordMigration.convert(
            LegacyPersonalDictionaryInventory(listOf(item), truncated = false),
            migratedAt = 5_000,
        )

        val record = result.records.single()
        assertEquals(item.stableId, record.id)
        assertEquals("FUTO", record.word)
        assertEquals(255, record.frequency)
        assertEquals(ManualWordSource.MigratedAndroidUserDictionary, record.source)
        assertTrue(result.issues.any { it.code == "frequency_clamped" })
        assertTrue(result.issues.any { it.code == "legacy_app_id_not_exported" })
        assertTrue(
            PersonalizationDataValidator.validateData(
                PersonalizationDataSet(
                    formatVersion = PERSONALIZATION_FORMAT_VERSION,
                    manualWords = result.records,
                ),
            ).isValid,
        )
    }

    @Test
    fun userHistoryMapperKeepsRawEvidenceOpaque() {
        val bigrams = ArrayList<WeightedString>().apply {
            add(
                WeightedString(
                    "richtig",
                    ProbabilityInfo(190, 1234, 2, 7),
                ),
            )
        }
        val property = WordProperty(
            "wahrscheinlich",
            ProbabilityInfo(210, 4567, 3, 11),
            bigrams,
            false,
            false,
        )

        val inventory = LegacyUserHistoryInventoryMapper.map(
            locale = "de-DE",
            wordProperties = listOf(property),
        )

        val word = inventory.words.single()
        assertEquals("wahrscheinlich", word.word)
        assertEquals(210, word.evidence.probability)
        assertEquals(4567, word.evidence.timestampRaw)
        assertEquals(3, word.evidence.levelRaw)
        assertEquals(11, word.evidence.countRaw)
        assertTrue(word.evidence.hasHistoricalInfo)
        assertTrue(inventory.rawSemanticsWarning.contains("implementation details"))

        val ngram = word.ngrams.single()
        assertEquals(listOf("wahrscheinlich"), ngram.contextTerms)
        assertEquals("richtig", ngram.targetWord)
        assertEquals(190, ngram.evidence.probability)
    }

    @Test
    fun historyWithoutHistoricalInfoDoesNotExposeSentinelValues() {
        val property = WordProperty(
            "FUTO",
            ProbabilityInfo(180),
            null,
            false,
            false,
        )

        val evidence = LegacyUserHistoryInventoryMapper.map(
            "de-DE",
            listOf(property),
        ).words.single().evidence

        assertFalse(evidence.hasHistoricalInfo)
        assertEquals(null, evidence.timestampRaw)
        assertEquals(null, evidence.levelRaw)
        assertEquals(null, evidence.countRaw)
    }
}
