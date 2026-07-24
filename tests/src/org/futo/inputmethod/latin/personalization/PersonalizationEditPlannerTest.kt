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
class PersonalizationEditPlannerTest {
    private val fixedIds = object : PersonalizationIdFactory {
        private val values = ArrayDeque(
            listOf(
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            ),
        )

        override fun newId(): String = values.removeFirst()
    }

    @Test
    fun forgetRemovesWordAndRelatedNgramsAndCreatesTombstones() {
        val word = learnedWord()
        val related = learnedNgram(
            id = "22222222-2222-4222-8222-222222222222",
            terms = listOf("sehr", "wahrscheinlich"),
        )
        val unrelated = learnedNgram(
            id = "33333333-3333-4333-8333-333333333333",
            terms = listOf("ganz", "sicher"),
        )
        val input = data(
            learnedWords = listOf(word),
            learnedNgrams = listOf(related, unrelated),
        )

        val result = PersonalizationEditPlanner.forgetWord(
            input,
            locale = "de-DE",
            word = "WAHRSCHEINLICH",
            editedAt = 10_000,
        )

        assertTrue(result.changed)
        assertTrue(result.data.learnedWords.isEmpty())
        assertEquals(listOf(unrelated), result.data.learnedNgrams)
        assertEquals(2, result.data.tombstones.size)
        assertTrue(result.data.tombstones.all {
            it.reason == PersonalizationDeletionReason.UserDelete
        })
        assertTrue(PersonalizationDataValidator.validateData(result.data).isValid)
    }

    @Test
    fun neverLearnAddsRuleAndOnlyRelabelsNewTombstones() {
        val unrelatedTombstone = PersonalizationTombstone(
            targetId = "99999999-9999-4999-8999-999999999999",
            targetKind = PersonalizationRecordKind.LearnedWord,
            revision = 2,
            deletedAt = 5_000,
            reason = PersonalizationDeletionReason.UserDelete,
        )
        val input = data(
            learnedWords = listOf(learnedWord()),
            tombstones = listOf(unrelatedTombstone),
        )

        val result = PersonalizationEditPlanner.neverLearnWord(
            input,
            locale = "de-DE",
            word = "wahrscheinlich",
            editedAt = 10_000,
            idFactory = fixedIds,
        )

        assertTrue(result.data.learnedWords.isEmpty())
        assertEquals(WordRuleAction.DoNotLearn, result.data.wordRules.single().action)
        assertEquals(
            PersonalizationDeletionReason.UserDelete,
            result.data.tombstones.first { it.targetId == unrelatedTombstone.targetId }.reason,
        )
        assertEquals(
            PersonalizationDeletionReason.NeverLearn,
            result.data.tombstones.first { it.targetId == learnedWord().id }.reason,
        )
        assertTrue(PersonalizationDataValidator.validateData(result.data).isValid)
    }

    @Test
    fun globalNeverLearnRemovesMatchingEvidenceAcrossLocales() {
        val german = learnedWord()
        val austrian = german.copy(
            id = "44444444-4444-4444-8444-444444444444",
            locale = "de-AT",
        )

        val result = PersonalizationEditPlanner.neverLearnWord(
            data(learnedWords = listOf(german, austrian)),
            locale = null,
            word = "wahrscheinlich",
            editedAt = 10_000,
            idFactory = fixedIds,
        )

        assertTrue(result.data.learnedWords.isEmpty())
        assertEquals(null, result.data.wordRules.single().locale)
        assertEquals(2, result.data.tombstones.size)
    }

    @Test
    fun pinCreatesManualWordAndAuthoritativeRule() {
        val result = PersonalizationEditPlanner.pinWord(
            data(),
            locale = "de-DE",
            word = "FUTO",
            shortcut = "futo",
            editedAt = 10_000,
            idFactory = fixedIds,
        )

        val manual = result.data.manualWords.single()
        assertEquals("FUTO", manual.word)
        assertEquals("futo", manual.shortcut)
        assertEquals(ManualWordSource.Manual, manual.source)
        assertEquals(WordRuleAction.Pin, result.data.wordRules.single().action)
        assertTrue(PersonalizationDataValidator.validateData(result.data).isValid)
    }

