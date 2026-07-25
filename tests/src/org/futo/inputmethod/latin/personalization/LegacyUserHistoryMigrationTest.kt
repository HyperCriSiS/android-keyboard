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
class LegacyUserHistoryMigrationTest {
    @Test
    fun retainedEvidenceMapsToPortableWordAndNgram() {
        val wordId = LegacyPersonalizationIds.learnedWord("de-DE", "wahrscheinlich")
        val ngramId = LegacyPersonalizationIds.learnedNgram(
            "de-DE",
            listOf("sehr"),
            "wahrscheinlich",
        )
        val inventory = LegacyUserHistoryInventory(
            locale = "de-DE",
            words = listOf(
                LegacyUserHistoryWordInventoryItem(
                    stableId = wordId,
                    word = "wahrscheinlich",
                    evidence = evidence(
                        probability = 204,
                        timestampSeconds = 1_700_000_000,
                        level = 0,
                        count = 8,
                    ),
                    isNotAWord = false,
                    isPossiblyOffensive = false,
                    ngrams = listOf(
                        LegacyUserHistoryNgramInventoryItem(
                            stableId = ngramId,
                            contextTerms = listOf("sehr"),
                            targetWord = "wahrscheinlich",
                            evidence = evidence(
                                probability = 153,
                                timestampSeconds = 1_700_000_010,
                                level = 0,
                                count = 4,
                            ),
                        ),
                    ),
                ),
            ),
            truncated = false,
            complete = true,
        )

        val migration = LegacyUserHistoryMigration.convert(
            inventory = inventory,
            migratedAt = 1_800_000_000_000,
        )

        assertEquals(LEGACY_USER_HISTORY_MAPPING_VERSION, migration.mappingVersion)
        assertTrue(migration.isComplete)
        assertTrue(migration.containsApproximations)
        assertEquals(0, migration.rejectedWordCount)
        assertEquals(0, migration.rejectedNgramCount)

        val word = migration.data.learnedWords.single()
        assertEquals(wordId, word.id)
        assertEquals("de-DE", word.locale)
        assertEquals("wahrscheinlich", word.word)
        assertEquals(8L, word.observationCount)
        assertEquals(1_700_000_000_000, word.firstSeenAt)
        assertEquals(1_700_000_000_000, word.lastSeenAt)
        assertEquals(0.8, word.confidence, 0.000001)
        assertEquals(LearnedRecordState.Active, word.state)
        assertEquals(LearnedRecordSource.MigratedUserHistory, word.source)

        val ngram = migration.data.learnedNgrams.single()
        assertEquals(ngramId, ngram.id)
        assertEquals(listOf("sehr", "wahrscheinlich"), ngram.terms)
        assertEquals(4L, ngram.observationCount)
        assertEquals(1_700_000_010_000, ngram.firstSeenAt)
        assertEquals(1_700_000_010_000, ngram.lastSeenAt)
        assertEquals(0.6, ngram.confidence, 0.000001)

        assertEquals(
            2,
            migration.issues.single { it.code == "first_seen_approximated" }.count,
        )
        assertTrue(PersonalizationDataValidator.validateData(migration.data).isValid)
    }

    @Test
    fun missingHistoricalInfoUsesExplicitConservativeFallback() {
        val migratedAt = 20_000L
        val inventory = inventory(
            word = "FUTO",
            evidence = LegacyProbabilityEvidence(
                probability = 128,
                hasHistoricalInfo = false,
                timestampRaw = null,
                levelRaw = null,
                countRaw = null,
            ),
        )

        val migration = LegacyUserHistoryMigration.convert(inventory, migratedAt)
        val word = migration.data.learnedWords.single()

        assertEquals(1L, word.observationCount)
        assertEquals(migratedAt, word.firstSeenAt)
        assertEquals(migratedAt, word.lastSeenAt)
        assertEquals(128.0 / 255.0, word.confidence, 0.000001)
        assertTrue(migration.containsApproximations)
        assertEquals(1, migration.issues.single { it.code == "missing_historical_evidence" }.count)
        assertEquals(1, migration.issues.single { it.code == "first_seen_approximated" }.count)
    }

