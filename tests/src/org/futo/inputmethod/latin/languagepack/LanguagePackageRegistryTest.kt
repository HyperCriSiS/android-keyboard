package org.futo.inputmethod.latin.languagepack

import androidx.test.InstrumentationRegistry
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class LanguagePackageRegistryTest {
    private lateinit var root: File
    private val createdPackages = mutableListOf<InstalledLanguagePackage>()

    @Before
    fun setUp() {
        root = File(
            InstrumentationRegistry.getTargetContext().cacheDir,
            "language-package-registry-${UUID.randomUUID()}",
        )
        assertTrue(root.mkdirs())
        createdPackages.clear()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun externalReferenceSelectsHighestMatchingComponentVersion() {
        val oldRanker = component(
            id = "ranker",
            version = "1.5.0",
            kind = LanguagePackageComponentKind.ContextRanker,
            tasks = listOf("candidate-ranking-v1"),
        )
        val newRanker = oldRanker.copy(version = "2.0.0")
        val oldPackage = installedPackage("org.futo.test.models", "1.0.0", listOf(oldRanker))
        installedPackage("org.futo.test.models", "2.0.0", listOf(newRanker))
        val owner = installedPackage("org.futo.test.profile", "1.0.0", emptyList())
        val registry = LanguagePackageRegistry.fromInstalledPackages(installedPackages())

        val constrained = registry.resolveReference(
            ownerPackage = owner,
            reference = LanguagePackageComponentReference(
                packageId = "org.futo.test.models",
                componentId = "ranker",
                versionRange = ">=1.0.0 <2.0.0",
            ),
        )
        val unconstrained = registry.resolveReference(
            ownerPackage = owner,
            reference = LanguagePackageComponentReference(
                packageId = "org.futo.test.models",
                componentId = "ranker",
            ),
        )

        assertTrue(constrained is LanguagePackageReferenceResolution.Resolved)
        assertEquals(
            oldPackage.manifest.packageInfo.version,
            (constrained as LanguagePackageReferenceResolution.Resolved).component.coordinate.packageVersion,
        )
        assertTrue(unconstrained is LanguagePackageReferenceResolution.Resolved)
        assertEquals(
            "2.0.0",
            (unconstrained as LanguagePackageReferenceResolution.Resolved).component.coordinate.componentVersion,
        )
    }

    @Test
    fun internalReferenceIsRestrictedToOwningPackageVersion() {
        val dependencyV1 = component(
            id = "dictionary",
            version = "1.0.0",
            kind = LanguagePackageComponentKind.Dictionary,
            tasks = listOf("dictionary-lookup-v1"),
        )
        val dependencyV2 = dependencyV1.copy(version = "2.0.0")
        val ownerV1 = installedPackage("org.futo.test.bundle", "1.0.0", listOf(dependencyV1))
        installedPackage("org.futo.test.bundle", "2.0.0", listOf(dependencyV2))
        val registry = LanguagePackageRegistry.fromInstalledPackages(installedPackages())

        val resolution = registry.resolveReference(
            ownerPackage = ownerV1,
            reference = LanguagePackageComponentReference(componentId = "dictionary"),
        )

        assertTrue(resolution is LanguagePackageReferenceResolution.Resolved)
        assertEquals(
            "1.0.0",
            (resolution as LanguagePackageReferenceResolution.Resolved).component.coordinate.packageVersion,
        )
    }

    @Test
    fun compatibilityChecksLanguageLayoutRuntimeAndPayload() {
        val ranker = component(
            id = "ranker",
            version = "1.0.0",
            kind = LanguagePackageComponentKind.ContextRanker,
            tasks = listOf("candidate-ranking-v1"),
            capabilities = LanguagePackageCapabilities(required = listOf("full-candidate-logprob")),
            compatibility = LanguagePackageCompatibility(
                minKeyboardApi = 2,
                androidAbis = listOf("arm64-v8a"),
                minRamMb = 1024,
                runtimeFeatures = listOf("gguf-causal-lm-v1"),
            ),
        ).copy(layouts = listOf("qwertz"))
        val installed = installedPackage("org.futo.test.ranker", "1.0.0", listOf(ranker))
        val registry = LanguagePackageRegistry.fromInstalledPackages(installedPackages())
        val registered = registry.components.single()

        val compatible = registry.evaluate(
            component = registered,
            target = LanguagePackageTarget("de-DE", "qwertz"),
            environment = runtimeEnvironment(),
        )
        assertTrue(compatible.issues.joinToString { it.message }, compatible.isCompatible)

        val incompatible = registry.evaluate(
            component = registered,
            target = LanguagePackageTarget("de-DE", "azerty"),
            environment = runtimeEnvironment().copy(
                androidAbi = "x86_64",
                ramMb = 512,
                supportedCapabilities = emptySet(),
                runtimeFeatures = emptySet(),
            ),
        )
        val codes = incompatible.issues.map { it.code }.toSet()
        assertFalse(incompatible.isCompatible)
        assertTrue("layout_mismatch" in codes)
        assertTrue("abi_mismatch" in codes)
        assertTrue("insufficient_ram" in codes)
        assertTrue("unsupported_capabilities" in codes)
        assertTrue("missing_runtime_features" in codes)

        File(installed.contentDirectory, ranker.payload.path).delete()
        val missingPayload = registry.evaluate(
            component = registered,
            target = LanguagePackageTarget("de", "qwertz"),
            environment = runtimeEnvironment(),
        )
        assertTrue(missingPayload.issues.any { it.code == "missing_payload_file" })
    }

    @Test
    fun requiredDependencyMustBePresentAndCompatible() {
        val dictionary = component(
            id = "dictionary",
            version = "1.0.0",
            kind = LanguagePackageComponentKind.Dictionary,
            tasks = listOf("dictionary-lookup-v1"),
        )
        val ranker = component(
            id = "ranker",
            version = "1.0.0",
            kind = LanguagePackageComponentKind.ContextRanker,
            tasks = listOf("candidate-ranking-v1"),
        ).copy(
            dependencies = listOf(
                LanguagePackageComponentReference(componentId = "dictionary"),
            ),
        )
        installedPackage("org.futo.test.bundle", "1.0.0", listOf(dictionary, ranker))
        val registry = LanguagePackageRegistry.fromInstalledPackages(installedPackages())
        val registeredRanker = registry.components.single { it.component.id == "ranker" }

        val evaluation = registry.evaluate(
            registeredRanker,
            LanguagePackageTarget("de", "qwertz"),
            runtimeEnvironment(),
        )

        assertTrue(evaluation.issues.joinToString { it.message }, evaluation.isCompatible)
        assertEquals("dictionary", evaluation.dependencies.single().component.component.id)

        val missingDependencyRanker = ranker.copy(
            id = "ranker-missing",
            payload = ranker.payload.copy(path = "components/ranker-missing.bin"),
            dependencies = listOf(
                LanguagePackageComponentReference(
                    packageId = "org.futo.test.missing",
                    componentId = "dictionary",
                ),
            ),
        )
        installedPackage("org.futo.test.missing-dependency", "1.0.0", listOf(missingDependencyRanker))
        val secondRegistry = LanguagePackageRegistry.fromInstalledPackages(installedPackages())
        val missingEvaluation = secondRegistry.evaluate(
            secondRegistry.components.single { it.component.id == "ranker-missing" },
            LanguagePackageTarget("de", "qwertz"),
            runtimeEnvironment(),
        )

        assertFalse(missingEvaluation.isCompatible)
        assertTrue(missingEvaluation.issues.any { it.code == "missing_dependency" })
    }

    private fun installedPackages(): List<InstalledLanguagePackage> = createdPackages.toList()

    private fun installedPackage(
        packageId: String,
        packageVersion: String,
        components: List<LanguagePackageComponent>,
        profiles: List<LanguagePackageProfile> = emptyList(),
    ): InstalledLanguagePackage {
        val directory = File(root, "${packageId.replace('.', '_')}-$packageVersion-${createdPackages.size}")
        val content = File(directory, "content")
        assertTrue(content.mkdirs())

        components.forEach { component ->
            val payload = File(content, component.payload.path)
            val parent = requireNotNull(payload.parentFile)
            assertTrue(parent.isDirectory || parent.mkdirs())
            payload.writeBytes(ByteArray(component.payload.sizeBytes.toInt()) { 1 })
        }
        val archive = File(directory, "package.futolanguage").apply { writeBytes(byteArrayOf(1)) }
        val manifest = LanguagePackageManifest(
            formatVersion = CURRENT_LANGUAGE_PACKAGE_FORMAT_VERSION,
            packageInfo = LanguagePackageInfo(
                id = packageId,
                name = packageId,
                version = packageVersion,
                kind = if (profiles.isEmpty() && components.size == 1) {
                    LanguagePackageKind.Component
                } else {
                    LanguagePackageKind.Bundle
                },
                authors = listOf(LanguagePackageAuthor("Test")),
                license = "Apache-2.0",
                languages = listOf("de"),
            ),
            components = components,
            profiles = profiles,
        )
        val installed = InstalledLanguagePackage(
            manifest = manifest,
            installationDirectory = directory,
            archiveFile = archive,
            contentDirectory = content,
            archiveSha256 = "a".repeat(64),
            archiveSizeBytes = archive.length(),
        )
        createdPackages += installed
        return installed
    }

    private fun component(
        id: String,
        version: String,
        kind: LanguagePackageComponentKind,
        tasks: List<String>,
        capabilities: LanguagePackageCapabilities = LanguagePackageCapabilities(),
        compatibility: LanguagePackageCompatibility = LanguagePackageCompatibility(),
    ): LanguagePackageComponent {
        return LanguagePackageComponent(
            id = id,
            name = id,
            version = version,
            kind = kind,
            activation = LanguagePackageResolver.activationFor(kind),
            languages = listOf("de"),
            layouts = listOf("qwertz"),
            tasks = tasks,
            capabilities = capabilities,
            compatibility = compatibility,
            payload = LanguagePackagePayload(
                path = "components/$id.bin",
                mediaType = "application/octet-stream",
                sha256 = "b".repeat(64),
                sizeBytes = 16,
            ),
        )
    }

    private fun runtimeEnvironment(): LanguagePackageRuntimeEnvironment {
        return LanguagePackageRuntimeEnvironment(
            keyboardApi = 2,
            androidAbi = "arm64-v8a",
            ramMb = 4096,
            supportedTasks = setOf("dictionary-lookup-v1", "candidate-ranking-v1"),
            supportedCapabilities = setOf("full-candidate-logprob"),
            runtimeFeatures = setOf("gguf-causal-lm-v1"),
        )
    }
}