    @Test
    fun pinUpdatesExistingManualWordWithoutCreatingDuplicate() {
        val existing = ManualWordRecord(
            id = "55555555-5555-4555-8555-555555555555",
            revision = 3,
            createdAt = 1_000,
            updatedAt = 2_000,
            locale = "de-DE",
            word = "FUTO",
            shortcut = null,
            frequency = 100,
            source = ManualWordSource.Imported,
        )

        val result = PersonalizationEditPlanner.pinWord(
            data(manualWords = listOf(existing)),
            locale = "DE-de",
            word = "futo",
            frequency = 250,
            editedAt = 10_000,
            idFactory = fixedIds,
        )

        val updated = result.data.manualWords.single()
        assertEquals(existing.id, updated.id)
        assertEquals(4, updated.revision)
        assertEquals(250, updated.frequency)
        assertEquals(ManualWordSource.Manual, updated.source)
    }

    @Test
    fun correctionPairRuleCanBeAddedAndUpdated() {
        val added = PersonalizationEditPlanner.setCorrectionRule(
            data(),
            locale = "de-DE",
            typed = "im",
            replacement = "ihm",
            action = CorrectionRuleAction.BlockAutocorrect,
            editedAt = 10_000,
            idFactory = fixedIds,
        )
        val first = added.data.correctionRules.single()
        assertEquals(CorrectionRuleAction.BlockAutocorrect, first.action)

        val updated = PersonalizationEditPlanner.setCorrectionRule(
            added.data,
            locale = "DE-de",
            typed = "IM",
            replacement = "IHM",
            action = CorrectionRuleAction.BlockSuggestion,
            editedAt = 11_000,
            idFactory = fixedIds,
        )
        val second = updated.data.correctionRules.single()

        assertEquals(first.id, second.id)
        assertEquals(first.revision + 1, second.revision)
        assertEquals(CorrectionRuleAction.BlockSuggestion, second.action)
        assertTrue(PersonalizationDataValidator.validateData(updated.data).isValid)
    }

    @Test
    fun forgettingUnknownWordIsNoOp() {
        val input = data(learnedWords = listOf(learnedWord()))

        val result = PersonalizationEditPlanner.forgetWord(
            input,
            locale = "de-DE",
            word = "unbekannt",
            editedAt = 10_000,
        )

        assertFalse(result.changed)
        assertEquals(input, result.data)
    }

    private fun learnedWord(): LearnedWordRecord {
        return LearnedWordRecord(
            id = "11111111-1111-4111-8111-111111111111",
            revision = 2,
            createdAt = 1_000,
            updatedAt = 5_000,
            locale = "de-DE",
            word = "wahrscheinlich",
            observationCount = 8,
            firstSeenAt = 1_500,
            lastSeenAt = 5_000,
            confidence = 0.8,
            state = LearnedRecordState.Active,
            source = LearnedRecordSource.Typing,
        )
    }

    private fun learnedNgram(id: String, terms: List<String>): LearnedNgramRecord {
        return LearnedNgramRecord(
            id = id,
            revision = 1,
            createdAt = 1_000,
            updatedAt = 5_000,
            locale = "de-DE",
            terms = terms,
            observationCount = 4,
            firstSeenAt = 1_500,
            lastSeenAt = 5_000,
            confidence = 0.7,
            state = LearnedRecordState.Active,
            source = LearnedRecordSource.Typing,
        )
    }

    private fun data(
        manualWords: List<ManualWordRecord> = emptyList(),
        learnedWords: List<LearnedWordRecord> = emptyList(),
        learnedNgrams: List<LearnedNgramRecord> = emptyList(),
        tombstones: List<PersonalizationTombstone> = emptyList(),
    ): PersonalizationDataSet {
        return PersonalizationDataSet(
            formatVersion = PERSONALIZATION_FORMAT_VERSION,
            manualWords = manualWords,
            learnedWords = learnedWords,
            learnedNgrams = learnedNgrams,
            tombstones = tombstones,
        )
    }
}
