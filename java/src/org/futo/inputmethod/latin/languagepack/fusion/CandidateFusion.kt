package org.futo.inputmethod.latin.languagepack.fusion

import kotlin.math.exp

/**
 * Model-independent signals produced before score fusion.
 *
 * Probabilistic signals use [0, 1]. Missing optional signals remain null and contribute no evidence.
 * [rankerScore] is the normalized complete-candidate score from CandidateRankerScore.
 */
data class CandidateFusionInput(
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
)

data class CandidateFusionCalibration(
    val rankerCenter: Double = -4.0,
    val rankerScale: Double = 1.5,
) {
    init {
        require(rankerCenter.isFinite()) { "Ranker center must be finite." }
        require(rankerScale.isFinite() && rankerScale > 0.0) {
            "Ranker scale must be finite and positive."
        }
    }
}

data class CandidateFusionWeights(
    val generator: Double = 1.6,
    val ranker: Double = 1.4,
    val touch: Double = 0.8,
    val frequency: Double = 0.5,
    val personalization: Double = 0.9,
    val editRisk: Double = 1.5,
    val exactDictionaryMatchBonus: Double = 0.35,
    val typedTextBias: Double = 0.20,
) {
    init {
        listOf(
            generator,
            ranker,
            touch,
            frequency,
            personalization,
            editRisk,
            exactDictionaryMatchBonus,
            typedTextBias,
        ).forEach { require(it.isFinite()) { "Fusion weights must be finite." } }
        require(generator >= 0.0 && ranker >= 0.0 && touch >= 0.0 && frequency >= 0.0 &&
            personalization >= 0.0 && editRisk >= 0.0
        ) {
            "Signal and risk weights must not be negative."
        }
    }
}

data class CandidateFusionPolicy(
    val calibration: CandidateFusionCalibration = CandidateFusionCalibration(),
    val weights: CandidateFusionWeights = CandidateFusionWeights(),
)

data class FusedCandidate(
    val input: CandidateFusionInput,
    val linearScore: Double,
    val confidence: Double,
    val evidence: CandidateFusionEvidence,
)

data class CandidateFusionEvidence(
    val generator: Double,
    val ranker: Double,
    val touch: Double,
    val frequency: Double,
    val personalization: Double,
    val editRiskPenalty: Double,
    val exactDictionaryBonus: Double,
    val typedTextBias: Double,
)

data class AutocorrectPolicy(
    val minimumCandidateConfidence: Double = 0.68,
    val minimumTypedMargin: Double = 0.12,
    val minimumRunnerUpMargin: Double = 0.04,
    val maximumEditRisk: Double = 0.72,
    val requireGeneratorAndRankerAgreement: Boolean = false,
) {
    init {
        listOf(
            minimumCandidateConfidence,
            minimumTypedMargin,
            minimumRunnerUpMargin,
            maximumEditRisk,
        ).forEach {
            require(it.isFinite() && it in 0.0..1.0) {
                "Autocorrect thresholds must be finite values between 0 and 1."
            }
        }
    }
}

enum class AutocorrectDecisionReason {
    Correct,
    KeepTypedTop,
    NoCorrectionCandidate,
    CandidateBlocked,
    CandidateOffensive,
    CandidateConfidenceTooLow,
    TypedMarginTooSmall,
    RunnerUpMarginTooSmall,
    EditRiskTooHigh,
    GeneratorRankerDisagreement,
}

data class AutocorrectDecision(
    val correction: FusedCandidate?,
    val reason: AutocorrectDecisionReason,
    val typedCandidate: FusedCandidate?,
    val runnerUp: FusedCandidate?,
)

object ExperimentalCandidateFusion {
    fun fuse(
        candidates: List<CandidateFusionInput>,
        policy: CandidateFusionPolicy = CandidateFusionPolicy(),
    ): List<FusedCandidate> {
        require(candidates.isNotEmpty()) { "At least one candidate is required." }
        require(candidates.map { it.id }.toSet().size == candidates.size) {
            "Candidate IDs must be unique."
        }

        return candidates.map { input -> fuseOne(input, policy) }
            .sortedWith(
                compareByDescending<FusedCandidate> { it.linearScore }
                    .thenByDescending { it.input.isTypedText }
                    .thenBy { it.input.id },
            )
    }

