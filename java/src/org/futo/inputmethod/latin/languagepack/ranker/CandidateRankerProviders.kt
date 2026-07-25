package org.futo.inputmethod.latin.languagepack.ranker

internal fun createDefaultCandidateRankerProviderRegistry(): CandidateRankerProviderRegistry {
    return CandidateRankerProviderRegistry(
        registrations = listOf(
            CandidateRankerProviderRegistration(
                runtimeId = GGUF_CAUSAL_RANKER_RUNTIME_ID,
                supportedApiVersions = GGUF_CAUSAL_RANKER_RUNTIME_API_V1..GGUF_CAUSAL_RANKER_RUNTIME_API_V1,
                provider = GgufCandidateRankerRuntimeProvider(),
            ),
        ),
    )
}
