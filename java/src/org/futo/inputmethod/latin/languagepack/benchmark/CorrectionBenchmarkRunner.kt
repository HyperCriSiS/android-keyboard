package org.futo.inputmethod.latin.languagepack.benchmark

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.futo.inputmethod.latin.languagepack.fusion.AutocorrectDecision
import org.futo.inputmethod.latin.languagepack.fusion.AutocorrectPolicy
import org.futo.inputmethod.latin.languagepack.fusion.CandidateFusionPolicy
import org.futo.inputmethod.latin.languagepack.fusion.ExperimentalCandidateFusion
import org.futo.inputmethod.latin.languagepack.fusion.FusedCandidate
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerCandidate
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerOutcome
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerRequest
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerRuntime
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankerScoringPolicy
import org.futo.inputmethod.latin.languagepack.ranker.CandidateRankingPurpose

data class CorrectionBenchmarkRunnerPolicy(
    val rankerScoring: CandidateRankerScoringPolicy = CandidateRankerScoringPolicy(),
    val fusion: CandidateFusionPolicy = CandidateFusionPolicy(),
    val autocorrect: AutocorrectPolicy = AutocorrectPolicy(),
    val rightContextTokenLimit: Int = 8,
) {
    init {
        require(rightContextTokenLimit in 0..64) {
            "Right-context token limit must be between 0 and 64."
        }
    }
}

data class CorrectionBenchmarkDetailedCaseResult(
    val benchmarkResult: CorrectionBenchmarkCaseResult,
    val fusedCandidates: List<FusedCandidate>,
    val autocorrectDecision: AutocorrectDecision,
)

data class CorrectionBenchmarkRun(
    val suiteId: String,
    val rankerModelName: String?,
    val caseResults: List<CorrectionBenchmarkDetailedCaseResult>,
    val metrics: CorrectionBenchmarkMetrics,
)

enum class CorrectionBenchmarkRunFailureCode {
    InvalidSuite,
    RankerFailure,
    InternalFailure,
}

data class CorrectionBenchmarkRunFailure(
    val code: CorrectionBenchmarkRunFailureCode,
    val message: String,
    val caseId: String? = null,
)

sealed class CorrectionBenchmarkRunOutcome {
    data class Success(val run: CorrectionBenchmarkRun) : CorrectionBenchmarkRunOutcome()
    data class Failure(val failure: CorrectionBenchmarkRunFailure) : CorrectionBenchmarkRunOutcome()
}

object CorrectionBenchmarkRunner {
    suspend fun runWithRanker(
        suite: CorrectionBenchmarkSuite,
        ranker: CandidateRankerRuntime,
        policy: CorrectionBenchmarkRunnerPolicy = CorrectionBenchmarkRunnerPolicy(),
    ): CorrectionBenchmarkRunOutcome {
        return run(
            suite = suite,
            ranker = ranker,
            rankerModelName = ranker.descriptor.modelName,
            policy = policy,
        )
    }

    suspend fun runBaseline(
        suite: CorrectionBenchmarkSuite,
        policy: CorrectionBenchmarkRunnerPolicy = CorrectionBenchmarkRunnerPolicy(),
    ): CorrectionBenchmarkRunOutcome {
        return run(
            suite = suite,
            ranker = null,
            rankerModelName = null,
            policy = policy,
        )
    }

    private suspend fun run(
        suite: CorrectionBenchmarkSuite,
        ranker: CandidateRankerRuntime?,
        rankerModelName: String?,
        policy: CorrectionBenchmarkRunnerPolicy,
    ): CorrectionBenchmarkRunOutcome {
        val validation = CorrectionBenchmarkEvaluator.validateSuite(suite)
        if (validation.isNotEmpty()) {
            return CorrectionBenchmarkRunOutcome.Failure(
                CorrectionBenchmarkRunFailure(
                    code = CorrectionBenchmarkRunFailureCode.InvalidSuite,
                    message = validation.joinToString(separator = "\n"),
                ),
            )
        }

        val results = ArrayList<CorrectionBenchmarkDetailedCaseResult>(suite.cases.size)
        for (case in suite.cases) {
            currentCoroutineContext().ensureActive()

            val rankerScores = if (ranker == null) {
                emptyMap()
            } else {
                when (val outcome = rankCase(ranker, case, policy)) {
                    is CandidateRankerOutcome.Failure -> {
                        return CorrectionBenchmarkRunOutcome.Failure(
                            CorrectionBenchmarkRunFailure(
                                code = CorrectionBenchmarkRunFailureCode.RankerFailure,
                                message = "${outcome.failure.code}: ${outcome.failure.message}",
                                caseId = case.id,
                            ),
                        )
                    }

                    is CandidateRankerOutcome.Success -> outcome.scores.associate { score ->
                        score.candidateId to score.normalizedScore(policy.rankerScoring)
                    }
                }
            }

            val fusionInputs = case.candidates.map { candidate ->
                candidate.toFusionInput().copy(
                    rankerScore = rankerScores[candidate.id] ?: candidate.rankerScore,
                )
            }
            val fused = try {
                ExperimentalCandidateFusion.fuse(fusionInputs, policy.fusion)
            } catch (exception: IllegalArgumentException) {
                return CorrectionBenchmarkRunOutcome.Failure(
                    CorrectionBenchmarkRunFailure(
                        code = CorrectionBenchmarkRunFailureCode.InternalFailure,
                        message = exception.message ?: "Candidate fusion failed.",
                        caseId = case.id,
                    ),
                )
            }
            val decision = ExperimentalCandidateFusion.decideAutocorrect(fused, policy.autocorrect)
            val elapsedMicros = if (ranker == null) {
                0L
            } else {
                // rankCase has already validated that every case produces a success outcome.
                val diagnosticOutcome = rankCaseDiagnosticsCache.remove(case.id)
                diagnosticOutcome ?: 0L
            }
            results += CorrectionBenchmarkDetailedCaseResult(
                benchmarkResult = CorrectionBenchmarkEvaluator.evaluateCase(
                    case = case,
                    fusedCandidates = fused,
                    decision = decision,
                    elapsedMicros = elapsedMicros,
                ),
                fusedCandidates = fused,
                autocorrectDecision = decision,
            )
        }

        val basicResults = results.map { it.benchmarkResult }
        return CorrectionBenchmarkRunOutcome.Success(
            CorrectionBenchmarkRun(
                suiteId = suite.id,
                rankerModelName = rankerModelName,
                caseResults = results,
                metrics = CorrectionBenchmarkEvaluator.aggregate(basicResults),
            ),
        )
    }

    private val rankCaseDiagnosticsCache = mutableMapOf<String, Long>()

    private suspend fun rankCase(
        ranker: CandidateRankerRuntime,
        case: CorrectionBenchmarkCase,
        policy: CorrectionBenchmarkRunnerPolicy,
    ): CandidateRankerOutcome {
        val outcome = ranker.rank(
            CandidateRankerRequest(
                requestId = "benchmark:${case.id}",
                languageTag = case.languageTag,
                purpose = CandidateRankingPurpose.Correction,
                leftContext = case.leftContext,
                typedText = case.typedText,
                rightContext = case.rightContext,
                candidates = case.candidates.map { candidate ->
                    CandidateRankerCandidate(
                        id = candidate.id,
                        displayText = candidate.text,
                        replacementText = candidate.text,
                    )
                },
                rightContextTokenLimit = policy.rightContextTokenLimit,
            ),
        )
        if (outcome is CandidateRankerOutcome.Success) {
            rankCaseDiagnosticsCache[case.id] = outcome.diagnostics.elapsedMicros
        }
        return outcome
    }
}