    @Test
    fun invalidEvidenceIsClampedAndReportedWithoutInventingPrecision() {
        val migratedAt = 10_000L
        val inventory = inventory(
            word = "Projektname",
            evidence = evidence(
                probability = 999,
                timestampSeconds = 20,
                level = 7,
                count = 0,
            ),
        )

        val migration = LegacyUserHistoryMigration.convert(inventory, migratedAt)
        val word = migration.data.learnedWords.single()

        assertEquals(1L, word.observationCount)
        assertEquals(migratedAt, word.firstSeenAt)
        assertEquals(migratedAt, word.lastSeenAt)
        assertEquals(1.0, word.confidence, 0.0)
        assertTrue(migration.issues.any { it.code == "invalid_legacy_count" })
        assertTrue(migration.issues.any { it.code == "invalid_legacy_timestamp" })
        assertTrue(migration.issues.any { it.code == "invalid_legacy_probability" })
        assertTrue(migration.issues.any { it.code == "legacy_level_ignored" })
    }

    @Test
    fun legacyNotAWordBecomesSuppressedEvidence() {
        val base = inventory(word = "Vertipper", evidence = evidence(count = 3))
        val inventory = base.copy(
            words = listOf(base.words.single().copy(isNotAWord = true)),
        )

        val migration = LegacyUserHistoryMigration.convert(inventory, migratedAt = 20_000L)

        assertEquals(LearnedRecordState.Suppressed, migration.data.learnedWords.single().state)
        assertTrue(PersonalizationDataValidator.validateData(migration.data).isValid)
    }

    @Test
    fun invalidNgramIsRejectedAndPreventsCompleteClaim() {
        val base = inventory(word = "Ziel", evidence = evidence(count = 2))
        val word = base.words.single().copy(
            ngrams = listOf(
                LegacyUserHistoryNgramInventoryItem(
                    stableId = LegacyPersonalizationIds.learnedNgram(
                        "de-DE",
                        listOf("eins", "zwei", "drei", "vier"),
                        "Ziel",
                    ),
                    contextTerms = listOf("eins", "zwei", "drei", "vier"),
                    targetWord = "Ziel",
                    evidence = evidence(count = 2),
                ),
            ),
        )

        val migration = LegacyUserHistoryMigration.convert(
            inventory = base.copy(words = listOf(word)),
            migratedAt = 20_000L,
        )

        assertFalse(migration.isComplete)
        assertEquals(1, migration.rejectedNgramCount)
        assertTrue(migration.data.learnedNgrams.isEmpty())
        assertEquals(1, migration.issues.single { it.code == "invalid_ngram_rejected" }.count)
    }

    @Test
    fun equivalentStableIdsAreCollapsedDeterministically() {
        val base = inventory(word = "Duplikat", evidence = evidence(count = 5))
        val duplicate = base.words.single().copy()

        val migration = LegacyUserHistoryMigration.convert(
            inventory = base.copy(words = listOf(base.words.single(), duplicate)),
            migratedAt = 20_000L,
        )

        assertTrue(migration.isComplete)
        assertEquals(1, migration.data.learnedWords.size)
        assertEquals(1, migration.issues.single { it.code == "duplicate_word_collapsed" }.count)
    }

    @Test
    fun truncatedSourceNeverClaimsCompleteMigration() {
        val migration = LegacyUserHistoryMigration.convert(
            inventory = inventory(
                word = "unvollständig",
                evidence = evidence(count = 2),
            ).copy(truncated = true, complete = false),
            migratedAt = 20_000L,
        )

        assertFalse(migration.isComplete)
        assertTrue(migration.sourceTruncated)
        assertFalse(migration.sourceComplete)
    }

    private fun inventory(
        word: String,
        evidence: LegacyProbabilityEvidence,
    ): LegacyUserHistoryInventory {
        return LegacyUserHistoryInventory(
            locale = "de-DE",
            words = listOf(
                LegacyUserHistoryWordInventoryItem(
                    stableId = LegacyPersonalizationIds.learnedWord("de-DE", word),
                    word = word,
                    evidence = evidence,
                    isNotAWord = false,
                    isPossiblyOffensive = false,
                    ngrams = emptyList(),
                ),
            ),
            truncated = false,
            complete = true,
        )
    }

    private fun evidence(
        probability: Int = 192,
        timestampSeconds: Int = 10,
        level: Int = 0,
        count: Int = 1,
    ): LegacyProbabilityEvidence {
        return LegacyProbabilityEvidence(
            probability = probability,
            hasHistoricalInfo = true,
            timestampRaw = timestampSeconds,
            levelRaw = level,
            countRaw = count,
        )
    }
}