    fun decideAutocorrect(
        fused: List<FusedCandidate>,
        policy: AutocorrectPolicy = AutocorrectPolicy(),
    ): AutocorrectDecision {
        require(fused.isNotEmpty()) { "At least one fused candidate is required." }

        val ordered = fused.sortedWith(
            compareByDescending<FusedCandidate> { it.linearScore }
                .thenByDescending { it.input.isTypedText }
                .thenBy { it.input.id },
        )
        val typed = ordered.firstOrNull { it.input.isTypedText }
        val correction = ordered.firstOrNull { !it.input.isTypedText }
            ?: return AutocorrectDecision(null, AutocorrectDecisionReason.NoCorrectionCandidate, typed, null)
        val runnerUp = ordered.firstOrNull { it.input.id != correction.input.id }

        if (ordered.first().input.isTypedText) {
            return AutocorrectDecision(null, AutocorrectDecisionReason.KeepTypedTop, typed, correction)
        }
        if (correction.input.isBlocked) {
            return AutocorrectDecision(null, AutocorrectDecisionReason.CandidateBlocked, typed, runnerUp)
        }
        if (correction.input.isPossiblyOffensive) {
            return AutocorrectDecision(null, AutocorrectDecisionReason.CandidateOffensive, typed, runnerUp)
        }
        if (correction.confidence < policy.minimumCandidateConfidence) {
            return AutocorrectDecision(
                null,
                AutocorrectDecisionReason.CandidateConfidenceTooLow,
                typed,
                runnerUp,
            )
        }
        if (correction.input.editRisk > policy.maximumEditRisk) {
            return AutocorrectDecision(null, AutocorrectDecisionReason.EditRiskTooHigh, typed, runnerUp)
        }

        val typedMargin = typed?.let { correction.confidence - it.confidence } ?: 1.0
        if (typedMargin < policy.minimumTypedMargin) {
            return AutocorrectDecision(null, AutocorrectDecisionReason.TypedMarginTooSmall, typed, runnerUp)
        }

        val runnerUpMargin = runnerUp?.let { correction.confidence - it.confidence } ?: 1.0
        if (runnerUpMargin < policy.minimumRunnerUpMargin) {
            return AutocorrectDecision(null, AutocorrectDecisionReason.RunnerUpMarginTooSmall, typed, runnerUp)
        }

        if (policy.requireGeneratorAndRankerAgreement && !generatorAndRankerAgree(fused, correction)) {
            return AutocorrectDecision(
                null,
                AutocorrectDecisionReason.GeneratorRankerDisagreement,
                typed,
                runnerUp,
            )
        }

        return AutocorrectDecision(
            correction = correction,
            reason = AutocorrectDecisionReason.Correct,
            typedCandidate = typed,
            runnerUp = runnerUp,
        )
    }

    private fun fuseOne(
        input: CandidateFusionInput,
        policy: CandidateFusionPolicy,
    ): FusedCandidate {
        validateProbability(input.generatorConfidence, "generatorConfidence")
        validateProbability(input.editRisk, "editRisk")
        input.touchConfidence?.let { validateProbability(it, "touchConfidence") }
        input.frequencyConfidence?.let { validateProbability(it, "frequencyConfidence") }
        input.personalizationConfidence?.let {
            validateProbability(it, "personalizationConfidence")
        }
        require(input.rankerScore == null || input.rankerScore.isFinite()) {
            "rankerScore must be finite when present."
        }

        val generatorEvidence = centeredProbability(input.generatorConfidence) * policy.weights.generator
        val rankerEvidence = input.rankerScore?.let {
            centeredProbability(
                sigmoid((it - policy.calibration.rankerCenter) / policy.calibration.rankerScale),
            ) * policy.weights.ranker
        } ?: 0.0
        val touchEvidence = input.touchConfidence?.let {
            centeredProbability(it) * policy.weights.touch
        } ?: 0.0
        val frequencyEvidence = input.frequencyConfidence?.let {
            centeredProbability(it) * policy.weights.frequency
        } ?: 0.0
        val personalizationEvidence = input.personalizationConfidence?.let {
            centeredProbability(it) * policy.weights.personalization
        } ?: 0.0
        val editRiskPenalty = input.editRisk * policy.weights.editRisk
        val exactDictionaryBonus = if (input.isExactDictionaryMatch) {
            policy.weights.exactDictionaryMatchBonus
        } else {
            0.0
        }
        val typedBias = if (input.isTypedText) policy.weights.typedTextBias else 0.0

        val linearScore = generatorEvidence + rankerEvidence + touchEvidence +
            frequencyEvidence + personalizationEvidence - editRiskPenalty +
            exactDictionaryBonus + typedBias

        return FusedCandidate(
            input = input,
            linearScore = linearScore,
            confidence = sigmoid(linearScore),
            evidence = CandidateFusionEvidence(
                generator = generatorEvidence,
                ranker = rankerEvidence,
                touch = touchEvidence,
                frequency = frequencyEvidence,
                personalization = personalizationEvidence,
                editRiskPenalty = editRiskPenalty,
                exactDictionaryBonus = exactDictionaryBonus,
                typedTextBias = typedBias,
            ),
        )
    }

    private fun generatorAndRankerAgree(
        fused: List<FusedCandidate>,
        correction: FusedCandidate,
    ): Boolean {
        if (correction.input.rankerScore == null) return false
        val generatorTop = fused
            .filterNot { it.input.isTypedText || it.input.isBlocked }
            .maxWithOrNull(
                compareBy<FusedCandidate> { it.input.generatorConfidence }
                    .thenBy { it.input.id },
            )
        val rankerTop = fused
            .filterNot { it.input.isTypedText || it.input.isBlocked || it.input.rankerScore == null }
            .maxWithOrNull(
                compareBy<FusedCandidate> { it.input.rankerScore }
                    .thenBy { it.input.id },
            )
        return generatorTop?.input?.id == correction.input.id &&
            rankerTop?.input?.id == correction.input.id
    }

    private fun validateProbability(value: Double, name: String) {
        require(value.isFinite() && value in 0.0..1.0) {
            "$name must be a finite value between 0 and 1."
        }
    }

    private fun centeredProbability(value: Double): Double = value * 2.0 - 1.0

    private fun sigmoid(value: Double): Double {
        return if (value >= 0.0) {
            1.0 / (1.0 + exp(-value))
        } else {
            val exponential = exp(value)
            exponential / (1.0 + exponential)
        }
    }
}
