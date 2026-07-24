package org.futo.inputmethod.latin.languagepack.benchmark

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.futo.inputmethod.latin.languagepack.LanguagePackageComponentCoordinate
import org.futo.inputmethod.latin.languagepack.fusion.AutocorrectPolicy
import org.futo.inputmethod.latin.languagepack.fusion.CandidateFusionCalibration
import org.futo.inputmethod.latin.languagepack.fusion.CandidateFusionPolicy
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerDescriptor
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerDiagnostics
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerFailure
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerFailureCode
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerOutcome
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerRequest
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerRuntime
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerScore
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerBoundaryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CorrectionBenchmarkRunnerTest {
    private val runnerPolicy = CorrectionBenchmarkRunnerPolicy(
        fusion = CandidateFusionPolicy(
            calibration = CandidateFusionCalibration(rankerCenter = -4.0, rankerScale = 1.0),
        ),
        autocorrect = AutocorrectPolicy(
            minimumCandidateConfidence = 0.5,
            minimumTypedMargin = 0.05,
            minimumRunnerUpMargin = 0.01,
            maximumEditRisk = 0.8,
        ),
    )

    @Test
    fun baselineAndRankerRunsProduceComparableMetrics() = runBlocking {
        val suite = suite()
        val baseline = CorrectionBenchmarkRunner.runBaseline(suite, runnerPolicy)
        val ranked = CorrectionBenchmarkRunner.runWithRanker(
            suite,
            FakeRanker(
                scoresByRequest = mapOf(
                    "benchmark:replace" to mapOf("typed" to -8.0, "expected" to -1.0),
                    "benchmark:keep" to mapOf("typed" to -1.0, "wrong" to -7.0),
                ),
            ),
            runnerPolicy,
        )

        assertTrue(baseline is CorrectionBenchmarkRunOutcome.Success)
        assertTrue(ranked is CorrectionBenchmarkRunOutcome.Success)
        ranked as CorrectionBenchmarkRunOutcome.Success
        assertEquals("Fake ranker", ranked.run.rankerModelName)
        assertEquals(2, ranked.run.caseResults.size)
        assertEquals(1.0, ranked.run.metrics.top1Accuracy, 0.000001)
        assertEquals(0.0, ranked.run.metrics.falseCorrectionRate, 0.000001)
        assertEquals(1.0, ranked.run.metrics.correctAutocorrectRate, 0.000001)
        assertEquals(250L, ranked.run.metrics.latencyP50Micros)
        assertEquals(500L, ranked.run.metrics.latencyP95Micros)
    }

    @Test
    fun rankerFailureStopsRunAndIdentifiesCase() = runBlocking {
        val outcome = CorrectionBenchmarkRunner.runWithRanker(
            suite(),
            FakeRanker(failRequest = "benchmark:keep"),
            runnerPolicy,
        )

        assertTrue(outcome is CorrectionBenchmarkRunOutcome.Failure)
        outcome as CorrectionBenchmarkRunOutcome.Failure
        assertEquals(CorrectionBenchmarkRunFailureCode.RankerFailure, outcome.failure.code)
        assertEquals("keep", outcome.failure.caseId)
    }

    @Test
    fun invalidSuiteFailsBeforeCallingRanker() = runBlocking {
        val ranker = FakeRanker(emptyMap())
        val invalid = suite().copy(cases = emptyList())

        val outcome = CorrectionBenchmarkRunner.runWithRanker(invalid, ranker, runnerPolicy)

        assertTrue(outcome is CorrectionBenchmarkRunOutcome.Failure)
        outcome as CorrectionBenchmarkRunOutcome.Failure
        assertEquals(CorrectionBenchmarkRunFailureCode.InvalidSuite, outcome.failure.code)
        assertTrue(ranker.requests.isEmpty())
    }

    private fun suite(): CorrectionBenchmarkSuite {
        return CorrectionBenchmarkSuite(
            id = "suite",
            name = "Suite",
            languageTags = listOf("de"),
            license = "CC0-1.0",
            cases = listOf(
                CorrectionBenchmarkCase(
                    id = "replace",
                    languageTag = "de-DE",
                    layout = "qwertz",
                    leftContext = "Das ist",
                    typedText = " warscheinlich",
                    rightContext = " richtig.",
                    expectation = CorrectionBenchmarkExpectation(
                        action = CorrectionBenchmarkExpectedAction.Replace,
                        acceptableTexts = listOf(" wahrscheinlich"),
                    ),
                    candidates = listOf(
                        candidate("typed", " warscheinlich", true, 0.45),
                        candidate("expected", " wahrscheinlich", false, 0.75),
                    ),
                ),
                CorrectionBenchmarkCase(
                    id = "keep",
                    languageTag = "de-DE",
                    layout = "qwertz",
                    leftContext = "Ich bin",
                    typedText = " seit",
                    rightContext = " gestern hier.",
                    expectation = CorrectionBenchmarkExpectation(
                        action = CorrectionBenchmarkExpectedAction.Keep,
                        acceptableTexts = listOf(" seit"),
                    ),
                    candidates = listOf(
                        candidate("typed", " seit", true, 0.75),
                        candidate("wrong", " seid", false, 0.78),
                    ),
                ),
            ),
        )
    }

    private fun candidate(
        id: String,
        text: String,
        typed: Boolean,
        generator: Double,
    ): CorrectionBenchmarkCandidate {
        return CorrectionBenchmarkCandidate(
            id = id,
            text = text,
            isTypedText = typed,
            generatorConfidence = generator,
            editRisk = if (typed) 0.0 else 0.2,
            frequencyConfidence = generator,
            isExactDictionaryMatch = typed,
        )
    }

    private class FakeRanker(
        private val scoresByRequest: Map<String, Map<String, Double>> = emptyMap(),
        private val failRequest: String? = null,
    ) : CandidateRankerRuntime {
        val requests = mutableListOf<CandidateRankerRequest>()
        override val descriptor = CandidateRankerDescriptor(
            component = LanguagePackageComponentCoordinate(
                packageId = "org.futo.test.ranker",
                packageVersion = "1.0.0",
                componentId = "ranker",
                componentVersion = "1.0.0",
            ),
            supportedLanguages = setOf("de"),
            maxContextTokens = 256,
            maxBatchSize = 16,
            boundaryMode = CandidateRankerBoundaryMode.LeadingSeparator,
            supportsRightContext = true,
            modelName = "Fake ranker",
        )

        override suspend fun rank(request: CandidateRankerRequest): CandidateRankerOutcome {
            requests += request
            if (request.requestId == failRequest) {
                return CandidateRankerOutcome.Failure(
                    request.requestId,
                    CandidateRankerFailure(
                        code = CandidateRankerFailureCode.RuntimeFailure,
                        message = "Synthetic failure",
                        recoverable = false,
                    ),
                )
            }
            val configured = scoresByRequest[request.requestId].orEmpty()
            return CandidateRankerOutcome.Success(
                requestId = request.requestId,
                descriptor = descriptor,
                scores = request.candidates.map { candidate ->
                    CandidateRankerScore(
                        candidateId = candidate.id,
                        candidateLogProbability = configured[candidate.id] ?: -4.0,
                        candidateTokenCount = 1,
                    )
                },
                diagnostics = CandidateRankerDiagnostics(
                    elapsedMicros = if (request.requestId.endsWith("replace")) 250 else 500,
                    evaluatedCandidateTokens = request.candidates.size,
                    evaluatedRightContextTokens = 0,
                    reusedPrefixTokens = request.candidates.size * 4,
                    batchCount = 1,
                ),
            )
        }

        override fun close() = Unit
    }
}
