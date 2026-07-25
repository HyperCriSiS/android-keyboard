package org.futo.inputmethod.latin.languagepack.ranker

import java.util.Locale
import org.futo.inputmethod.latin.languagepack.LanguagePackageActivation
import org.futo.inputmethod.latin.languagepack.LanguagePackageComponentKind
import org.futo.inputmethod.latin.languagepack.RegisteredLanguagePackageComponent

private const val MAX_REQUEST_ID_LENGTH = 128
private const val MAX_CANDIDATE_ID_LENGTH = 128
private const val MAX_CANDIDATES_PER_REQUEST = 64
private const val MAX_CONTEXT_UTF8_BYTES = 64 * 1024
private const val MAX_TYPED_TEXT_UTF8_BYTES = 4 * 1024
private const val MAX_CANDIDATE_UTF8_BYTES = 4 * 1024
private const val MAX_RIGHT_CONTEXT_TOKEN_LIMIT = 64

enum class CandidateRankerValidationSeverity {
    Error,
    Warning,
}

data class CandidateRankerValidationIssue(
    val severity: CandidateRankerValidationSeverity,
    val code: String,
    val path: String,
    val message: String,
)

data class CandidateRankerValidationResult(
    val issues: List<CandidateRankerValidationIssue>,
) {
    val isValid: Boolean
        get() = issues.none { it.severity == CandidateRankerValidationSeverity.Error }
}

object CandidateRankerValidator {
    fun validateComponent(component: RegisteredLanguagePackageComponent): CandidateRankerValidationResult {
        val issues = mutableListOf<CandidateRankerValidationIssue>()

        if (component.component.kind != LanguagePackageComponentKind.ContextRanker) {
            issues.error(
                "wrong_component_kind",
                "component.kind",
                "Candidate ranker runtime requires a context-ranker component.",
            )
        }
        if (component.component.activation != LanguagePackageActivation.Exclusive) {
            issues.error(
                "wrong_component_activation",
                "component.activation",
                "Context rankers must use exclusive activation.",
            )
        }
        if (CANDIDATE_RANKING_TASK_V1 !in component.component.tasks) {
            issues.error(
                "missing_candidate_ranking_task",
                "component.tasks",
                "Component does not declare $CANDIDATE_RANKING_TASK_V1.",
            )
        }
        if (FULL_CANDIDATE_LOGPROB_CAPABILITY !in component.component.capabilities.required) {
            issues.error(
                "missing_full_logprob_capability",
                "component.capabilities.required",
                "Component must require $FULL_CANDIDATE_LOGPROB_CAPABILITY.",
            )
        }
        if (!component.payloadFile.isFile) {
            issues.error(
                "missing_payload",
                "component.payload",
                "Installed ranker payload is missing.",
            )
        } else if (component.payloadFile.length() != component.component.payload.sizeBytes) {
            issues.error(
                "payload_size_changed",
                "component.payload",
                "Installed ranker payload size no longer matches its manifest.",
            )
        }

        return CandidateRankerValidationResult(issues)
    }

    fun validateDescriptor(
        component: RegisteredLanguagePackageComponent,
        descriptor: CandidateRankerDescriptor,
    ): CandidateRankerValidationResult {
        val issues = validateComponent(component).issues.toMutableList()

        if (descriptor.component != component.coordinate) {
            issues.error(
                "descriptor_component_mismatch",
                "descriptor.component",
                "Runtime descriptor belongs to ${descriptor.component}, expected ${component.coordinate}.",
            )
        }
        if (descriptor.modelName.isBlank()) {
            issues.error("blank_model_name", "descriptor.modelName", "Model name must not be blank.")
        }
        if (descriptor.maxContextTokens <= 0) {
            issues.error(
                "invalid_max_context_tokens",
                "descriptor.maxContextTokens",
                "Maximum context tokens must be positive.",
            )
        }
        if (descriptor.maxBatchSize <= 0 || descriptor.maxBatchSize > MAX_CANDIDATES_PER_REQUEST) {
            issues.error(
                "invalid_max_batch_size",
                "descriptor.maxBatchSize",
                "Maximum batch size must be between 1 and $MAX_CANDIDATES_PER_REQUEST.",
            )
        }
        if (descriptor.supportedLanguages.isEmpty()) {
            issues.error(
                "missing_supported_language",
                "descriptor.supportedLanguages",
                "Descriptor must support at least one language.",
            )
        }

        return CandidateRankerValidationResult(issues)
    }

