package org.futo.inputmethod.latin.languagepack.ranker

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.futo.inputmethod.latin.languagepack.LanguagePackageRuntimeBinding
import org.futo.inputmethod.latin.languagepack.RegisteredLanguagePackageComponent

const val GGUF_CAUSAL_RANKER_RUNTIME_ID = "gguf-causal-ranker"
const val GGUF_CAUSAL_RANKER_RUNTIME_API_V1 = 1

@Serializable
enum class GgufCandidateBoundaryMode {
    @SerialName("exact-text")
    ExactText,

    @SerialName("leading-separator")
    LeadingSeparator,

    @SerialName("trailing-separator")
    TrailingSeparator,
}

@Serializable
enum class GgufBosPolicy {
    @SerialName("model-default")
    ModelDefault,

    @SerialName("always")
    Always,

    @SerialName("never")
    Never,
}

@Serializable
enum class GgufContextTruncation {
    @SerialName("keep-last")
    KeepLast,

    @SerialName("reject")
    Reject,
}

@Serializable
data class GgufCandidateRankerParameters(
    val boundaryMode: GgufCandidateBoundaryMode,
    val maxContextTokens: Int = 256,
    val maxBatchSize: Int = 16,
    val supportsRightContext: Boolean = true,
    val bosPolicy: GgufBosPolicy = GgufBosPolicy.ModelDefault,
    val addEos: Boolean = false,
    val contextTruncation: GgufContextTruncation = GgufContextTruncation.KeepLast,
)

data class GgufCandidateRankerConfigurationIssue(
    val code: String,
    val message: String,
)

sealed class GgufCandidateRankerConfigurationResult {
    data class Valid(val parameters: GgufCandidateRankerParameters) : GgufCandidateRankerConfigurationResult()
    data class Invalid(val issues: List<GgufCandidateRankerConfigurationIssue>) : GgufCandidateRankerConfigurationResult()
}

object GgufCandidateRankerConfiguration {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
    }

    fun parse(binding: LanguagePackageRuntimeBinding?): GgufCandidateRankerConfigurationResult {
        if (binding == null) {
            return invalid("missing_runtime_binding", "Component has no runtime binding.")
        }
        if (binding.id != GGUF_CAUSAL_RANKER_RUNTIME_ID) {
            return invalid(
                "wrong_runtime_id",
                "Runtime '${binding.id}' is not $GGUF_CAUSAL_RANKER_RUNTIME_ID.",
            )
        }
        if (binding.apiVersion != GGUF_CAUSAL_RANKER_RUNTIME_API_V1) {
            return invalid(
                "unsupported_runtime_api",
                "GGUF candidate ranker API ${binding.apiVersion} is unsupported.",
            )
        }

        val parameters = try {
            json.decodeFromString<GgufCandidateRankerParameters>(binding.parameters.toString())
        } catch (exception: SerializationException) {
            return invalid(
                "invalid_runtime_parameters",
                exception.message ?: "GGUF candidate ranker parameters are invalid.",
            )
        } catch (exception: IllegalArgumentException) {
            return invalid(
                "invalid_runtime_parameters",
                exception.message ?: "GGUF candidate ranker parameters are invalid.",
            )
        }

        val issues = mutableListOf<GgufCandidateRankerConfigurationIssue>()
        if (parameters.maxContextTokens !in 16..32768) {
            issues += GgufCandidateRankerConfigurationIssue(
                "invalid_max_context_tokens",
                "maxContextTokens must be between 16 and 32768.",
            )
        }
        if (parameters.maxBatchSize !in 1..64) {
            issues += GgufCandidateRankerConfigurationIssue(
                "invalid_max_batch_size",
                "maxBatchSize must be between 1 and 64.",
            )
        }
        if (parameters.addEos && parameters.supportsRightContext) {
            issues += GgufCandidateRankerConfigurationIssue(
                "eos_conflicts_with_right_context",
                "addEos cannot be enabled when right-context scoring is enabled.",
            )
        }

        return if (issues.isEmpty()) {
            GgufCandidateRankerConfigurationResult.Valid(parameters)
        } else {
            GgufCandidateRankerConfigurationResult.Invalid(issues)
        }
    }

    fun descriptor(
        component: RegisteredLanguagePackageComponent,
        parameters: GgufCandidateRankerParameters,
        modelName: String = component.component.name,
        modelRevision: String? = null,
    ): CandidateRankerDescriptor {
        return CandidateRankerDescriptor(
            component = component.coordinate,
            supportedLanguages = component.component.languages.toSet(),
            maxContextTokens = parameters.maxContextTokens,
            maxBatchSize = parameters.maxBatchSize,
            boundaryMode = when (parameters.boundaryMode) {
                GgufCandidateBoundaryMode.ExactText -> CandidateRankerBoundaryMode.ExactText
                GgufCandidateBoundaryMode.LeadingSeparator -> CandidateRankerBoundaryMode.LeadingSeparator
                GgufCandidateBoundaryMode.TrailingSeparator -> CandidateRankerBoundaryMode.TrailingSeparator
            },
            supportsRightContext = parameters.supportsRightContext,
            modelName = modelName,
            modelRevision = modelRevision,
        )
    }

    private fun invalid(code: String, message: String): GgufCandidateRankerConfigurationResult.Invalid {
        return GgufCandidateRankerConfigurationResult.Invalid(
            listOf(GgufCandidateRankerConfigurationIssue(code, message)),
        )
    }
}
