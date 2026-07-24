package org.futo.inputmethod.latin.languagepack.benchmark

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.futo.inputmethod.latin.languagepack.fusion.AutocorrectDecisionReason
import org.futo.inputmethod.latin.languagepack.fusion.AutocorrectPolicy
import org.futo.inputmethod.latin.languagepack.fusion.CandidateFusionPolicy
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerScoringPolicy

const val CORRECTION_BENCHMARK_REPORT_FORMAT_VERSION = "0.1"

@Serializable
data class CorrectionBenchmarkReport(
    val formatVersion: String = CORRECTION_BENCHMARK_REPORT_FORMAT_VERSION,
    val suite: CorrectionBenchmarkReportSuite,
    val subject: CorrectionBenchmarkReportSubject,
    val policy: CorrectionBenchmarkReportPolicy,
    val environment: CorrectionBenchmarkReportEnvironment,
    val metrics: CorrectionBenchmarkReportMetrics,
    val cases: List<CorrectionBenchmarkReportCase>,
)

@Serializable
data class CorrectionBenchmarkReportSuite(
    val id: String,
    val sha256: String,
    val caseCount: Int,
)

@Serializable
data class CorrectionBenchmarkReportSubject(
    val mode: CorrectionBenchmarkReportMode,
    val modelName: String? = null,
    val componentCoordinate: String? = null,
    val modelSha256: String? = null,
    val quantization: String? = null,
)

@Serializable
enum class CorrectionBenchmarkReportMode {
    @SerialName("baseline")
    Baseline,

    @SerialName("ranker")
    Ranker,
}

@Serializable
data class CorrectionBenchmarkReportPolicy(
    val rankerLengthNormalizationExponent: Double,
    val rankerRightContextWeight: Double,
    val rankerCenter: Double,
    val rankerScale: Double,
    val generatorWeight: Double,
    val rankerWeight: Double,
    val touchWeight: Double,
    val frequencyWeight: Double,
    val personalizationWeight: Double,
    val editRiskWeight: Double,
    val exactDictionaryMatchBonus: Double,
    val typedTextBias: Double,
    val minimumCandidateConfidence: Double,
    val minimumTypedMargin: Double,
    val minimumRunnerUpMargin: Double,
    val maximumEditRisk: Double,
    val requireGeneratorAndRankerAgreement: Boolean,
    val rightContextTokenLimit: Int,
)

@Serializable
data class CorrectionBenchmarkReportEnvironment(
    val keyboardVersion: String,
    val keyboardCommit: String? = null,
    val operatingSystem: String,
    val architecture: String,
    val device: String? = null,
    val availableRamMb: Int? = null,
    val runtime: String? = null,
)

@Serializable
data class CorrectionBenchmarkReportMetrics(
    val totalCases: Int,
    val keepCases: Int,
    val replacementCases: Int,
    val candidateCoverage: Double,
    val top1Accuracy: Double,
    val top3Accuracy: Double,
    val meanReciprocalRank: Double,
    val falseCorrectionRate: Double,
    val correctAutocorrectRate: Double,
    val wrongAutocorrectRate: Double,
    val missedCorrectionRate: Double,
    val latencyP50Micros: Long,
    val latencyP95Micros: Long,
)

@Serializable
data class CorrectionBenchmarkReportCase(
    val id: String,
    val expectedAction: CorrectionBenchmarkExpectedAction,
    val candidateCovered: Boolean,
    val expectedRank: Int? = null,
    val selectedText: String? = null,
    val autocorrectOutcome: CorrectionBenchmarkAutocorrectOutcome,
    val autocorrectReason: String,
    val elapsedMicros: Long,
    val candidates: List<CorrectionBenchmarkReportCandidate>,
)

@Serializable
data class CorrectionBenchmarkReportCandidate(
    val id: String,
    val text: String,
    val isTypedText: Boolean,
    val linearScore: Double,
    val confidence: Double,
    val generatorEvidence: Double,
    val rankerEvidence: Double,
    val touchEvidence: Double,
    val frequencyEvidence: Double,
    val personalizationEvidence: Double,
    val editRiskPenalty: Double,
    val exactDictionaryBonus: Double,
    val typedTextBias: Double,
)

data class CorrectionBenchmarkReportMetadata(
    val suiteSha256: String,
    val modelSha256: String? = null,
    val componentCoordinate: String? = null,
    val quantization: String? = null,
    val keyboardVersion: String,
    val keyboardCommit: String? = null,
    val operatingSystem: String,
    val architecture: String,
    val device: String? = null,
    val availableRamMb: Int? = null,
    val runtime: String? = null,
)