    fun validateRequest(
        request: CandidateRankerRequest,
        descriptor: CandidateRankerDescriptor,
    ): CandidateRankerValidationResult {
        val issues = mutableListOf<CandidateRankerValidationIssue>()

        if (request.requestId.isBlank() || request.requestId.length > MAX_REQUEST_ID_LENGTH) {
            issues.error(
                "invalid_request_id",
                "requestId",
                "Request ID must contain 1 to $MAX_REQUEST_ID_LENGTH characters.",
            )
        }
        if (!languageTagsOverlap(request.languageTag, descriptor.supportedLanguages)) {
            issues.error(
                "unsupported_language",
                "languageTag",
                "Ranker does not support '${request.languageTag}'.",
            )
        }
        if (request.leftContext.utf8Size() > MAX_CONTEXT_UTF8_BYTES) {
            issues.error(
                "left_context_too_large",
                "leftContext",
                "Left context exceeds $MAX_CONTEXT_UTF8_BYTES UTF-8 bytes.",
            )
        }
        if (request.rightContext.utf8Size() > MAX_CONTEXT_UTF8_BYTES) {
            issues.error(
                "right_context_too_large",
                "rightContext",
                "Right context exceeds $MAX_CONTEXT_UTF8_BYTES UTF-8 bytes.",
            )
        }
        if (request.typedText.utf8Size() > MAX_TYPED_TEXT_UTF8_BYTES) {
            issues.error(
                "typed_text_too_large",
                "typedText",
                "Typed text exceeds $MAX_TYPED_TEXT_UTF8_BYTES UTF-8 bytes.",
            )
        }
        if (request.candidates.isEmpty() || request.candidates.size > MAX_CANDIDATES_PER_REQUEST) {
            issues.error(
                "invalid_candidate_count",
                "candidates",
                "Candidate count must be between 1 and $MAX_CANDIDATES_PER_REQUEST.",
            )
        }
        if (request.rightContextTokenLimit !in 0..MAX_RIGHT_CONTEXT_TOKEN_LIMIT) {
            issues.error(
                "invalid_right_context_token_limit",
                "rightContextTokenLimit",
                "Right-context token limit must be between 0 and $MAX_RIGHT_CONTEXT_TOKEN_LIMIT.",
            )
        }
        if (!descriptor.supportsRightContext && request.rightContext.isNotEmpty() &&
            request.rightContextTokenLimit > 0
        ) {
            issues.warning(
                "right_context_not_supported",
                "rightContext",
                "The selected ranker will ignore right context.",
            )
        }

        val seenIds = mutableSetOf<String>()
        request.candidates.forEachIndexed { index, candidate ->
            val path = "candidates[$index]"
            if (candidate.id.isBlank() || candidate.id.length > MAX_CANDIDATE_ID_LENGTH) {
                issues.error(
                    "invalid_candidate_id",
                    "$path.id",
                    "Candidate ID must contain 1 to $MAX_CANDIDATE_ID_LENGTH characters.",
                )
            } else if (!seenIds.add(candidate.id)) {
                issues.error(
                    "duplicate_candidate_id",
                    "$path.id",
                    "Candidate ID '${candidate.id}' occurs more than once.",
                )
            }
            if (candidate.displayText.isBlank()) {
                issues.error(
                    "blank_candidate_display_text",
                    "$path.displayText",
                    "Candidate display text must not be blank.",
                )
            }
            if (candidate.replacementText.isEmpty()) {
                issues.error(
                    "empty_candidate_replacement",
                    "$path.replacementText",
                    "Candidate replacement text must not be empty.",
                )
            }
            if (candidate.replacementText.utf8Size() > MAX_CANDIDATE_UTF8_BYTES) {
                issues.error(
                    "candidate_too_large",
                    "$path.replacementText",
                    "Candidate replacement exceeds $MAX_CANDIDATE_UTF8_BYTES UTF-8 bytes.",
                )
            }

            when (descriptor.boundaryMode) {
                CandidateRankerBoundaryMode.ExactText -> Unit
                CandidateRankerBoundaryMode.LeadingSeparator -> {
                    if (candidate.replacementText.firstOrNull()?.isWhitespace() != true) {
                        issues.warning(
                            "missing_leading_separator",
                            "$path.replacementText",
                            "Model expects candidate text with a leading separator.",
                        )
                    }
                }
                CandidateRankerBoundaryMode.TrailingSeparator -> {
                    if (candidate.replacementText.lastOrNull()?.isWhitespace() != true) {
                        issues.warning(
                            "missing_trailing_separator",
                            "$path.replacementText",
                            "Model expects candidate text with a trailing separator.",
                        )
                    }
                }
            }
        }

        return CandidateRankerValidationResult(issues)
    }

