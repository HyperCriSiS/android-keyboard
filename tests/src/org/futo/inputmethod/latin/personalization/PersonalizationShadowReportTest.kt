package org.futo.inputmethod.latin.personalization

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import org.futo.inputmethod.latin.Dictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class PersonalizationShadowReportTest {
    @Test
    fun aggregatesEventsWithoutWordsOrFingerprints() {
        val accumulator = PersonalizationShadowReportAccumulator(startedAt = 1_000)
        accumulator.record(
            event(
                observedAt = 2_000,
                locale = "de-de",
                generationId = "generation-a",
                dataSha256 = "0".repeat(64),
                typedHash = "typed-hash-secret",
                topHash = "candidate-hash-secret",
                topSource = Dictionary.TYPE_MAIN,
                candidateCount = 3,
                manualCount = 2,
                manualVisible = 1,
                learned = true,
                historyVisible = false,
                findings = setOf(
                    PersonalizationShadowFinding.ManualPrefixCandidateMissing,
                    PersonalizationShadowFinding.LearnedTypedWordMissing,
                ),
                evaluationMicros = 75,
                inputStyle = 1,
            ),
        )
        accumulator.record(
            event(
                observedAt = 3_000,
                locale = "en-us",
                generationId = "generation-b",
                dataSha256 = "1".repeat(64),
                typedHash = "another-typed-secret",
                topHash = "another-candidate-secret",
                topSource = Dictionary.TYPE_USER_HISTORY,
                candidateCount = 1,
                manualCount = 0,
                manualVisible = 0,
                learned = true,
                historyVisible = true,
                findings = emptySet(),
                evaluationMicros = 1_200,
                inputStyle = 2,
            ),
        )

        val report = accumulator.build(
            generatedAt = 5_000,
            currentRuntime = PersonalizationShadowReportRuntime(
                generationId = "generation-b",
                dataSha256 = "1".repeat(64),
            ),
            droppedQueueItems = 1,
            skippedWithoutRuntime = 2,
            retainedRecentEvents = 2,
        )
        val encoded = PersonalizationShadowReportCodec.encode(report)

        assertEquals(2L, report.counters.evaluatedEvents)
        assertEquals(1L, report.counters.droppedQueueItems)
        assertEquals(1.0 / 3.0, report.counters.queueDropRate, 0.000001)
        assertEquals(4L, report.candidateStats.totalCandidates)
        assertEquals(2.0, report.candidateStats.averageCandidates, 0.000001)
        assertEquals(0.5, report.candidateStats.manualPrefixVisibilityRate, 0.000001)
        assertEquals(0.5, report.candidateStats.learnedHistoryVisibilityRate, 0.000001)
        assertEquals(2L, report.latency.samples)
        assertEquals(1_275L, report.latency.totalMicros)
        assertEquals(1_200L, report.latency.maximumMicros)
        assertEquals(2L, report.latency.buckets.sumOf { it.count })
        assertEquals(2, report.runtimeGenerations.size)
        assertFalse(encoded.contains("typed-hash-secret"))
        assertFalse(encoded.contains("candidate-hash-secret"))
        assertFalse(encoded.contains("another-typed-secret"))
        assertFalse(encoded.contains("another-candidate-secret"))
        assertFalse(report.privacy.containsFingerprints)
        assertFalse(report.privacy.containsTypedWords)
    }

    @Test
    fun reportCodecRoundTripsValidatedAggregate() {
        val accumulator = PersonalizationShadowReportAccumulator(startedAt = 1_000)
        accumulator.record(
            event(
                observedAt = 2_000,
                locale = "de-de",
                generationId = "generation-a",
                dataSha256 = "0".repeat(64),
                typedHash = "not-exported",
                topHash = null,
                topSource = null,
                candidateCount = 0,
                manualCount = 0,
                manualVisible = 0,
                learned = false,
                historyVisible = false,
                findings = setOf(PersonalizationShadowFinding.PreferredCorrectionMissing),
                evaluationMicros = 25,
                inputStyle = 1,
            ),
        )
        val report = accumulator.build(
            generatedAt = 3_000,
            currentRuntime = null,
            droppedQueueItems = 0,
            skippedWithoutRuntime = 0,
            retainedRecentEvents = 1,
        )

        val decoded = PersonalizationShadowReportCodec.decode(
            PersonalizationShadowReportCodec.encode(report),
        )

        assertEquals(report, decoded)
        assertTrue(PersonalizationShadowReportValidator.validate(decoded).isEmpty())
    }

    @Test
    fun validatorRejectsFingerprintDeclarationAndInvalidLatencyBuckets() {
        val accumulator = PersonalizationShadowReportAccumulator(startedAt = 1_000)
        val valid = accumulator.build(
            generatedAt = 2_000,
            currentRuntime = null,
            droppedQueueItems = 0,
            skippedWithoutRuntime = 0,
            retainedRecentEvents = 0,
        )
        val invalidPrivacy = valid.copy(
            privacy = valid.privacy.copy(containsFingerprints = true),
        )
        val invalidBuckets = valid.copy(
            latency = valid.latency.copy(
                samples = 1,
                buckets = valid.latency.buckets,
            ),
        )

        assertTrue(PersonalizationShadowReportValidator.validate(invalidPrivacy).isNotEmpty())
        assertTrue(PersonalizationShadowReportValidator.validate(invalidBuckets).isNotEmpty())
    }

    private fun event(
        observedAt: Long,
        locale: String,
        generationId: String,
        dataSha256: String,
        typedHash: String,
        topHash: String?,
        topSource: String?,
        candidateCount: Int,
        manualCount: Int,
        manualVisible: Int,
        learned: Boolean,
        historyVisible: Boolean,
        findings: Set<PersonalizationShadowFinding>,
        evaluationMicros: Long,
        inputStyle: Int,
    ) = PersonalizationShadowEvent(
        observedAt = observedAt,
        locale = locale,
        runtimeGenerationId = generationId,
        runtimeDataSha256 = dataSha256,
        typedWord = PersonalizationShadowFingerprint(typedHash, 4),
        productionTopCandidate = topHash?.let {
            PersonalizationShadowFingerprint(it, 5)
        },
        productionTopSourceType = topSource,
        candidateCount = candidateCount,
        manualPrefixCandidateCount = manualCount,
        manualPrefixCandidatesVisible = manualVisible,
        learnedTypedWordPresent = learned,
        productionHistoryTypedWordVisible = historyVisible,
        wordRuleAction = null,
        blockedSuggestionCount = 0,
        blockedAutocorrectCandidateCount = 0,
        preferredCorrectionCount = 0,
        findings = findings,
        evaluationMicros = evaluationMicros,
        inputStyle = inputStyle,
        sessionId = 0,
    )
}
