package org.futo.inputmethod.latin.languagepack.benchmark

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import org.futo.inputmethod.latin.languagepack.fusion.AutocorrectPolicy
import org.futo.inputmethod.latin.languagepack.fusion.CandidateFusionCalibration
import org.futo.inputmethod.latin.languagepack.fusion.CandidateFusionPolicy
import org.futo.inputmethod.latin.languagepack.fusion.ExperimentalCandidateFusion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CorrectionBenchmarkTest {
    private val fusionPolicy = CandidateFusionPolicy(
        calibration = CandidateFusionCalibration(rankerCenter = -4.0, rankerScale = 1.0),
    )

    @Test
    fun validSuitePassesValidationAndUnicodeIsNormalized() {
        val keep = benchmarkCase(
            id = "keep-accent",
            typed = "café",
            action = CorrectionBenchmarkExpectedAction.Keep,
            acceptable = listOf("cafe\u0301"),
            candidates = listOf(
                candidate("typed", "café", typed = true, generator = 0.9, ranker = -2.0),
                candidate("cafe", "cafe", generator = 0.4, ranker = -4.0),
            ),
        )
        val suite = CorrectionBenchmarkSuite(
            id = "de-test",
            name = "German test",
            languageTags = listOf("de"),
            license = "CC0-1.0",
            cases = listOf(keep),
        )

        assertTrue(CorrectionBenchmarkEvaluator.validateSuite(suite).isEmpty())
        val fused = ExperimentalCandidateFusion.fuse(
            keep.candidates.map { it.toFusionInput() },
            fusionPolicy,
        )
        val decision = ExperimentalCandidateFusion.decideAutocorrect(fused)
        val result = CorrectionBenchmarkEvaluator.evaluateCase(keep, fused, decision, 100)

        assertTrue(result.top1Correct)
        assertEquals(CorrectionBenchmarkAutocorrectOutcome.SafeKeep, result.autocorrectOutcome)
    }

    @Test
    fun aggregateReportsRankingSafetyCoverageAndLatency() {
        val keep = benchmarkCase(
            id = "keep-seit",
            typed = "seit",
            action = CorrectionBenchmarkExpectedAction.Keep,
            acceptable = listOf("seit"),
            candidates = listOf(
                candidate("seit", "seit", typed = true, generator = 0.92, ranker = -2.0),
                candidate("seid", "seid", generator = 0.6, ranker = -3.5),
            ),
        )
        val replace = benchmarkCase(
            id = "replace-wahrscheinlich",
            typed = "warscheinlich",
            action = CorrectionBenchmarkExpectedAction.Replace,
            acceptable = listOf("wahrscheinlich"),
            candidates = listOf(
                candidate("typed", "warscheinlich", typed = true, generator = 0.2, ranker = -7.0),
                candidate("expected", "wahrscheinlich", generator = 0.98, ranker = -1.5),
                candidate("other", "anscheinlich", generator = 0.4, ranker = -4.0),
            ),
        )
        val missing = benchmarkCase(
            id = "missing-candidate",
            typed = "kompositonswort",
            action = CorrectionBenchmarkExpectedAction.Replace,
            acceptable = listOf("Kompositionswort"),
            candidates = listOf(
                candidate("typed", "kompositonswort", typed = true, generator = 0.9, ranker = -2.0),
                candidate("other", "Komposition", generator = 0.3, ranker = -5.0),
            ),
        )

        val results = listOf(
            evaluate(keep, 100),
            evaluate(replace, 200),
            evaluate(missing, 1000),
        )
        val metrics = CorrectionBenchmarkEvaluator.aggregate(results)

        assertEquals(3, metrics.totalCases)
        assertEquals(1, metrics.keepCases)
        assertEquals(2, metrics.replacementCases)
        assertEquals(2.0 / 3.0, metrics.candidateCoverage, 0.000001)
        assertEquals(2.0 / 3.0, metrics.top1Accuracy, 0.000001)
        assertEquals(2.0 / 3.0, metrics.top3Accuracy, 0.000001)
        assertEquals(0.0, metrics.falseCorrectionRate, 0.000001)
        assertEquals(0.5, metrics.correctAutocorrectRate, 0.000001)
        assertEquals(0.5, metrics.missedCorrectionRate, 0.000001)
        assertEquals(0.0, metrics.wrongAutocorrectRate, 0.000001)
        assertEquals(200L, metrics.latencyP50Micros)
        assertEquals(1000L, metrics.latencyP95Micros)
    }

    @Test
    fun validationRejectsMissingTypedCandidateAndInvalidSignals() {
        val invalid = benchmarkCase(
            id = "invalid",
            typed = "word",
            action = CorrectionBenchmarkExpectedAction.Keep,
            acceptable = listOf("different"),
            candidates = listOf(
                candidate("candidate", "candidate", generator = 2.0, ranker = Double.NaN),
            ),
        )
        val suite = CorrectionBenchmarkSuite(
            id = "invalid-suite",
            name = "Invalid",
            languageTags = listOf("de"),
            license = "CC0-1.0",
            cases = listOf(invalid, invalid),
        )

        val issues = CorrectionBenchmarkEvaluator.validateSuite(suite)

        assertTrue(issues.any { "duplicated" in it })
        assertTrue(issues.any { "exactly one typed-text candidate" in it })
        assertTrue(issues.any { "must accept typedText" in it })
        assertTrue(issues.any { "generatorConfidence" in it })
        assertTrue(issues.any { "rankerScore" in it })
    }

    private fun evaluate(case: CorrectionBenchmarkCase, elapsedMicros: Long): CorrectionBenchmarkCaseResult {
        val fused = ExperimentalCandidateFusion.fuse(
            case.candidates.map { it.toFusionInput() },
            fusionPolicy,
        )
        val decision = ExperimentalCandidateFusion.decideAutocorrect(
            fused,
            AutocorrectPolicy(
                minimumCandidateConfidence = 0.55,
                minimumTypedMargin = 0.05,
                minimumRunnerUpMargin = 0.02,
                maximumEditRisk = 0.8,
            ),
        )
        return CorrectionBenchmarkEvaluator.evaluateCase(case, fused, decision, elapsedMicros)
    }

    private fun benchmarkCase(
        id: String,
        typed: String,
        action: CorrectionBenchmarkExpectedAction,
        acceptable: List<String>,
        candidates: List<CorrectionBenchmarkCandidate>,
    ): CorrectionBenchmarkCase {
        return CorrectionBenchmarkCase(
            id = id,
            languageTag = "de-DE",
            layout = "qwertz",
            leftContext = "Das ist",
            typedText = typed,
            rightContext = " richtig.",
            expectation = CorrectionBenchmarkExpectation(
                action = action,
                acceptableTexts = acceptable,
            ),
            candidates = candidates,
        )
    }

    private fun candidate(
        id: String,
        text: String,
        typed: Boolean = false,
        generator: Double,
        ranker: Double,
    ): CorrectionBenchmarkCandidate {
        return CorrectionBenchmarkCandidate(
            id = id,
            text = text,
            isTypedText = typed,
            generatorConfidence = generator,
            editRisk = if (typed) 0.0 else 0.2,
            rankerScore = ranker,
            frequencyConfidence = generator.coerceIn(0.0, 1.0),
            isExactDictionaryMatch = typed,
        )
    }
}
