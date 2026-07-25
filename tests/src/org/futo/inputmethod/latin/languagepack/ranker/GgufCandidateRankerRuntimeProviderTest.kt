package org.futo.inputmethod.latin.languagepack.ranker

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.futo.inputmethod.latin.languagepack.LanguagePackageComponentCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class GgufCandidateRankerRuntimeProviderTest {
    @Test
    fun runtimeBatchesCandidatesAndPreservesScoreOrder() = runBlocking {
        val native = FakeNativeApi()
        val runtime = GgufCandidateRankerRuntime(
            nativeApi = native,
            initialState = 42L,
            descriptor = descriptor(maxBatchSize = 2),
        )
        val request = request(candidateCount = 5)

        val outcome = runtime.rank(request)

        assertTrue(outcome is CandidateRankerOutcome.Success)
        outcome as CandidateRankerOutcome.Success
        assertEquals(request.candidates.map { it.id }, outcome.scores.map { it.candidateId })
        assertEquals(listOf(2, 2, 1), native.scoredBatches.map { it.size })
        assertEquals(30L, outcome.diagnostics.elapsedMicros)
        assertEquals(5, outcome.diagnostics.evaluatedCandidateTokens)
        assertEquals(5, outcome.diagnostics.evaluatedRightContextTokens)
        assertEquals(25, outcome.diagnostics.reusedPrefixTokens)
        assertEquals(3, outcome.diagnostics.batchCount)

        runtime.close()
        runtime.close()
        assertEquals(listOf(42L), native.closedStates)
    }

    @Test
    fun invalidRequestNeverCallsNativeRuntime() = runBlocking {
        val native = FakeNativeApi()
        val runtime = GgufCandidateRankerRuntime(
            nativeApi = native,
            initialState = 1L,
            descriptor = descriptor(maxBatchSize = 4),
        )
        val duplicate = CandidateRankerCandidate("same", "one", " one")
        val outcome = runtime.rank(
            request(candidateCount = 1).copy(candidates = listOf(duplicate, duplicate)),
        )

        assertTrue(outcome is CandidateRankerOutcome.Failure)
        outcome as CandidateRankerOutcome.Failure
        assertEquals(CandidateRankerFailureCode.InvalidRequest, outcome.failure.code)
        assertTrue(native.scoredBatches.isEmpty())
        runtime.close()
    }

    @Test
    fun nativeFailureIsReturnedAndClosedRuntimeCannotScore() = runBlocking {
        val native = FakeNativeApi(failure = "native runtime failure")
        val runtime = GgufCandidateRankerRuntime(
            nativeApi = native,
            initialState = 7L,
            descriptor = descriptor(maxBatchSize = 8),
        )

        val failed = runtime.rank(request(candidateCount = 2))
        assertTrue(failed is CandidateRankerOutcome.Failure)
        failed as CandidateRankerOutcome.Failure
        assertEquals(CandidateRankerFailureCode.RuntimeFailure, failed.failure.code)

        runtime.close()
        val closed = runtime.rank(request(candidateCount = 1))
        assertTrue(closed is CandidateRankerOutcome.Failure)
        closed as CandidateRankerOutcome.Failure
        assertEquals(CandidateRankerFailureCode.ComponentUnavailable, closed.failure.code)
    }

    @Test
    fun rightContextIsSuppressedWhenDescriptorDoesNotSupportIt() = runBlocking {
        val native = FakeNativeApi()
        val runtime = GgufCandidateRankerRuntime(
            nativeApi = native,
            initialState = 3L,
            descriptor = descriptor(maxBatchSize = 4, supportsRightContext = false),
        )

        val outcome = runtime.rank(request(candidateCount = 1))

        assertTrue(outcome is CandidateRankerOutcome.Success)
        assertEquals(listOf(""), native.receivedRightContexts)
        assertEquals(listOf(0), native.receivedRightContextLimits)
        runtime.close()
    }

    private fun descriptor(
        maxBatchSize: Int,
        supportsRightContext: Boolean = true,
    ): CandidateRankerDescriptor {
        return CandidateRankerDescriptor(
            component = LanguagePackageComponentCoordinate(
                packageId = "org.futo.test.ranker",
                packageVersion = "1.0.0",
                componentId = "ranker",
                componentVersion = "1.0.0",
            ),
            supportedLanguages = setOf("de"),
            maxContextTokens = 256,
            maxBatchSize = maxBatchSize,
            boundaryMode = CandidateRankerBoundaryMode.LeadingSeparator,
            supportsRightContext = supportsRightContext,
            modelName = "Test ranker",
        )
    }

    private fun request(candidateCount: Int): CandidateRankerRequest {
        return CandidateRankerRequest(
            requestId = "request",
            languageTag = "de-DE",
            purpose = CandidateRankingPurpose.Correction,
            leftContext = "Das ist",
            typedText = " warscheinlich",
            rightContext = " richtig.",
            candidates = (0 until candidateCount).map { index ->
                CandidateRankerCandidate(
                    id = "candidate-$index",
                    displayText = "candidate-$index",
                    replacementText = " candidate-$index",
                )
            },
            rightContextTokenLimit = 4,
        )
    }

    private class FakeNativeApi(
        private val failure: String? = null,
    ) : GgufCandidateRankerNativeApi {
        val scoredBatches = mutableListOf<List<String>>()
        val receivedRightContexts = mutableListOf<String>()
        val receivedRightContextLimits = mutableListOf<Int>()
        val closedStates = mutableListOf<Long>()

        override fun openNative(
            modelPath: String,
            maxContextTokens: Int,
            maxBatchSize: Int,
            supportsRightContext: Boolean,
            bosPolicy: Int,
            addEos: Boolean,
            contextTruncation: Int,
            outError: Array<String?>,
        ): Long = 1L

        override fun closeNative(state: Long) {
            closedStates += state
        }

        override fun scoreNative(
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
        ): String? {
            failure?.let { return it }

            scoredBatches += candidates.toList()
            receivedRightContexts += rightContext
            receivedRightContextLimits += rightContextTokenLimit
            candidates.indices.forEach { index ->
                outCandidateLogProbabilities[index] = -(index + 1).toDouble()
                outCandidateTokenCounts[index] = 1
                outRightContextLogProbabilities[index] = -0.5
                outRightContextTokenCounts[index] = if (rightContextTokenLimit > 0) 1 else 0
            }
            outDiagnostics[0] = 10L
            outDiagnostics[1] = candidates.size.toLong()
            outDiagnostics[2] = if (rightContextTokenLimit > 0) candidates.size.toLong() else 0L
            outDiagnostics[3] = candidates.size * 5L
            outDiagnostics[4] = 1L
            return null
        }
    }
}
