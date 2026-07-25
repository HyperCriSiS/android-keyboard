package org.futo.inputmethod.latin.languagepack.ranker

import java.io.Closeable
import kotlin.math.pow
import org.futo.inputmethod.latin.languagepack.LanguagePackageComponentCoordinate
import org.futo.inputmethod.latin.languagepack.RegisteredLanguagePackageComponent

const val CANDIDATE_RANKING_TASK_V1 = "candidate-ranking-v1"
const val FULL_CANDIDATE_LOGPROB_CAPABILITY = "full-candidate-logprob"

enum class CandidateRankingPurpose {
    Correction,
    Completion,
    NextWord,
}

/**
 * Describes how exact candidate replacement text must be assembled for a tokenizer.
 *
 * The caller still supplies exact replacement strings. This value tells tooling and diagnostics
 * which boundary convention the model was trained and validated with.
 */
enum class CandidateRankerBoundaryMode {
    ExactText,
    LeadingSeparator,
    TrailingSeparator,
}

data class CandidateRankerDescriptor(
    val component: LanguagePackageComponentCoordinate,
    val supportedLanguages: Set<String>,
    val maxContextTokens: Int,
    val maxBatchSize: Int,
    val boundaryMode: CandidateRankerBoundaryMode,
    val supportsRightContext: Boolean,
    val modelName: String,
    val modelRevision: String? = null,
)

data class CandidateRankerCandidate(
    val id: String,
    val displayText: String,
    val replacementText: String,
)

data class CandidateRankerRequest(
    val requestId: String,
    val languageTag: String,
    val purpose: CandidateRankingPurpose,
    val leftContext: String,
    val typedText: String,
    val rightContext: String,
    val candidates: List<CandidateRankerCandidate>,
    val rightContextTokenLimit: Int = 8,
)

data class CandidateRankerScoringPolicy(
    val lengthNormalizationExponent: Double = 0.7,
    val rightContextWeight: Double = 0.25,
) {
    init {
        require(lengthNormalizationExponent.isFinite() && lengthNormalizationExponent in 0.0..1.0) {
            "Length normalization exponent must be finite and between 0 and 1."
        }
        require(rightContextWeight.isFinite() && rightContextWeight in 0.0..1.0) {
            "Right-context weight must be finite and between 0 and 1."
        }
    }
}

data class CandidateRankerScore(
    val candidateId: String,
    val candidateLogProbability: Double,
    val candidateTokenCount: Int,
    val rightContextLogProbability: Double = 0.0,
    val rightContextTokenCount: Int = 0,
) {
    fun normalizedScore(policy: CandidateRankerScoringPolicy): Double {
        require(candidateLogProbability.isFinite()) { "Candidate log probability must be finite." }
        require(candidateTokenCount > 0) { "Candidate token count must be positive." }
        require(rightContextLogProbability.isFinite()) { "Right-context log probability must be finite." }
        require(rightContextTokenCount >= 0) { "Right-context token count must not be negative." }

        val candidateScore = candidateLogProbability /
            candidateTokenCount.toDouble().pow(policy.lengthNormalizationExponent)
        if (rightContextTokenCount == 0 || policy.rightContextWeight == 0.0) {
            return candidateScore
        }

        val continuationScore = rightContextLogProbability /
            rightContextTokenCount.toDouble().pow(policy.lengthNormalizationExponent)
        return candidateScore + continuationScore * policy.rightContextWeight
    }
}

data class CandidateRankerDiagnostics(
    val elapsedMicros: Long,
    val evaluatedCandidateTokens: Int,
    val evaluatedRightContextTokens: Int,
    val reusedPrefixTokens: Int,
    val batchCount: Int,
)

enum class CandidateRankerFailureCode {
    InvalidRequest,
    ComponentUnavailable,
    UnsupportedLanguage,
    UnsupportedTask,
    UnsupportedCapability,
    ModelProbeFailed,
    ModelLoadFailed,
    ContextTooLong,
    OutOfMemory,
    RuntimeFailure,
}

data class CandidateRankerFailure(
    val code: CandidateRankerFailureCode,
    val message: String,
    val recoverable: Boolean,
)

sealed class CandidateRankerOutcome {
    data class Success(
        val requestId: String,
        val descriptor: CandidateRankerDescriptor,
        val scores: List<CandidateRankerScore>,
        val diagnostics: CandidateRankerDiagnostics,
    ) : CandidateRankerOutcome()

    data class Failure(
        val requestId: String,
        val failure: CandidateRankerFailure,
    ) : CandidateRankerOutcome()
}

enum class CandidateRankerProbeIssueSeverity {
    Error,
    Warning,
}

data class CandidateRankerProbeIssue(
    val severity: CandidateRankerProbeIssueSeverity,
    val code: String,
    val message: String,
)

sealed class CandidateRankerProbeOutcome {
    data class Ready(
        val descriptor: CandidateRankerDescriptor,
        val issues: List<CandidateRankerProbeIssue> = emptyList(),
    ) : CandidateRankerProbeOutcome()

    data class Unavailable(
        val issues: List<CandidateRankerProbeIssue>,
    ) : CandidateRankerProbeOutcome()
}

/**
 * A loaded ranker instance. Implementations must be safe for sequential requests.
 *
 * Implementations may serialize concurrent calls internally. Coroutine cancellation must be
 * propagated as cancellation rather than converted into [CandidateRankerOutcome.Failure].
 */
interface CandidateRankerRuntime : Closeable {
    val descriptor: CandidateRankerDescriptor

    suspend fun rank(request: CandidateRankerRequest): CandidateRankerOutcome
}

/**
 * Runtime-specific bridge used to probe and open an installed context-ranker component.
 */
interface CandidateRankerRuntimeProvider {
    suspend fun probe(component: RegisteredLanguagePackageComponent): CandidateRankerProbeOutcome

    suspend fun open(component: RegisteredLanguagePackageComponent): CandidateRankerOpenOutcome
}

sealed class CandidateRankerOpenOutcome {
    data class Opened(val runtime: CandidateRankerRuntime) : CandidateRankerOpenOutcome()
    data class Failed(val failure: CandidateRankerFailure) : CandidateRankerOpenOutcome()
}
