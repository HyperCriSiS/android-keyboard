package org.futo.inputmethod.latin.languagepack.ranker

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.languagepack.RegisteredLanguagePackageComponent
import org.futo.inputmethod.latin.utils.JniUtils
import org.futo.inputmethod.latin.xlm.LanguageModelScope

internal class GgufCandidateRankerNativeBridge {
    external fun openNative(
        modelPath: String,
        maxContextTokens: Int,
        maxBatchSize: Int,
        supportsRightContext: Boolean,
        bosPolicy: Int,
        addEos: Boolean,
        contextTruncation: Int,
        outError: Array<String?>,
    ): Long

    external fun closeNative(state: Long)

    external fun scoreNative(
        state: Long,
        leftContext: String,
        rightContext: String,
        candidates: Array<String>,
        rightContextTokenLimit: Int,
        outCandidateLogProbabilities: DoubleArray,
        outCandidateTokenCounts: IntArray,
        outRightContextLogProbabilities: DoubleArray,
        outRightContextTokenCounts: IntArray,
        outDiagnostics: LongArray,
    ): String?
}

@OptIn(DelicateCoroutinesApi::class)
internal class GgufCandidateRankerRuntimeProvider(
    private val bridge: GgufCandidateRankerNativeBridge = GgufCandidateRankerNativeBridge(),
) : CandidateRankerRuntimeProvider {
    override suspend fun probe(
        component: RegisteredLanguagePackageComponent,
    ): CandidateRankerProbeOutcome = withContext(LanguageModelScope) {
        when (val configuration = GgufCandidateRankerConfiguration.parse(component.component.runtime)) {
            is GgufCandidateRankerConfigurationResult.Invalid -> {
                CandidateRankerProbeOutcome.Unavailable(
                    configuration.issues.map {
                        CandidateRankerProbeIssue(
                            severity = CandidateRankerProbeIssueSeverity.Error,
                            code = it.code,
                            message = it.message,
                        )
                    },
                )
            }

            is GgufCandidateRankerConfigurationResult.Valid -> {
                ensureNativeLibraryLoaded()
                val opened = openNative(component, configuration.parameters)
                if (opened.state == 0L) {
                    CandidateRankerProbeOutcome.Unavailable(
                        listOf(
                            CandidateRankerProbeIssue(
                                severity = CandidateRankerProbeIssueSeverity.Error,
                                code = "gguf_probe_failed",
                                message = opened.error ?: "Could not open GGUF candidate ranker.",
                            ),
                        ),
                    )
                } else {
                    bridge.closeNative(opened.state)
                    CandidateRankerProbeOutcome.Ready(
                        descriptor = GgufCandidateRankerConfiguration.descriptor(
                            component = component,
                            parameters = configuration.parameters,
                            modelRevision = component.component.version,
                        ),
                    )
                }
            }
        }
    }

    override suspend fun open(
        component: RegisteredLanguagePackageComponent,
    ): CandidateRankerOpenOutcome = withContext(LanguageModelScope) {
        when (val configuration = GgufCandidateRankerConfiguration.parse(component.component.runtime)) {
            is GgufCandidateRankerConfigurationResult.Invalid -> {
                CandidateRankerOpenOutcome.Failed(
                    CandidateRankerFailure(
                        code = CandidateRankerFailureCode.ModelLoadFailed,
                        message = configuration.issues.joinToString { it.message },
                        recoverable = false,
                    ),
                )
            }

            is GgufCandidateRankerConfigurationResult.Valid -> {
                ensureNativeLibraryLoaded()
                val opened = openNative(component, configuration.parameters)
                if (opened.state == 0L) {
                    CandidateRankerOpenOutcome.Failed(
                        CandidateRankerFailure(
                            code = CandidateRankerFailureCode.ModelLoadFailed,
                            message = opened.error ?: "Could not open GGUF candidate ranker.",
                            recoverable = false,
                        ),
                    )
                } else {
                    CandidateRankerOpenOutcome.Opened(
                        GgufCandidateRankerRuntime(
                            bridge = bridge,
                            initialState = opened.state,
                            descriptor = GgufCandidateRankerConfiguration.descriptor(
                                component = component,
                                parameters = configuration.parameters,
                                modelRevision = component.component.version,
                            ),
                        ),
                    )
                }
            }
        }
    }

    private suspend fun ensureNativeLibraryLoaded() {
        withContext(Dispatchers.Main) {
            JniUtils.loadNativeLibrary()
        }
    }

    private fun openNative(
        component: RegisteredLanguagePackageComponent,
        parameters: GgufCandidateRankerParameters,
    ): NativeOpenResult {
        val errors = arrayOfNulls<String>(1)
        val state = bridge.openNative(
            modelPath = component.payloadFile.absolutePath,
            maxContextTokens = parameters.maxContextTokens,
            maxBatchSize = parameters.maxBatchSize,
            supportsRightContext = parameters.supportsRightContext,
            bosPolicy = parameters.bosPolicy.nativeValue,
            addEos = parameters.addEos,
            contextTruncation = parameters.contextTruncation.nativeValue,
            outError = errors,
        )
        return NativeOpenResult(state, errors[0])
    }

    private data class NativeOpenResult(
        val state: Long,
        val error: String?,
    )
}

