package org.futo.inputmethod.latin.personalization

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import java.io.File
import org.futo.inputmethod.latin.Dictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class PersonalizationShadowModeTest {
    @Test
    fun reportsMissingPinnedManualCandidateWithoutRetainingRawWords() {
        val runtime = runtime(PersonalizationTestFixtures.validData())
        val event = PersonalizationShadowEvaluator.evaluate(
            runtime = runtime,
            observation = observation(
                typedWord = "FUTO",
                candidates = listOf(candidate("Foto", 0, Dictionary.TYPE_MAIN)),
            ),
            fingerprinter = ::testFingerprint,
        )

        assertTrue(PersonalizationShadowFinding.ManualPrefixCandidateMissing in event.findings)
        assertTrue(PersonalizationShadowFinding.PinnedTypedWordMissing in event.findings)
        assertEquals(1, event.manualPrefixCandidateCount)
        assertEquals(0, event.manualPrefixCandidatesVisible)
        assertEquals(WordRuleAction.Pin, event.wordRuleAction)
        assertFalse(event.toString().contains("FUTO"))
        assertFalse(event.toString().contains("Foto"))
    }

    @Test
    fun reportsBlockedAutocorrectAtRankOneAndMissingGlobalPreference() {
        val base = PersonalizationTestFixtures.validData()
        val data = base.copy(
            correctionRules = base.correctionRules + listOf(
                CorrectionRuleRecord(
                    id = "99999999-9999-4999-8999-999999999999",
                    revision = 1,
                    createdAt = 8_000,
                    updatedAt = 8_000,
                    locale = "de-DE",
                    typed = "im",
                    replacement = "ihm",
                    action = CorrectionRuleAction.BlockAutocorrect,
                ),
                CorrectionRuleRecord(
                    id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                    revision = 1,
                    createdAt = 8_000,
                    updatedAt = 8_000,
                    locale = null,
                    typed = "im",
                    replacement = "immer",
                    action = CorrectionRuleAction.Prefer,
                ),
            ),
        )
        val event = PersonalizationShadowEvaluator.evaluate(
            runtime = runtime(data),
            observation = observation(
                typedWord = "im",
                candidates = listOf(
                    candidate("ihm", 0, Dictionary.TYPE_MAIN),
                    candidate("im", 1, Dictionary.TYPE_MAIN),
                ),
            ),
            fingerprinter = ::testFingerprint,
        )

        assertTrue(
            PersonalizationShadowFinding.BlockedAutocorrectCandidateRankedFirst in event.findings,
        )
        assertFalse(PersonalizationShadowFinding.BlockedSuggestionVisible in event.findings)
        assertTrue(PersonalizationShadowFinding.PreferredCorrectionMissing in event.findings)
        assertEquals(1, event.blockedAutocorrectCandidateCount)
        assertEquals(0, event.blockedSuggestionCount)
        assertEquals(1, event.preferredCorrectionCount)
    }

    @Test
    fun reportsSuggestionThatShouldHaveBeenHidden() {
        val base = PersonalizationTestFixtures.validData()
        val data = base.copy(
            correctionRules = base.correctionRules + CorrectionRuleRecord(
                id = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                revision = 1,
                createdAt = 8_000,
                updatedAt = 8_000,
                locale = "de-DE",
                typed = "seid",
                replacement = "seit",
                action = CorrectionRuleAction.BlockSuggestion,
            ),
        )
        val event = PersonalizationShadowEvaluator.evaluate(
            runtime = runtime(data),
            observation = observation(
                typedWord = "seid",
                candidates = listOf(candidate("seit", 2, Dictionary.TYPE_MAIN)),
            ),
            fingerprinter = ::testFingerprint,
        )

        assertTrue(PersonalizationShadowFinding.BlockedSuggestionVisible in event.findings)
        assertEquals(1, event.blockedSuggestionCount)
        assertEquals(0, event.blockedAutocorrectCandidateCount)
    }

    @Test
    fun distinguishesPortableLearnedEvidenceFromProductionHistoryVisibility() {
        val runtime = runtime(PersonalizationTestFixtures.validData())
        val missing = PersonalizationShadowEvaluator.evaluate(
            runtime = runtime,
            observation = observation(
                typedWord = "wahrscheinlich",
                candidates = listOf(candidate("warscheinlich", 0, Dictionary.TYPE_MAIN)),
            ),
            fingerprinter = ::testFingerprint,
        )
        val visible = PersonalizationShadowEvaluator.evaluate(
            runtime = runtime,
            observation = observation(
                typedWord = "wahrscheinlich",
                candidates = listOf(
                    candidate("wahrscheinlich", 0, Dictionary.TYPE_USER_HISTORY),
                ),
            ),
            fingerprinter = ::testFingerprint,
        )

        assertTrue(missing.learnedTypedWordPresent)
        assertFalse(missing.productionHistoryTypedWordVisible)
        assertTrue(PersonalizationShadowFinding.LearnedTypedWordMissing in missing.findings)
        assertTrue(visible.productionHistoryTypedWordVisible)
        assertFalse(PersonalizationShadowFinding.LearnedTypedWordMissing in visible.findings)
    }

    private fun runtime(data: PersonalizationDataSet): PersonalizationRuntimeSnapshot {
        return PersonalizationRuntimeSnapshotCompiler.compile(
            PersonalizationStoreSnapshot(
                generation = PersonalizationStoreGeneration(
                    storeVersion = "0.1",
                    generation = 1,
                    generationId = "00000000000000000001-000000000001",
                    committedAt = 10_000,
                    reason = PersonalizationStoreCommitReason.Migration,
                    dataSha256 = "0".repeat(64),
                    dataSizeBytes = 0,
                ),
                data = data,
                generationDirectory = File("."),
            ),
        )
    }

    private fun observation(
        typedWord: String,
        candidates: List<PersonalizationShadowCandidate>,
    ) = PersonalizationShadowObservation(
        observedAt = 20_000,
        locale = "de-DE",
        typedWord = typedWord,
        candidates = candidates,
        inputStyle = 1,
        sessionId = 0,
    )

    private fun candidate(
        word: String,
        rank: Int,
        sourceType: String,
    ) = PersonalizationShadowCandidate(
        word = word,
        score = 1_000 - rank,
        sourceType = sourceType,
        rank = rank,
    )

    private fun testFingerprint(value: String) = PersonalizationShadowFingerprint(
        hash = "fp-${value.codePointCount(0, value.length)}",
        codePointLength = value.codePointCount(0, value.length),
    )
}