object CorrectionBenchmarkReportBuilder {
    fun build(
        run: CorrectionBenchmarkRun,
        suite: CorrectionBenchmarkSuite,
        runnerPolicy: CorrectionBenchmarkRunnerPolicy,
        metadata: CorrectionBenchmarkReportMetadata,
    ): CorrectionBenchmarkReport {
        require(run.suiteId == suite.id) {
            "Benchmark run suite ID does not match the supplied suite."
        }
        require(metadata.suiteSha256.matches(Regex("^[A-Fa-f0-9]{64}$"))) {
            "Suite SHA-256 must contain exactly 64 hexadecimal characters."
        }
        metadata.modelSha256?.let {
            require(it.matches(Regex("^[A-Fa-f0-9]{64}$"))) {
                "Model SHA-256 must contain exactly 64 hexadecimal characters."
            }
        }
        require(run.caseResults.size == suite.cases.size) {
            "Benchmark run does not contain every suite case."
        }

        val fusion = runnerPolicy.fusion
        val weights = fusion.weights
        val autocorrect = runnerPolicy.autocorrect
        val scoring = runnerPolicy.rankerScoring

        return CorrectionBenchmarkReport(
            suite = CorrectionBenchmarkReportSuite(
                id = suite.id,
                sha256 = metadata.suiteSha256.lowercase(),
                caseCount = suite.cases.size,
            ),
            subject = CorrectionBenchmarkReportSubject(
                mode = if (run.rankerModelName == null) {
                    CorrectionBenchmarkReportMode.Baseline
                } else {
                    CorrectionBenchmarkReportMode.Ranker
                },
                modelName = run.rankerModelName,
                componentCoordinate = metadata.componentCoordinate,
                modelSha256 = metadata.modelSha256?.lowercase(),
                quantization = metadata.quantization,
            ),
            policy = CorrectionBenchmarkReportPolicy(
                rankerLengthNormalizationExponent = scoring.lengthNormalizationExponent,
                rankerRightContextWeight = scoring.rightContextWeight,
                rankerCenter = fusion.calibration.rankerCenter,
                rankerScale = fusion.calibration.rankerScale,
                generatorWeight = weights.generator,
                rankerWeight = weights.ranker,
                touchWeight = weights.touch,
                frequencyWeight = weights.frequency,
                personalizationWeight = weights.personalization,
                editRiskWeight = weights.editRisk,
                exactDictionaryMatchBonus = weights.exactDictionaryMatchBonus,
                typedTextBias = weights.typedTextBias,
                minimumCandidateConfidence = autocorrect.minimumCandidateConfidence,
                minimumTypedMargin = autocorrect.minimumTypedMargin,
                minimumRunnerUpMargin = autocorrect.minimumRunnerUpMargin,
                maximumEditRisk = autocorrect.maximumEditRisk,
                requireGeneratorAndRankerAgreement = autocorrect.requireGeneratorAndRankerAgreement,
                rightContextTokenLimit = runnerPolicy.rightContextTokenLimit,
            ),
            environment = CorrectionBenchmarkReportEnvironment(
                keyboardVersion = metadata.keyboardVersion,
                keyboardCommit = metadata.keyboardCommit,
                operatingSystem = metadata.operatingSystem,
                architecture = metadata.architecture,
                device = metadata.device,
                availableRamMb = metadata.availableRamMb,
                runtime = metadata.runtime,
            ),
            metrics = run.metrics.toReportMetrics(),
            cases = run.caseResults.map { detailed ->
                val result = detailed.benchmarkResult
                CorrectionBenchmarkReportCase(
                    id = result.caseId,
                    expectedAction = result.expectedAction,
                    candidateCovered = result.candidateCovered,
                    expectedRank = result.expectedRank,
                    selectedText = result.selectedText,
                    autocorrectOutcome = result.autocorrectOutcome,
                    autocorrectReason = detailed.autocorrectDecision.reason.serializedName,
                    elapsedMicros = result.elapsedMicros,
                    candidates = detailed.fusedCandidates.map { candidate ->
                        val evidence = candidate.evidence
                        CorrectionBenchmarkReportCandidate(
                            id = candidate.input.id,
                            text = candidate.input.text,
                            isTypedText = candidate.input.isTypedText,
                            linearScore = candidate.linearScore,
                            confidence = candidate.confidence,
                            generatorEvidence = evidence.generator,
                            rankerEvidence = evidence.ranker,
                            touchEvidence = evidence.touch,
                            frequencyEvidence = evidence.frequency,
                            personalizationEvidence = evidence.personalization,
                            editRiskPenalty = evidence.editRiskPenalty,
                            exactDictionaryBonus = evidence.exactDictionaryBonus,
                            typedTextBias = evidence.typedTextBias,
                        )
                    },
                )
            },
        )
    }

    private fun CorrectionBenchmarkMetrics.toReportMetrics(): CorrectionBenchmarkReportMetrics {
        return CorrectionBenchmarkReportMetrics(
            totalCases = totalCases,
            keepCases = keepCases,
            replacementCases = replacementCases,
            candidateCoverage = candidateCoverage,
            top1Accuracy = top1Accuracy,
            top3Accuracy = top3Accuracy,
            meanReciprocalRank = meanReciprocalRank,
            falseCorrectionRate = falseCorrectionRate,
            correctAutocorrectRate = correctAutocorrectRate,
            wrongAutocorrectRate = wrongAutocorrectRate,
            missedCorrectionRate = missedCorrectionRate,
            latencyP50Micros = latencyP50Micros,
            latencyP95Micros = latencyP95Micros,
        )
    }

    private val AutocorrectDecisionReason.serializedName: String
        get() = name.replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()
}
