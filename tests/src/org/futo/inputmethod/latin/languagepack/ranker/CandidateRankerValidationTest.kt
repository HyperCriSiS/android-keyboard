package org.futo.inputmethod.latin.languagepack.ranker

import androidx.test.InstrumentationRegistry
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.futo.inputmethod.latin.languagepack.CURRENT_LANGUAGE_PACKAGE_FORMAT_VERSION
import org.futo.inputmethod.latin.languagepack.InstalledLanguagePackage
import org.futo.inputmethod.latin.languagepack.LanguagePackageActivation
import org.futo.inputmethod.latin.languagepack.LanguagePackageAuthor
import org.futo.inputmethod.latin.languagepack.LanguagePackageCapabilities
import org.futo.inputmethod.latin.languagepack.LanguagePackageComponent
import org.futo.inputmethod.latin.languagepack.LanguagePackageComponentKind
import org.futo.inputmethod.latin.languagepack.LanguagePackageInfo
import org.futo.inputmethod.latin.languagepack.LanguagePackageKind
import org.futo.inputmethod.latin.languagepack.LanguagePackageManifest
import org.futo.inputmethod.latin.languagepack.LanguagePackagePayload
import org.futo.inputmethod.latin.languagepack.RegisteredLanguagePackageComponent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CandidateRankerValidationTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(
            InstrumentationRegistry.getTargetContext().cacheDir,
            "candidate-ranker-${UUID.randomUUID()}",
        )
        assertTrue(root.mkdirs())
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun normalizedScoreUsesCompleteCandidateAndOptionalRightContext() {
        val score = CandidateRankerScore(
            candidateId = "a",
            candidateLogProbability = -6.0,
            candidateTokenCount = 3,
            rightContextLogProbability = -2.0,
            rightContextTokenCount = 2,
        )
        val withoutRightContext = score.normalizedScore(
            CandidateRankerScoringPolicy(
                lengthNormalizationExponent = 1.0,
                rightContextWeight = 0.0,
            ),
        )
        val withRightContext = score.normalizedScore(
            CandidateRankerScoringPolicy(
                lengthNormalizationExponent = 1.0,
                rightContextWeight = 0.5,
            ),
        )

        assertEquals(-2.0, withoutRightContext, 0.000001)
        assertEquals(-2.5, withRightContext, 0.000001)
    }

    @Test
    fun validComponentDescriptorAndRequestPassValidation() {
        val component = registeredRanker()
        val descriptor = descriptor(component)
        val request = request()

        assertTrue(CandidateRankerValidator.validateComponent(component).isValid)
        assertTrue(CandidateRankerValidator.validateDescriptor(component, descriptor).isValid)
        assertTrue(CandidateRankerValidator.validateRequest(request, descriptor).isValid)
    }

    @Test
    fun requestRejectsDuplicateIdsUnsupportedLanguageAndInvalidLimits() {
        val component = registeredRanker()
        val descriptor = descriptor(component).copy(
            supportsRightContext = false,
            boundaryMode = CandidateRankerBoundaryMode.LeadingSeparator,
        )
        val request = request().copy(
            languageTag = "fr",
            rightContext = " tomorrow",
            rightContextTokenLimit = 100,
            candidates = listOf(
                CandidateRankerCandidate("same", "first", "first"),
                CandidateRankerCandidate("same", "second", "second"),
            ),
        )

        val validation = CandidateRankerValidator.validateRequest(request, descriptor)
        val codes = validation.issues.map { it.code }.toSet()

        assertFalse(validation.isValid)
        assertTrue("unsupported_language" in codes)
        assertTrue("invalid_right_context_token_limit" in codes)
        assertTrue("duplicate_candidate_id" in codes)
        assertTrue("right_context_not_supported" in codes)
        assertTrue("missing_leading_separator" in codes)
    }

    @Test
    fun successMustReturnExactlyOneFiniteScorePerCandidate() {
        val component = registeredRanker()
        val descriptor = descriptor(component)
        val request = request()
        val invalid = CandidateRankerOutcome.Success(
            requestId = "different-request",
            descriptor = descriptor,
            scores = listOf(
                CandidateRankerScore(
                    candidateId = request.candidates.first().id,
                    candidateLogProbability = Double.NaN,
                    candidateTokenCount = 0,
                ),
                CandidateRankerScore(
                    candidateId = "unknown",
                    candidateLogProbability = -1.0,
                    candidateTokenCount = 1,
                ),
            ),
            diagnostics = CandidateRankerDiagnostics(
                elapsedMicros = -1,
                evaluatedCandidateTokens = -1,
                evaluatedRightContextTokens = 0,
                reusedPrefixTokens = 0,
                batchCount = 0,
            ),
        )

        val validation = CandidateRankerValidator.validateSuccess(request, invalid)
        val codes = validation.issues.map { it.code }.toSet()

        assertFalse(validation.isValid)
        assertTrue("result_request_id_mismatch" in codes)
        assertTrue("invalid_candidate_log_probability" in codes)
        assertTrue("invalid_candidate_token_count" in codes)
        assertTrue("unknown_score_id" in codes)
        assertTrue("missing_candidate_scores" in codes)
        assertTrue("invalid_diagnostics" in codes)
    }

    @Test
    fun batchPlannerPreservesCandidateOrder() {
        val candidates = (0 until 7).map { index ->
            CandidateRankerCandidate(
                id = index.toString(),
                displayText = "candidate-$index",
                replacementText = " candidate-$index",
            )
        }

        val batches = CandidateRankerBatchPlanner.plan(candidates, maxBatchSize = 3)

        assertEquals(listOf(3, 3, 1), batches.map { it.size })
        assertEquals(candidates, batches.flatten())
    }

    private fun request(): CandidateRankerRequest {
        return CandidateRankerRequest(
            requestId = "request-1",
            languageTag = "de-DE",
            purpose = CandidateRankingPurpose.Correction,
            leftContext = "Das ist",
            typedText = " warscheinlich",
            rightContext = " richtig.",
            candidates = listOf(
                CandidateRankerCandidate(
                    id = "wahrscheinlich",
                    displayText = "wahrscheinlich",
                    replacementText = " wahrscheinlich",
                ),
                CandidateRankerCandidate(
                    id = "anscheinend",
                    displayText = "anscheinend",
                    replacementText = " anscheinend",
                ),
            ),
        )
    }

    private fun descriptor(component: RegisteredLanguagePackageComponent): CandidateRankerDescriptor {
        return CandidateRankerDescriptor(
            component = component.coordinate,
            supportedLanguages = setOf("de"),
            maxContextTokens = 256,
            maxBatchSize = 16,
            boundaryMode = CandidateRankerBoundaryMode.LeadingSeparator,
            supportsRightContext = true,
            modelName = "German test ranker",
        )
    }

    private fun registeredRanker(): RegisteredLanguagePackageComponent {
        val directory = File(root, "package")
        val content = File(directory, "content")
        assertTrue(content.mkdirs())
        val payload = File(content, "components/ranker.gguf")
        assertTrue(requireNotNull(payload.parentFile).mkdirs())
        payload.writeBytes(ByteArray(32) { 1 })
        val archive = File(directory, "package.futolanguage").apply { writeBytes(byteArrayOf(1)) }
        val component = LanguagePackageComponent(
            id = "ranker",
            name = "Ranker",
            version = "1.0.0",
            kind = LanguagePackageComponentKind.ContextRanker,
            activation = LanguagePackageActivation.Exclusive,
            languages = listOf("de"),
            tasks = listOf(CANDIDATE_RANKING_TASK_V1),
            capabilities = LanguagePackageCapabilities(
                required = listOf(FULL_CANDIDATE_LOGPROB_CAPABILITY),
            ),
            payload = LanguagePackagePayload(
                path = "components/ranker.gguf",
                mediaType = "application/vnd.futo.keyboard.model+gguf",
                sha256 = "a".repeat(64),
                sizeBytes = payload.length(),
            ),
        )
        val manifest = LanguagePackageManifest(
            formatVersion = CURRENT_LANGUAGE_PACKAGE_FORMAT_VERSION,
            packageInfo = LanguagePackageInfo(
                id = "org.futo.test.ranker",
                name = "Ranker",
                version = "1.0.0",
                kind = LanguagePackageKind.Component,
                authors = listOf(LanguagePackageAuthor("Test")),
                license = "Apache-2.0",
                languages = listOf("de"),
            ),
            components = listOf(component),
        )
        val installed = InstalledLanguagePackage(
            manifest = manifest,
            installationDirectory = directory,
            archiveFile = archive,
            contentDirectory = content,
            archiveSha256 = "b".repeat(64),
            archiveSizeBytes = archive.length(),
        )
        return RegisteredLanguagePackageComponent(installed, component)
    }
}
