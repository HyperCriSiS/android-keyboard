package org.futo.inputmethod.latin.languagepack.ranker

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.futo.inputmethod.latin.languagepack.LanguagePackageRuntimeBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class GgufCandidateRankerConfigurationTest {
    @Test
    fun validConfigurationUsesDefaults() {
        val binding = LanguagePackageRuntimeBinding(
            id = GGUF_CAUSAL_RANKER_RUNTIME_ID,
            apiVersion = GGUF_CAUSAL_RANKER_RUNTIME_API_V1,
            parameters = buildJsonObject {
                put("boundaryMode", "leading-separator")
            },
        )

        val result = GgufCandidateRankerConfiguration.parse(binding)

        assertTrue(result is GgufCandidateRankerConfigurationResult.Valid)
        val parameters = (result as GgufCandidateRankerConfigurationResult.Valid).parameters
        assertEquals(GgufCandidateBoundaryMode.LeadingSeparator, parameters.boundaryMode)
        assertEquals(256, parameters.maxContextTokens)
        assertEquals(16, parameters.maxBatchSize)
        assertTrue(parameters.supportsRightContext)
        assertEquals(GgufBosPolicy.ModelDefault, parameters.bosPolicy)
    }

    @Test
    fun wrongRuntimeAndUnknownParametersAreRejected() {
        val wrongRuntime = GgufCandidateRankerConfiguration.parse(
            LanguagePackageRuntimeBinding(
                id = "other-ranker",
                parameters = buildJsonObject { put("boundaryMode", "exact-text") },
            ),
        )
        val unknownParameter = GgufCandidateRankerConfiguration.parse(
            LanguagePackageRuntimeBinding(
                id = GGUF_CAUSAL_RANKER_RUNTIME_ID,
                parameters = buildJsonObject {
                    put("boundaryMode", "exact-text")
                    put("unknown", true)
                },
            ),
        )

        assertTrue(wrongRuntime is GgufCandidateRankerConfigurationResult.Invalid)
        assertTrue(unknownParameter is GgufCandidateRankerConfigurationResult.Invalid)
    }

    @Test
    fun invalidLimitsAndEosRightContextConflictAreRejected() {
        val result = GgufCandidateRankerConfiguration.parse(
            LanguagePackageRuntimeBinding(
                id = GGUF_CAUSAL_RANKER_RUNTIME_ID,
                parameters = buildJsonObject {
                    put("boundaryMode", "trailing-separator")
                    put("maxContextTokens", 8)
                    put("maxBatchSize", 100)
                    put("supportsRightContext", true)
                    put("addEos", true)
                },
            ),
        )

        assertTrue(result is GgufCandidateRankerConfigurationResult.Invalid)
        val codes = (result as GgufCandidateRankerConfigurationResult.Invalid).issues
            .map { it.code }
            .toSet()
        assertTrue("invalid_max_context_tokens" in codes)
        assertTrue("invalid_max_batch_size" in codes)
        assertTrue("eos_conflicts_with_right_context" in codes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedRuntimeIdIsRejectedByManifestModel() {
        LanguagePackageRuntimeBinding(
            id = "Invalid Runtime ID",
            parameters = buildJsonObject { put("boundaryMode", "exact-text") },
        )
    }
}
