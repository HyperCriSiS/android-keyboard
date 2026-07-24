package org.futo.inputmethod.latin.languagepack.benchmark

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import kotlinx.serialization.SerializationException
import org.futo.inputmethod.latin.languagepack.fusion.AutocorrectDecision
import org.futo.inputmethod.latin.languagepack.fusion.AutocorrectDecisionReason
import org.futo.inputmethod.latin.languagepack.fusion.CandidateFusionEvidence
import org.futo.inputmethod.latin.languagepack.fusion.CandidateFusionInput
import org.futo.inputmethod.latin.languagepack.fusion.FusedCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CorrectionBenchmarkReportTest {
    @Test
    fun reportCapturesHashesPoliciesEnvironmentAndPerCandidateEvidence() {
        val suite = suite()
        val run = run(suite)
        val policy = CorrectionBenchmarkRunnerPolicy()
        val report = CorrectionBenchmarkReportBuilder.build(
            run = run,
            suite = suite,
            runnerPolicy = policy,
            metadata = CorrectionBenchmarkReportMetadata(
                suiteSha256 = "A".repeat(64),
                modelSha256 = "B".repeat(64),
                componentCoordinate = "org.futo.test@1.0.0:ranker@1.0.0",
                quantization = "Q4_K_M",
                keyboardVersion = "0.2.0",
                keyboardCommit = "0123456789abcdef",
                operatingSystem = "Android 16",
                architecture = "arm64-v8a",
                device = "Test device",
                availableRamMb = 8192,
                runtime = "gguf-causal-ranker API 1",
            ),
        )

        assertEquals(CorrectionBenchmarkReportMode.Ranker, report.subject.mode)
        assertEquals("a".repeat(64), report.suite.sha256)
        assertEquals("b".repeat(64), report.subject.modelSha256)
        assertEquals("Q4_K_M", report.subject.quantization)
        assertEquals(1, report.cases.size)
        assertEquals("correct", report.cases.single().autocorrectReason)
        assertEquals(0.8, report.cases.single().candidates.single().confidence, 0.000001)

        val decoded = CorrectionBenchmarkReportCodec.decode(
            CorrectionBenchmarkReportCodec.encode(report),
        )
        assertEquals(report, decoded)
    }

    @Test
    fun baselineReportDoesNotInventModelIdentity() {
        val suite = suite()
        val rankerRun = run(suite)
        val baselineRun = rankerRun.copy(rankerModelName = null)
        val report = CorrectionBenchmarkReportBuilder.build(
            run = baselineRun,
            suite = suite,
            runnerPolicy = CorrectionBenchmarkRunnerPolicy(),
            metadata = CorrectionBenchmarkReportMetadata(
                suiteSha256 = "c".repeat(64),
                keyboardVersion = "0.2.0",
                operatingSystem = "Windows 11",
                architecture = "x86_64",
            ),
        )

        assertEquals(CorrectionBenchmarkReportMode.Baseline, report.subject.mode)
        assertNull(report.subject.modelName)
        assertNull(report.subject.modelSha256)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSuiteHashIsRejected() {
        val suite = suite()
        CorrectionBenchmarkReportBuilder.build(
            run = run(suite),
            suite = suite,
            runnerPolicy = CorrectionBenchmarkRunnerPolicy(),
            metadata = CorrectionBenchmarkReportMetadata(
                suiteSha256 = "not-a-hash",
                keyboardVersion = "0.2.0",
                operatingSystem = "Android",
                architecture = "arm64-v8a",
            ),
        )
    }

    @Test
    fun codecRejectsUnknownNonExtensionFields() {
        val source = """
            {
              "formatVersion": "0.1",
              "suite": {"id":"suite","sha256":"${"a".repeat(64)}","caseCount":0},
              "subject": {"mode":"baseline"},
              "policy": {
                "rankerLengthNormalizationExponent":0.7,
                "rankerRightContextWeight":0.25,
                "rankerCenter":-4.0,
                "rankerScale":1.5,
                "generatorWeight":1.6,
                "rankerWeight":1.4,
                "touchWeight":0.8,
                "frequencyWeight":0.5,
                "personalizationWeight":0.9,
                "editRiskWeight":1.5,
                "exactDictionaryMatchBonus":0.35,
                "typedTextBias":0.2,
                "minimumCandidateConfidence":0.68,
                "minimumTypedMargin":0.12,
                "minimumRunnerUpMargin":0.04,
                "maximumEditRisk":0.72,
                "requireGeneratorAndRankerAgreement":false,
                "rightContextTokenLimit":8
              },
              "environment": {
                "keyboardVersion":"0.2.0",
                "operatingSystem":"Android",
                "architecture":"arm64-v8a"
              },
              "metrics": {
                "totalCases":0,
                "keepCases":0,
                "replacementCases":0,
                "candidateCoverage":0.0,
                "top1Accuracy":0.0,
                "top3Accuracy":0.0,
                "meanReciprocalRank":0.0,
                "falseCorrectionRate":0.0,
                "correctAutocorrectRate":0.0,
                "wrongAutocorrectRate":0.0,
                "missedCorrectionRate":0.0,
                "latencyP50Micros":0,
                "latencyP95Micros":0
              },
              "cases": [],
              "unexpected": true
            }
        """.trimIndent()

        try {
            CorrectionBenchmarkReportCodec.decode(source)
            fail("Unknown non-extension field should fail strict decoding")
        } catch (_: SerializationException) {
            // Expected.
        }
    }

    private fun suite(): CorrectionBenchmarkSuite {
        return CorrectionBenchmarkSuite(
            id = "suite",
            name = "Suite",
            languageTags = listOf("de"),
            license = "CC0-1.0",
            cases = listOf(
                CorrectionBenchmarkCase(
                    id = "case",
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
                        CorrectionBenchmarkCandidate(
                            id = "expected",
                            text = " wahrscheinlich",
                            isTypedText = false,
                            generatorConfidence = 0.9,
                            editRisk = 0.1,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun run(suite: CorrectionBenchmarkSuite): CorrectionBenchmarkRun {
        val input = CandidateFusionInput(
            id = "expected",
            text = " wahrscheinlich",
            isTypedText = false,
            generatorConfidence = 0.9,
            editRisk = 0.1,
        )
        val fused = FusedCandidate(
            input = input,
            linearScore = 1.38629436112,
            confidence = 0.8,
            evidence = CandidateFusionEvidence(
                generator = 1.0,
                ranker = 0.5,
                touch = 0.0,
                frequency = 0.0,
                personalization = 0.0,
                editRiskPenalty = 0.15,
                exactDictionaryBonus = 0.0,
                typedTextBias = 0.0,
            ),
        )
        val decision = AutocorrectDecision(
            correction = fused,
            reason = AutocorrectDecisionReason.Correct,
            typedCandidate = null,
            runnerUp = null,
        )
        val basic = CorrectionBenchmarkCaseResult(
            caseId = "case",
            expectedAction = CorrectionBenchmarkExpectedAction.Replace,
            candidateCovered = true,
            expectedRank = 1,
            top1Correct = true,
            top3Correct = true,
            reciprocalRank = 1.0,
            selectedText = " wahrscheinlich",
            autocorrectOutcome = CorrectionBenchmarkAutocorrectOutcome.CorrectCorrection,
            elapsedMicros = 100,
        )
        return CorrectionBenchmarkRun(
            suiteId = suite.id,
            rankerModelName = "Test ranker",
            caseResults = listOf(
                CorrectionBenchmarkDetailedCaseResult(
                    benchmarkResult = basic,
                    fusedCandidates = listOf(fused),
                    autocorrectDecision = decision,
                ),
            ),
            metrics = CorrectionBenchmarkEvaluator.aggregate(listOf(basic)),
        )
    }
}
