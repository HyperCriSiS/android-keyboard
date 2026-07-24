package org.futo.inputmethod.latin.languagepack.benchmark

import java.text.Normalizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.futo.inputmethod.latin.languagepack.fusion.AutocorrectDecision
import org.futo.inputmethod.latin.languagepack.fusion.CandidateFusionInput
import org.futo.inputmethod.latin.languagepack.fusion.FusedCandidate

const val CORRECTION_BENCHMARK_FORMAT_VERSION = "0.1"

@Serializable
data class CorrectionBenchmarkSuite(
    val formatVersion: String = CORRECTION_BENCHMARK_FORMAT_VERSION,
    val id: String,
    val name: String,
    val description: String? = null,
    val languageTags: List<String>,
    val license: String,
    val cases: List<CorrectionBenchmarkCase>,
)

@Serializable
data class CorrectionBenchmarkCase(
    val id: String,
    val languageTag: String,
    val layout: String,
    val leftContext: String,
    val typedText: String,
    val rightContext: String = "",
    val expectation: CorrectionBenchmarkExpectation,
    val candidates: List<CorrectionBenchmarkCandidate>,
    val tags: List<String> = emptyList(),
)

@Serializable
data class CorrectionBenchmarkExpectation(
    val action: CorrectionBenchmarkExpectedAction,
    val acceptableTexts: List<String>,
    val caseSensitive: Boolean = true,
)

@Serializable
enum class CorrectionBenchmarkExpectedAction {
    @SerialName("keep")
    Keep,

    @SerialName("replace")
    Replace,
}

@Serializable
data class CorrectionBenchmarkCandidate(
    val id: String,
    val text: String,
    val isTypedText: Boolean,
    val generatorConfidence: Double,
    val editRisk: Double,
    val rankerScore: Double? = null,
    val touchConfidence: Double? = null,
    val frequencyConfidence: Double? = null,
    val personalizationConfidence: Double? = null,
    val isExactDictionaryMatch: Boolean = false,
    val isPossiblyOffensive: Boolean = false,
    val isBlocked: Boolean = false,
) {
    fun toFusionInput(): CandidateFusionInput = CandidateFusionInput(
        id = id,
        text = text,
        isTypedText = isTypedText,
        generatorConfidence = generatorConfidence,
        editRisk = editRisk,
        rankerScore = rankerScore,
        touchConfidence = touchConfidence,
        frequencyConfidence = frequencyConfidence,
        personalizationConfidence = personalizationConfidence,
        isExactDictionaryMatch = isExactDictionaryMatch,
        isPossiblyOffensive = isPossiblyOffensive,
        isBlocked = isBlocked,
    )
}

enum class CorrectionBenchmarkAutocorrectOutcome {
    SafeKeep,
    CorrectCorrection,
    FalseCorrection,
    WrongCorrection,
    MissedCorrection,
}

data class CorrectionBenchmarkCaseResult(
    val caseId: String,
    val expectedAction: CorrectionBenchmarkExpectedAction,
    val candidateCovered: Boolean,
    val expectedRank: Int?,
    val top1Correct: Boolean,
    val top3Correct: Boolean,
    val reciprocalRank: Double,
    val selectedText: String?,
    val autocorrectOutcome: CorrectionBenchmarkAutocorrectOutcome,
    val elapsedMicros: Long,
)