@OptIn(DelicateCoroutinesApi::class)
private class GgufCandidateRankerRuntime(
    private val bridge: GgufCandidateRankerNativeBridge,
    initialState: Long,
    override val descriptor: CandidateRankerDescriptor,
) : CandidateRankerRuntime {
    private val nativeLock = ReentrantLock()
    private var state: Long = initialState

    override suspend fun rank(request: CandidateRankerRequest): CandidateRankerOutcome =
        withContext(LanguageModelScope) {
            currentCoroutineContext().ensureActive()

            val validation = CandidateRankerValidator.validateRequest(request, descriptor)
            if (!validation.isValid) {
                return@withContext CandidateRankerOutcome.Failure(
                    requestId = request.requestId,
                    failure = CandidateRankerFailure(
                        code = CandidateRankerFailureCode.InvalidRequest,
                        message = validation.issues
                            .filter { it.severity == CandidateRankerValidationSeverity.Error }
                            .joinToString { it.message },
                        recoverable = false,
                    ),
                )
            }

            val batches = CandidateRankerBatchPlanner.plan(
                candidates = request.candidates,
                maxBatchSize = descriptor.maxBatchSize,
            )
            val scores = ArrayList<CandidateRankerScore>(request.candidates.size)
            var elapsedMicros = 0L
            var candidateTokens = 0L
            var rightContextTokens = 0L
            var reusedPrefixTokens = 0L
            var nativeBatchCount = 0L

            for (batch in batches) {
                currentCoroutineContext().ensureActive()
                val nativeResult = nativeLock.withLock {
                    val nativeState = state
                    if (nativeState == 0L) {
                        return@withLock NativeScoreResult.Failure("Candidate ranker is closed.")
                    }
                    scoreBatch(nativeState, request, batch)
                }

                when (nativeResult) {
                    is NativeScoreResult.Failure -> {
                        return@withContext CandidateRankerOutcome.Failure(
                            requestId = request.requestId,
                            failure = CandidateRankerFailure(
                                code = classifyFailure(nativeResult.message),
                                message = nativeResult.message,
                                recoverable = false,
                            ),
                        )
                    }

                    is NativeScoreResult.Success -> {
                        scores += nativeResult.scores
                        elapsedMicros += nativeResult.diagnostics[0]
                        candidateTokens += nativeResult.diagnostics[1]
                        rightContextTokens += nativeResult.diagnostics[2]
                        reusedPrefixTokens += nativeResult.diagnostics[3]
                        nativeBatchCount += nativeResult.diagnostics[4]
                    }
                }
            }

            currentCoroutineContext().ensureActive()
            val success = CandidateRankerOutcome.Success(
                requestId = request.requestId,
                descriptor = descriptor,
                scores = scores,
                diagnostics = CandidateRankerDiagnostics(
                    elapsedMicros = elapsedMicros,
                    evaluatedCandidateTokens = candidateTokens.saturatedInt(),
                    evaluatedRightContextTokens = rightContextTokens.saturatedInt(),
                    reusedPrefixTokens = reusedPrefixTokens.saturatedInt(),
                    batchCount = nativeBatchCount.saturatedInt().coerceAtLeast(1),
                ),
            )
            val resultValidation = CandidateRankerValidator.validateSuccess(request, success)
            if (resultValidation.isValid) {
                success
            } else {
                CandidateRankerOutcome.Failure(
                    requestId = request.requestId,
                    failure = CandidateRankerFailure(
                        code = CandidateRankerFailureCode.RuntimeFailure,
                        message = resultValidation.issues
                            .filter { it.severity == CandidateRankerValidationSeverity.Error }
                            .joinToString { it.message },
                        recoverable = false,
                    ),
                )
            }
        }

    private fun scoreBatch(
        nativeState: Long,
        request: CandidateRankerRequest,
        batch: List<CandidateRankerCandidate>,
    ): NativeScoreResult {
        val count = batch.size
        val candidateLogProbabilities = DoubleArray(count)
        val candidateTokenCounts = IntArray(count)
        val rightContextLogProbabilities = DoubleArray(count)
        val rightContextTokenCounts = IntArray(count)
        val diagnostics = LongArray(5)

        val error = bridge.scoreNative(
            state = nativeState,
            leftContext = request.leftContext,
            rightContext = if (descriptor.supportsRightContext) request.rightContext else "",
            candidates = batch.map { it.replacementText }.toTypedArray(),
            rightContextTokenLimit = if (descriptor.supportsRightContext) {
                request.rightContextTokenLimit
            } else {
                0
            },
            outCandidateLogProbabilities = candidateLogProbabilities,
            outCandidateTokenCounts = candidateTokenCounts,
            outRightContextLogProbabilities = rightContextLogProbabilities,
            outRightContextTokenCounts = rightContextTokenCounts,
            outDiagnostics = diagnostics,
        )
        if (error != null) return NativeScoreResult.Failure(error)

        val scores = batch.indices.map { index ->
            CandidateRankerScore(
                candidateId = batch[index].id,
                candidateLogProbability = candidateLogProbabilities[index],
                candidateTokenCount = candidateTokenCounts[index],
                rightContextLogProbability = rightContextLogProbabilities[index],
                rightContextTokenCount = rightContextTokenCounts[index],
            )
        }
        return NativeScoreResult.Success(scores, diagnostics)
    }

    override fun close() {
        nativeLock.withLock {
            if (state != 0L) {
                bridge.closeNative(state)
                state = 0L
            }
        }
    }

    private sealed class NativeScoreResult {
        data class Success(
            val scores: List<CandidateRankerScore>,
            val diagnostics: LongArray,
        ) : NativeScoreResult()

        data class Failure(val message: String) : NativeScoreResult()
    }

    companion object {
        private fun classifyFailure(message: String): CandidateRankerFailureCode {
            val normalized = message.lowercase()
            return when {
                "context" in normalized && ("exceed" in normalized || "capacity" in normalized) -> {
                    CandidateRankerFailureCode.ContextTooLong
                }
                "memory" in normalized || "allocation" in normalized -> {
                    CandidateRankerFailureCode.OutOfMemory
                }
                "closed" in normalized -> CandidateRankerFailureCode.ComponentUnavailable
                else -> CandidateRankerFailureCode.RuntimeFailure
            }
        }
    }
}

private val GgufBosPolicy.nativeValue: Int
    get() = when (this) {
        GgufBosPolicy.ModelDefault -> 0
        GgufBosPolicy.Always -> 1
        GgufBosPolicy.Never -> 2
    }

private val GgufContextTruncation.nativeValue: Int
    get() = when (this) {
        GgufContextTruncation.KeepLast -> 0
        GgufContextTruncation.Reject -> 1
    }

private fun Long.saturatedInt(): Int = coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