    fun validateSuccess(
        request: CandidateRankerRequest,
        success: CandidateRankerOutcome.Success,
    ): CandidateRankerValidationResult {
        val issues = mutableListOf<CandidateRankerValidationIssue>()

        if (success.requestId != request.requestId) {
            issues.error(
                "result_request_id_mismatch",
                "requestId",
                "Result request ID does not match the request.",
            )
        }

        val expectedIds = request.candidates.map { it.id }.toSet()
        val actualIds = mutableSetOf<String>()
        success.scores.forEachIndexed { index, score ->
            val path = "scores[$index]"
            if (!actualIds.add(score.candidateId)) {
                issues.error(
                    "duplicate_score_id",
                    "$path.candidateId",
                    "Candidate '${score.candidateId}' was scored more than once.",
                )
            }
            if (score.candidateId !in expectedIds) {
                issues.error(
                    "unknown_score_id",
                    "$path.candidateId",
                    "Score refers to unknown candidate '${score.candidateId}'.",
                )
            }
            if (!score.candidateLogProbability.isFinite()) {
                issues.error(
                    "invalid_candidate_log_probability",
                    "$path.candidateLogProbability",
                    "Candidate log probability must be finite.",
                )
            }
            if (score.candidateTokenCount <= 0) {
                issues.error(
                    "invalid_candidate_token_count",
                    "$path.candidateTokenCount",
                    "Candidate token count must be positive.",
                )
            }
            if (!score.rightContextLogProbability.isFinite()) {
                issues.error(
                    "invalid_right_context_log_probability",
                    "$path.rightContextLogProbability",
                    "Right-context log probability must be finite.",
                )
            }
            if (score.rightContextTokenCount < 0) {
                issues.error(
                    "invalid_right_context_token_count",
                    "$path.rightContextTokenCount",
                    "Right-context token count must not be negative.",
                )
            }
        }

        val missingIds = expectedIds - actualIds
        if (missingIds.isNotEmpty()) {
            issues.error(
                "missing_candidate_scores",
                "scores",
                "No score was returned for: ${missingIds.joinToString()}.",
            )
        }

        val diagnostics = success.diagnostics
        if (diagnostics.elapsedMicros < 0L || diagnostics.evaluatedCandidateTokens < 0 ||
            diagnostics.evaluatedRightContextTokens < 0 || diagnostics.reusedPrefixTokens < 0 ||
            diagnostics.batchCount <= 0
        ) {
            issues.error(
                "invalid_diagnostics",
                "diagnostics",
                "Runtime diagnostics contain negative counters or a non-positive batch count.",
            )
        }

        return CandidateRankerValidationResult(issues)
    }

    private fun MutableList<CandidateRankerValidationIssue>.error(
        code: String,
        path: String,
        message: String,
    ) {
        this += CandidateRankerValidationIssue(
            severity = CandidateRankerValidationSeverity.Error,
            code = code,
            path = path,
            message = message,
        )
    }

    private fun MutableList<CandidateRankerValidationIssue>.warning(
        code: String,
        path: String,
        message: String,
    ) {
        this += CandidateRankerValidationIssue(
            severity = CandidateRankerValidationSeverity.Warning,
            code = code,
            path = path,
            message = message,
        )
    }
}

object CandidateRankerBatchPlanner {
    fun plan(
        candidates: List<CandidateRankerCandidate>,
        maxBatchSize: Int,
    ): List<List<CandidateRankerCandidate>> {
        require(maxBatchSize > 0) { "Maximum batch size must be positive." }
        return candidates.chunked(maxBatchSize)
    }
}

private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

private fun languageTagsOverlap(target: String, supported: Set<String>): Boolean {
    val normalizedTarget = target.lowercase(Locale.ROOT)
    return supported.any { candidate ->
        val normalizedCandidate = candidate.lowercase(Locale.ROOT)
        normalizedTarget == normalizedCandidate ||
            normalizedTarget.startsWith("$normalizedCandidate-") ||
            normalizedCandidate.startsWith("$normalizedTarget-")
    }
}
