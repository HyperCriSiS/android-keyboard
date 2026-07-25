package org.futo.inputmethod.latin.languagepack.fusion

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CandidateFusionTest {
    private val policy = CandidateFusionPolicy(
        calibration = CandidateFusionCalibration(rankerCenter = -4.0, rankerScale = 1.0),
    )

    @Test
    fun strongCorrectionWinsAndCanBeAutocorrected() {
        val fused = ExperimentalCandidateFusion.fuse(
            listOf(
                typed("warscheinlich", generator = 0.35, ranker = -6.0),
                correction(
                    id = "wahrscheinlich",
                    generator = 0.94,
                    ranker = -2.0,
                    editRisk = 0.18,
                    frequency = 0.9,
                ),
                correction(
                    id = "anscheinlich",
                    generator = 0.4,
                    ranker = -4.7,
                    editRisk = 0.5,
                ),
            ),
            policy,
        )

        assertEquals("wahrscheinlich", fused.first().input.id)
        val decision = ExperimentalCandidateFusion.decideAutocorrect(fused)
        assertEquals(AutocorrectDecisionReason.Correct, decision.reason)
        assertEquals("wahrscheinlich", decision.correction?.input?.id)
    }

    @Test
    fun validTypedWordIsKeptWhenItRanksFirst() {
        val fused = ExperimentalCandidateFusion.fuse(
            listOf(
                typed("seit", generator = 0.92, ranker = -2.1, exact = true),
                correction("seid", generator = 0.72, ranker = -2.6, editRisk = 0.3),
            ),
            policy,
        )

        val decision = ExperimentalCandidateFusion.decideAutocorrect(fused)

        assertEquals("seit", fused.first().input.id)
        assertEquals(AutocorrectDecisionReason.KeepTypedTop, decision.reason)
        assertNull(decision.correction)
    }

    @Test
    fun closeTypedMarginPreventsAggressiveCorrection() {
        val fused = ExperimentalCandidateFusion.fuse(
            listOf(
                typed("im", generator = 0.76, ranker = -2.8, exact = true),
                correction("ihm", generator = 0.82, ranker = -2.5, editRisk = 0.2),
            ),
            policy,
        )
        assertEquals("ihm", fused.first().input.id)

        val decision = ExperimentalCandidateFusion.decideAutocorrect(
            fused,
            AutocorrectPolicy(
                minimumCandidateConfidence = 0.5,
                minimumTypedMargin = 0.20,
                minimumRunnerUpMargin = 0.0,
                maximumEditRisk = 1.0,
            ),
        )

        assertEquals(AutocorrectDecisionReason.TypedMarginTooSmall, decision.reason)
        assertNull(decision.correction)
    }

    @Test
    fun highEditRiskBlockedAndOffensiveCandidatesAreNeverAutocorrected() {
        val base = listOf(
            typed("word", generator = 0.1, ranker = -8.0),
            correction("candidate", generator = 0.99, ranker = -1.0, editRisk = 0.95),
        )
        val risky = ExperimentalCandidateFusion.decideAutocorrect(
            ExperimentalCandidateFusion.fuse(base, policy),
            permissiveAutocorrect().copy(maximumEditRisk = 0.5),
        )
        assertEquals(AutocorrectDecisionReason.EditRiskTooHigh, risky.reason)

        val blocked = ExperimentalCandidateFusion.decideAutocorrect(
            ExperimentalCandidateFusion.fuse(
                base.map { if (it.id == "candidate") it.copy(isBlocked = true) else it },
                policy,
            ),
            permissiveAutocorrect(),
        )
        assertEquals(AutocorrectDecisionReason.CandidateBlocked, blocked.reason)

        val offensive = ExperimentalCandidateFusion.decideAutocorrect(
            ExperimentalCandidateFusion.fuse(
                base.map { if (it.id == "candidate") it.copy(isPossiblyOffensive = true) else it },
                policy,
            ),
            permissiveAutocorrect(),
        )
        assertEquals(AutocorrectDecisionReason.CandidateOffensive, offensive.reason)
    }

    @Test
    fun generatorRankerAgreementCanBeRequired() {
        val fused = ExperimentalCandidateFusion.fuse(
            listOf(
                typed("typed", generator = 0.1, ranker = -8.0),
                correction("generator-top", generator = 0.99, ranker = -5.0, editRisk = 0.1),
                correction("ranker-top", generator = 0.7, ranker = -1.0, editRisk = 0.1),
            ),
            CandidateFusionPolicy(
                calibration = CandidateFusionCalibration(-4.0, 1.0),
                weights = CandidateFusionWeights(generator = 1.0, ranker = 2.0),
            ),
        )
        assertEquals("ranker-top", fused.first().input.id)

        val decision = ExperimentalCandidateFusion.decideAutocorrect(
            fused,
            permissiveAutocorrect().copy(requireGeneratorAndRankerAgreement = true),
        )

        assertEquals(AutocorrectDecisionReason.GeneratorRankerDisagreement, decision.reason)
    }

    @Test
    fun evidenceSumsToLinearScoreAndConfidenceIsBounded() {
        val fused = ExperimentalCandidateFusion.fuse(
            listOf(
                correction(
                    id = "candidate",
                    generator = 0.8,
                    ranker = -3.0,
                    editRisk = 0.25,
                    frequency = 0.7,
                ).copy(
                    touchConfidence = 0.6,
                    personalizationConfidence = 0.9,
                    isExactDictionaryMatch = true,
                ),
            ),
            policy,
        ).single()
        val evidence = fused.evidence
        val sum = evidence.generator + evidence.ranker + evidence.touch + evidence.frequency +
            evidence.personalization - evidence.editRiskPenalty +
            evidence.exactDictionaryBonus + evidence.typedTextBias

        assertEquals(sum, fused.linearScore, 0.0000001)
        assertTrue(fused.confidence in 0.0..1.0)
    }

    private fun typed(
        text: String,
        generator: Double,
        ranker: Double,
        exact: Boolean = false,
    ): CandidateFusionInput {
        return CandidateFusionInput(
            id = text,
            text = text,
            isTypedText = true,
            generatorConfidence = generator,
            editRisk = 0.0,
            rankerScore = ranker,
            frequencyConfidence = generator,
            isExactDictionaryMatch = exact,
        )
    }

    private fun correction(
        id: String,
        generator: Double,
        ranker: Double,
        editRisk: Double,
        frequency: Double? = null,
    ): CandidateFusionInput {
        return CandidateFusionInput(
            id = id,
            text = id,
            isTypedText = false,
            generatorConfidence = generator,
            editRisk = editRisk,
            rankerScore = ranker,
            frequencyConfidence = frequency,
        )
    }

    private fun permissiveAutocorrect(): AutocorrectPolicy {
        return AutocorrectPolicy(
            minimumCandidateConfidence = 0.0,
            minimumTypedMargin = 0.0,
            minimumRunnerUpMargin = 0.0,
            maximumEditRisk = 1.0,
        )
    }
}