data class CorrectionBenchmarkMetrics(
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

object CorrectionBenchmarkEvaluator {
    fun validateSuite(suite: CorrectionBenchmarkSuite): List<String> {
        val issues = mutableListOf<String>()
        if (suite.formatVersion != CORRECTION_BENCHMARK_FORMAT_VERSION) {
            issues += "Unsupported benchmark format version '${suite.formatVersion}'."
        }
        if (suite.id.isBlank()) issues += "Suite ID must not be blank."
        if (suite.name.isBlank()) issues += "Suite name must not be blank."
        if (suite.languageTags.isEmpty()) issues += "Suite must declare at least one language."
        if (suite.license.isBlank()) issues += "Suite license must not be blank."
        if (suite.cases.isEmpty()) issues += "Suite must contain at least one case."

        val seenCaseIds = mutableSetOf<String>()
        suite.cases.forEachIndexed { index, case ->
            val path = "cases[$index]"
            if (case.id.isBlank()) issues += "$path.id must not be blank."
            else if (!seenCaseIds.add(case.id)) issues += "$path.id '${case.id}' is duplicated."
            if (case.languageTag.isBlank()) issues += "$path.languageTag must not be blank."
            if (case.layout.isBlank()) issues += "$path.layout must not be blank."
            if (case.typedText.isEmpty()) issues += "$path.typedText must not be empty."
            if (case.expectation.acceptableTexts.isEmpty()) {
                issues += "$path.expectation.acceptableTexts must not be empty."
            }
            if (case.expectation.action == CorrectionBenchmarkExpectedAction.Keep &&
                case.expectation.acceptableTexts.none {
                    textEquals(it, case.typedText, case.expectation.caseSensitive)
                }
            ) {
                issues += "$path keep expectation must accept typedText."
            }
            if (case.candidates.isEmpty()) issues += "$path.candidates must not be empty."
            if (case.candidates.count { it.isTypedText } != 1) {
                issues += "$path must contain exactly one typed-text candidate."
            }
            if (case.candidates.map { it.id }.toSet().size != case.candidates.size) {
                issues += "$path candidate IDs must be unique."
            }
            if (case.candidates.none { it.isTypedText && textEquals(
                    it.text,
                    case.typedText,
                    case.expectation.caseSensitive,
                ) }
            ) {
                issues += "$path typed candidate text must equal typedText."
            }
            case.candidates.forEachIndexed { candidateIndex, candidate ->
                validateProbability(candidate.generatorConfidence)?.let {
                    issues += "$path.candidates[$candidateIndex].generatorConfidence $it"
                }
                validateProbability(candidate.editRisk)?.let {
                    issues += "$path.candidates[$candidateIndex].editRisk $it"
                }
                candidate.touchConfidence?.let(::validateProbability)?.let {
                    issues += "$path.candidates[$candidateIndex].touchConfidence $it"
                }
                candidate.frequencyConfidence?.let(::validateProbability)?.let {
                    issues += "$path.candidates[$candidateIndex].frequencyConfidence $it"
                }
                candidate.personalizationConfidence?.let(::validateProbability)?.let {
                    issues += "$path.candidates[$candidateIndex].personalizationConfidence $it"
                }
                if (candidate.rankerScore != null && !candidate.rankerScore.isFinite()) {
                    issues += "$path.candidates[$candidateIndex].rankerScore must be finite."
                }
            }
        }
        return issues
    }

    fun evaluateCase(
        case: CorrectionBenchmarkCase,
        fusedCandidates: List<FusedCandidate>,
        decision: AutocorrectDecision,
        elapsedMicros: Long,
    ): CorrectionBenchmarkCaseResult {
        require(elapsedMicros >= 0L) { "Elapsed time must not be negative." }
        require(fusedCandidates.map { it.input.id }.toSet().size == fusedCandidates.size) {
            "Fused candidate IDs must be unique."
        }

        val ordered = fusedCandidates.sortedWith(
            compareByDescending<FusedCandidate> { it.linearScore }
                .thenByDescending { it.input.isTypedText }
                .thenBy { it.input.id },
        )
        val expectedRank = ordered.indexOfFirst {
            case.accepts(it.input.text)
        }.takeIf { it >= 0 }?.plus(1)
        val selectedText = decision.correction?.input?.text

        return CorrectionBenchmarkCaseResult(
            caseId = case.id,
            expectedAction = case.expectation.action,
            candidateCovered = expectedRank != null,
            expectedRank = expectedRank,
            top1Correct = expectedRank == 1,
            top3Correct = expectedRank != null && expectedRank <= 3,
            reciprocalRank = expectedRank?.let { 1.0 / it.toDouble() } ?: 0.0,
            selectedText = selectedText,
            autocorrectOutcome = when (case.expectation.action) {
                CorrectionBenchmarkExpectedAction.Keep -> {
                    if (selectedText == null) {
                        CorrectionBenchmarkAutocorrectOutcome.SafeKeep
                    } else {
                        CorrectionBenchmarkAutocorrectOutcome.FalseCorrection
                    }
                }

                CorrectionBenchmarkExpectedAction.Replace -> {
                    when {
                        selectedText == null -> CorrectionBenchmarkAutocorrectOutcome.MissedCorrection
                        case.accepts(selectedText) -> {
                            CorrectionBenchmarkAutocorrectOutcome.CorrectCorrection
                        }
                        else -> CorrectionBenchmarkAutocorrectOutcome.WrongCorrection
                    }
                }
            },
            elapsedMicros = elapsedMicros,
        )
    }

    fun aggregate(results: List<CorrectionBenchmarkCaseResult>): CorrectionBenchmarkMetrics {
        require(results.isNotEmpty()) { "At least one benchmark result is required." }
        require(results.map { it.caseId }.toSet().size == results.size) {
            "Benchmark result case IDs must be unique."
        }

        val keep = results.filter {
            it.expectedAction == CorrectionBenchmarkExpectedAction.Keep
        }
        val replace = results.filter {
            it.expectedAction == CorrectionBenchmarkExpectedAction.Replace
        }
        val sortedLatency = results.map { it.elapsedMicros }.sorted()

        return CorrectionBenchmarkMetrics(
            totalCases = results.size,
            keepCases = keep.size,
            replacementCases = replace.size,
            candidateCoverage = ratio(results.count { it.candidateCovered }, results.size),
            top1Accuracy = ratio(results.count { it.top1Correct }, results.size),
            top3Accuracy = ratio(results.count { it.top3Correct }, results.size),
            meanReciprocalRank = results.sumOf { it.reciprocalRank } / results.size.toDouble(),
            falseCorrectionRate = ratio(
                keep.count {
                    it.autocorrectOutcome == CorrectionBenchmarkAutocorrectOutcome.FalseCorrection
                },
                keep.size,
            ),
            correctAutocorrectRate = ratio(
                replace.count {
                    it.autocorrectOutcome == CorrectionBenchmarkAutocorrectOutcome.CorrectCorrection
                },
                replace.size,
            ),
            wrongAutocorrectRate = ratio(
                results.count {
                    it.autocorrectOutcome == CorrectionBenchmarkAutocorrectOutcome.WrongCorrection ||
                        it.autocorrectOutcome == CorrectionBenchmarkAutocorrectOutcome.FalseCorrection
                },
                results.size,
            ),
            missedCorrectionRate = ratio(
                replace.count {
                    it.autocorrectOutcome == CorrectionBenchmarkAutocorrectOutcome.MissedCorrection
                },
                replace.size,
            ),
            latencyP50Micros = percentileNearestRank(sortedLatency, 0.50),
            latencyP95Micros = percentileNearestRank(sortedLatency, 0.95),
        )
    }

    private fun CorrectionBenchmarkCase.accepts(text: String): Boolean {
        return expectation.acceptableTexts.any {
            textEquals(it, text, expectation.caseSensitive)
        }
    }

    private fun validateProbability(value: Double): String? {
        return if (value.isFinite() && value in 0.0..1.0) null
        else "must be a finite value between 0 and 1."
    }

    private fun ratio(numerator: Int, denominator: Int): Double {
        return if (denominator == 0) 0.0 else numerator.toDouble() / denominator.toDouble()
    }

    private fun percentileNearestRank(sorted: List<Long>, percentile: Double): Long {
        require(sorted.isNotEmpty())
        val rank = kotlin.math.ceil(percentile * sorted.size.toDouble()).toInt().coerceAtLeast(1)
        return sorted[(rank - 1).coerceAtMost(sorted.lastIndex)]
    }

    private fun textEquals(first: String, second: String, caseSensitive: Boolean): Boolean {
        val a = Normalizer.normalize(first, Normalizer.Form.NFC)
        val b = Normalizer.normalize(second, Normalizer.Form.NFC)
        return if (caseSensitive) a == b else a.equals(b, ignoreCase = true)
    }
}
