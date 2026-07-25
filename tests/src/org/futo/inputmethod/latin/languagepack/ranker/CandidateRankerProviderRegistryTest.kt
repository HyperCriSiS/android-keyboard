package org.futo.inputmethod.latin.languagepack.ranker

import androidx.test.InstrumentationRegistry
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
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
import org.futo.inputmethod.latin.languagepack.LanguagePackageRuntimeBinding
import org.futo.inputmethod.latin.languagepack.RegisteredLanguagePackageComponent
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CandidateRankerProviderRegistryTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(
            InstrumentationRegistry.getTargetContext().cacheDir,
            "candidate-provider-${UUID.randomUUID()}",
        )
        assertTrue(root.mkdirs())
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun providerIsSelectedByRuntimeIdAndApiVersion() = runBlocking {
        val component = component(LanguagePackageRuntimeBinding("gguf-causal-ranker", 2))
        val registry = CandidateRankerProviderRegistry(
            listOf(
                CandidateRankerProviderRegistration(
                    runtimeId = "gguf-causal-ranker",
                    supportedApiVersions = 1..2,
                    provider = FakeProvider(component, validDescriptor(component)),
                ),
            ),
        )

        assertTrue(registry.resolve(component) is CandidateRankerProviderResolution.Resolved)
        assertTrue(registry.probe(component) is CandidateRankerProbeOutcome.Ready)
        val opened = registry.open(component)
        assertTrue(opened is CandidateRankerOpenOutcome.Opened)
        (opened as CandidateRankerOpenOutcome.Opened).runtime.close()
    }

    @Test
    fun missingBindingOrUnsupportedApiIsUnavailable() {
        val withoutBinding = component(null)
        val unsupported = component(LanguagePackageRuntimeBinding("gguf-causal-ranker", 3))
        val registry = CandidateRankerProviderRegistry(
            listOf(
                CandidateRankerProviderRegistration(
                    runtimeId = "gguf-causal-ranker",
                    supportedApiVersions = 1..2,
                    provider = FakeProvider(unsupported, validDescriptor(unsupported)),
                ),
            ),
        )

        assertTrue(registry.resolve(withoutBinding) is CandidateRankerProviderResolution.Unavailable)
        assertTrue(registry.resolve(unsupported) is CandidateRankerProviderResolution.Unavailable)
    }

    @Test
    fun invalidDescriptorIsRejectedAndOpenedRuntimeIsClosed() = runBlocking {
        val component = component(LanguagePackageRuntimeBinding("gguf-causal-ranker", 1))
        val invalidDescriptor = validDescriptor(component).copy(component = component.coordinate.copy(componentId = "wrong"))
        val provider = FakeProvider(component, invalidDescriptor)
        val registry = CandidateRankerProviderRegistry(
            listOf(
                CandidateRankerProviderRegistration(
                    runtimeId = "gguf-causal-ranker",
                    supportedApiVersions = 1..1,
                    provider = provider,
                ),
            ),
        )

        assertTrue(registry.probe(component) is CandidateRankerProbeOutcome.Unavailable)
        assertTrue(registry.open(component) is CandidateRankerOpenOutcome.Failed)
        assertTrue(provider.lastRuntime?.closed == true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun overlappingProviderApiRangesAreRejected() {
        val component = component(LanguagePackageRuntimeBinding("gguf-causal-ranker", 1))
        val provider = FakeProvider(component, validDescriptor(component))
        CandidateRankerProviderRegistry(
            listOf(
                CandidateRankerProviderRegistration("gguf-causal-ranker", 1..2, provider),
                CandidateRankerProviderRegistration("gguf-causal-ranker", 2..3, provider),
            ),
        )
    }

    private fun validDescriptor(component: RegisteredLanguagePackageComponent): CandidateRankerDescriptor {
        return CandidateRankerDescriptor(
            component = component.coordinate,
            supportedLanguages = setOf("de"),
            maxContextTokens = 256,
            maxBatchSize = 16,
            boundaryMode = CandidateRankerBoundaryMode.LeadingSeparator,
            supportsRightContext = true,
            modelName = "Test ranker",
        )
    }

    private fun component(binding: LanguagePackageRuntimeBinding?): RegisteredLanguagePackageComponent {
        val directory = File(root, "package-${UUID.randomUUID()}")
        val content = File(directory, "content")
        assertTrue(content.mkdirs())
        val payload = File(content, "components/ranker.gguf")
        assertTrue(requireNotNull(payload.parentFile).mkdirs())
        payload.writeBytes(ByteArray(32) { 1 })
        val archive = File(directory, "package.futolanguage").apply { writeBytes(byteArrayOf(1)) }
        val specification = LanguagePackageComponent(
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
            runtime = binding,
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
            components = listOf(specification),
        )
        val installed = InstalledLanguagePackage(
            manifest = manifest,
            installationDirectory = directory,
            archiveFile = archive,
            contentDirectory = content,
            archiveSha256 = "b".repeat(64),
            archiveSizeBytes = archive.length(),
        )
        return RegisteredLanguagePackageComponent(installed, specification)
    }

    private class FakeProvider(
        private val component: RegisteredLanguagePackageComponent,
        private val descriptor: CandidateRankerDescriptor,
    ) : CandidateRankerRuntimeProvider {
        var lastRuntime: FakeRuntime? = null

        override suspend fun probe(component: RegisteredLanguagePackageComponent): CandidateRankerProbeOutcome {
            assertTrue(component.coordinate == this.component.coordinate)
            return CandidateRankerProbeOutcome.Ready(descriptor)
        }

        override suspend fun open(component: RegisteredLanguagePackageComponent): CandidateRankerOpenOutcome {
            assertTrue(component.coordinate == this.component.coordinate)
            return CandidateRankerOpenOutcome.Opened(
                FakeRuntime(descriptor).also { lastRuntime = it },
            )
        }
    }

    private class FakeRuntime(
        override val descriptor: CandidateRankerDescriptor,
    ) : CandidateRankerRuntime {
        var closed = false

        override suspend fun rank(request: CandidateRankerRequest): CandidateRankerOutcome {
            return CandidateRankerOutcome.Failure(
                request.requestId,
                CandidateRankerFailure(
                    CandidateRankerFailureCode.RuntimeFailure,
                    "Not implemented in test",
                    recoverable = false,
                ),
            )
        }

        override fun close() {
            closed = true
        }
    }
}
