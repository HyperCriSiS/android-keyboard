package org.futo.inputmethod.latin.languagepack.ranker

import org.futo.inputmethod.latin.languagepack.RegisteredLanguagePackageComponent

data class CandidateRankerProviderRegistration(
    val runtimeId: String,
    val supportedApiVersions: IntRange,
    val provider: CandidateRankerRuntimeProvider,
) {
    init {
        require(runtimeId.length in 3..160 && runtimeId.matches(Regex("^[a-z0-9]+(?:[._-][a-z0-9]+)*$"))) {
            "Runtime ID is invalid."
        }
        require(!supportedApiVersions.isEmpty() && supportedApiVersions.first > 0) {
            "Supported runtime API versions must be a non-empty positive range."
        }
    }
}

sealed class CandidateRankerProviderResolution {
    data class Resolved(val registration: CandidateRankerProviderRegistration) : CandidateRankerProviderResolution()
    data class Unavailable(val failure: CandidateRankerFailure) : CandidateRankerProviderResolution()
}

class CandidateRankerProviderRegistry(
    registrations: List<CandidateRankerProviderRegistration>,
) {
    private val registrationsById: Map<String, List<CandidateRankerProviderRegistration>>

    init {
        registrations.groupBy { it.runtimeId }.forEach { (runtimeId, entries) ->
            entries.forEachIndexed { index, left ->
                entries.drop(index + 1).forEach { right ->
                    require(left.supportedApiVersions.intersect(right.supportedApiVersions).isEmpty()) {
                        "Runtime provider ranges overlap for '$runtimeId'."
                    }
                }
            }
        }
        registrationsById = registrations
            .groupBy { it.runtimeId }
            .mapValues { (_, entries) -> entries.sortedByDescending { it.supportedApiVersions.last } }
    }

    fun resolve(component: RegisteredLanguagePackageComponent): CandidateRankerProviderResolution {
        val componentValidation = CandidateRankerValidator.validateComponent(component)
        if (!componentValidation.isValid) {
            return CandidateRankerProviderResolution.Unavailable(
                CandidateRankerFailure(
                    code = CandidateRankerFailureCode.ComponentUnavailable,
                    message = componentValidation.issues
                        .filter { it.severity == CandidateRankerValidationSeverity.Error }
                        .joinToString { it.message },
                    recoverable = false,
                ),
            )
        }

        val binding = component.component.runtime
            ?: return CandidateRankerProviderResolution.Unavailable(
                CandidateRankerFailure(
                    code = CandidateRankerFailureCode.ComponentUnavailable,
                    message = "Context-ranker component has no runtime binding.",
                    recoverable = false,
                ),
            )
        val matching = registrationsById[binding.id]
            ?.firstOrNull { binding.apiVersion in it.supportedApiVersions }
            ?: return CandidateRankerProviderResolution.Unavailable(
                CandidateRankerFailure(
                    code = CandidateRankerFailureCode.ComponentUnavailable,
                    message = "No runtime provider supports '${binding.id}' API ${binding.apiVersion}.",
                    recoverable = false,
                ),
            )

        return CandidateRankerProviderResolution.Resolved(matching)
    }

    suspend fun probe(component: RegisteredLanguagePackageComponent): CandidateRankerProbeOutcome {
        return when (val resolution = resolve(component)) {
            is CandidateRankerProviderResolution.Unavailable -> {
                CandidateRankerProbeOutcome.Unavailable(
                    issues = listOf(
                        CandidateRankerProbeIssue(
                            severity = CandidateRankerProbeIssueSeverity.Error,
                            code = "runtime_provider_unavailable",
                            message = resolution.failure.message,
                        ),
                    ),
                )
            }

            is CandidateRankerProviderResolution.Resolved -> {
                when (val outcome = resolution.registration.provider.probe(component)) {
                    is CandidateRankerProbeOutcome.Unavailable -> outcome
                    is CandidateRankerProbeOutcome.Ready -> {
                        val validation = CandidateRankerValidator.validateDescriptor(
                            component = component,
                            descriptor = outcome.descriptor,
                        )
                        if (validation.isValid) {
                            outcome
                        } else {
                            CandidateRankerProbeOutcome.Unavailable(
                                issues = validation.issues.map { issue ->
                                    CandidateRankerProbeIssue(
                                        severity = when (issue.severity) {
                                            CandidateRankerValidationSeverity.Error -> {
                                                CandidateRankerProbeIssueSeverity.Error
                                            }
                                            CandidateRankerValidationSeverity.Warning -> {
                                                CandidateRankerProbeIssueSeverity.Warning
                                            }
                                        },
                                        code = issue.code,
                                        message = issue.message,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    suspend fun open(component: RegisteredLanguagePackageComponent): CandidateRankerOpenOutcome {
        return when (val resolution = resolve(component)) {
            is CandidateRankerProviderResolution.Unavailable -> {
                CandidateRankerOpenOutcome.Failed(resolution.failure)
            }

            is CandidateRankerProviderResolution.Resolved -> {
                when (val outcome = resolution.registration.provider.open(component)) {
                    is CandidateRankerOpenOutcome.Failed -> outcome
                    is CandidateRankerOpenOutcome.Opened -> {
                        val validation = CandidateRankerValidator.validateDescriptor(
                            component = component,
                            descriptor = outcome.runtime.descriptor,
                        )
                        if (validation.isValid) {
                            outcome
                        } else {
                            outcome.runtime.close()
                            CandidateRankerOpenOutcome.Failed(
                                CandidateRankerFailure(
                                    code = CandidateRankerFailureCode.ModelLoadFailed,
                                    message = validation.issues
                                        .filter { it.severity == CandidateRankerValidationSeverity.Error }
                                        .joinToString { it.message },
                                    recoverable = false,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun IntRange.intersect(other: IntRange): IntRange {
    val start = maxOf(first, other.first)
    val end = minOf(last, other.last)
    return if (start <= end) start..end else IntRange.EMPTY
}
