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
class LanguagePackageResolverTest {
    private lateinit var root: File
    private val createdPackages = mutableListOf<InstalledLanguagePackage>()

    @Before
    fun setUp() {
        root = File(
            InstrumentationRegistry.getTargetContext().cacheDir,
            "language-package-resolver-${UUID.randomUUID()}",
        )
        assertTrue(root.mkdirs())
        createdPackages.clear()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun userSelectionOverridesProfileWhileProfileDictionaryRemainsActive() {
        val dictionary = component("dictionary", LanguagePackageComponentKind.Dictionary)
        val profileRanker = component("ranker-profile", LanguagePackageComponentKind.ContextRanker)
        val profile = LanguagePackageProfile(
            id = "standard",
            name = "Standard",
            selections = listOf(
                LanguagePackageProfileSelection(
                    slot = LanguagePackageComponentKind.Dictionary,
                    strategy = LanguagePackageSelectionStrategy.Append,
                    components = listOf(LanguagePackageComponentReference(componentId = dictionary.id)),
                ),
                LanguagePackageProfileSelection(
                    slot = LanguagePackageComponentKind.ContextRanker,
                    strategy = LanguagePackageSelectionStrategy.Replace,
                    components = listOf(LanguagePackageComponentReference(componentId = profileRanker.id)),
                ),
            ),
        )
        val profilePackage = installedPackage(
            packageId = "org.futo.test.profile",
            version = "1.0.0",
            components = listOf(dictionary, profileRanker),
            profiles = listOf(profile),
        )
        val userRankerPackage = installedPackage(
            packageId = "org.futo.test.user-ranker",
            version = "1.0.0",
            components = listOf(component("ranker-user", LanguagePackageComponentKind.ContextRanker)),
        )
        val registry = LanguagePackageRegistry.fromInstalledPackages(createdPackages)
        val userRanker = registry.components.single {
            it.installedPackage == userRankerPackage
        }

        val plan = LanguagePackageResolver(registry).resolve(
            request(
                profile = LanguagePackageProfileCoordinate(
                    packageId = profilePackage.manifest.packageInfo.id,
                    packageVersion = profilePackage.manifest.packageInfo.version,
                    profileId = profile.id,
                ),
                overrides = mapOf(
                    LanguagePackageComponentKind.ContextRanker to LanguagePackageSlotOverride.Select(
                        strategy = LanguagePackageSelectionStrategy.Replace,
                        components = listOf(userRanker.coordinate),
                    ),
                ),
            ),
        )

        assertTrue(plan.issues.joinToString { it.message }, plan.isValid)
        val selectedDictionary = plan.slots.getValue(LanguagePackageComponentKind.Dictionary).selected.single()
        val selectedRanker = plan.slots.getValue(LanguagePackageComponentKind.ContextRanker).selected.single()
        assertEquals(LanguagePackageSelectionSource.Profile, selectedDictionary.source)
        assertEquals(dictionary.id, selectedDictionary.component.component.id)
        assertEquals(LanguagePackageSelectionSource.User, selectedRanker.source)
        assertEquals("ranker-user", selectedRanker.component.component.id)
    }

    @Test
    fun stackableUserAppendKeepsProfileComponents() {
        val general = component("general", LanguagePackageComponentKind.Dictionary, priority = 0)
        val technical = component("technical", LanguagePackageComponentKind.Dictionary, priority = 20)
        val profile = LanguagePackageProfile(
            id = "standard",
            name = "Standard",
            selections = listOf(
                LanguagePackageProfileSelection(
                    slot = LanguagePackageComponentKind.Dictionary,
                    strategy = LanguagePackageSelectionStrategy.Append,
                    components = listOf(LanguagePackageComponentReference(componentId = general.id)),
                ),
            ),
        )
        val bundle = installedPackage(
            "org.futo.test.dictionaries",
            "1.0.0",
            listOf(general, technical),
            listOf(profile),
        )
        val registry = LanguagePackageRegistry.fromInstalledPackages(createdPackages)
        val technicalCoordinate = registry.components.single { it.component.id == technical.id }.coordinate

        val plan = LanguagePackageResolver(registry).resolve(
            request(
                profile = LanguagePackageProfileCoordinate(
                    bundle.manifest.packageInfo.id,
                    bundle.manifest.packageInfo.version,
                    profile.id,
                ),
                overrides = mapOf(
                    LanguagePackageComponentKind.Dictionary to LanguagePackageSlotOverride.Select(
                        strategy = LanguagePackageSelectionStrategy.Append,
                        components = listOf(technicalCoordinate),
                    ),
                ),
            ),
        )

        assertTrue(plan.isValid)
        val selected = plan.slots.getValue(LanguagePackageComponentKind.Dictionary).selected
        assertEquals(listOf("general", "technical"), selected.map { it.component.component.id })
        assertEquals(
            listOf(LanguagePackageSelectionSource.Profile, LanguagePackageSelectionSource.User),
            selected.map { it.source },
        )
    }

    @Test
    fun automaticSelectionChoosesExclusiveModelButNotInstalledDictionary() {
        installedPackage(
            "org.futo.test.auto",
            "1.0.0",
            listOf(
                component("dictionary", LanguagePackageComponentKind.Dictionary),
                component("ranker-slow", LanguagePackageComponentKind.ContextRanker, priority = 1),
                component("ranker-preferred", LanguagePackageComponentKind.ContextRanker, priority = 10),
            ),
        )
        val registry = LanguagePackageRegistry.fromInstalledPackages(createdPackages)

        val plan = LanguagePackageResolver(registry).resolve(request())

        assertTrue(plan.isValid)
        assertTrue(plan.slots.getValue(LanguagePackageComponentKind.Dictionary).selected.isEmpty())
        val ranker = plan.slots.getValue(LanguagePackageComponentKind.ContextRanker).selected.single()
        assertEquals("ranker-preferred", ranker.component.component.id)
        assertEquals(LanguagePackageSelectionSource.Automatic, ranker.source)
        assertTrue(plan.issues.any { it.code == "automatic_component_selected" })
    }

    @Test
    fun requiredDependencyIsAddedToItsSlot() {
        val dictionary = component("dictionary", LanguagePackageComponentKind.Dictionary)
        val ranker = component("ranker", LanguagePackageComponentKind.ContextRanker).copy(
            dependencies = listOf(LanguagePackageComponentReference(componentId = dictionary.id)),
        )
        val profile = LanguagePackageProfile(
            id = "ranker-only",
            name = "Ranker only",
            selections = listOf(
                LanguagePackageProfileSelection(
                    slot = LanguagePackageComponentKind.ContextRanker,
                    strategy = LanguagePackageSelectionStrategy.Replace,
                    components = listOf(LanguagePackageComponentReference(componentId = ranker.id)),
                ),
            ),
        )
        val bundle = installedPackage(
            "org.futo.test.dependencies",
            "1.0.0",
            listOf(dictionary, ranker),
            listOf(profile),
        )
        val registry = LanguagePackageRegistry.fromInstalledPackages(createdPackages)

        val plan = LanguagePackageResolver(registry).resolve(
            request(
                profile = LanguagePackageProfileCoordinate(
                    bundle.manifest.packageInfo.id,
                    bundle.manifest.packageInfo.version,
                    profile.id,
                ),
            ),
        )

        assertTrue(plan.issues.joinToString { it.message }, plan.isValid)
        val selectedDictionary = plan.slots.getValue(LanguagePackageComponentKind.Dictionary).selected.single()
        assertEquals(LanguagePackageSelectionSource.Dependency, selectedDictionary.source)
    }

    @Test
    fun disablingRequiredDependencyMakesPlanInvalid() {
        val dictionary = component("dictionary", LanguagePackageComponentKind.Dictionary)
        val ranker = component("ranker", LanguagePackageComponentKind.ContextRanker).copy(
            dependencies = listOf(LanguagePackageComponentReference(componentId = dictionary.id)),
        )
        val profile = LanguagePackageProfile(
            id = "standard",
            name = "Standard",
            selections = listOf(
                LanguagePackageProfileSelection(
                    slot = LanguagePackageComponentKind.ContextRanker,
                    strategy = LanguagePackageSelectionStrategy.Replace,
                    components = listOf(LanguagePackageComponentReference(componentId = ranker.id)),
                ),
            ),
        )
        val bundle = installedPackage(
            "org.futo.test.disabled-dependency",
            "1.0.0",
            listOf(dictionary, ranker),
            listOf(profile),
        )
        val registry = LanguagePackageRegistry.fromInstalledPackages(createdPackages)

        val plan = LanguagePackageResolver(registry).resolve(
            request(
                profile = LanguagePackageProfileCoordinate(
                    bundle.manifest.packageInfo.id,
                    bundle.manifest.packageInfo.version,
                    profile.id,
                ),
                overrides = mapOf(
                    LanguagePackageComponentKind.Dictionary to LanguagePackageSlotOverride.Disabled,
                ),
            ),
        )

        assertFalse(plan.isValid)
        assertTrue(plan.issues.any { it.code == "required_dependency_disabled" })
    }

    @Test
    fun conflictingExclusiveDependencyMakesPlanInvalid() {
        val generatorA = component("generator-a", LanguagePackageComponentKind.CandidateGenerator)
        val generatorB = component("generator-b", LanguagePackageComponentKind.CandidateGenerator)
        val ranker = component("ranker", LanguagePackageComponentKind.ContextRanker).copy(
            dependencies = listOf(LanguagePackageComponentReference(componentId = generatorA.id)),
        )
        val bundle = installedPackage(
            "org.futo.test.conflict",
            "1.0.0",
            listOf(generatorA, generatorB, ranker),
        )
        val registry = LanguagePackageRegistry.fromInstalledPackages(createdPackages)
        val selectedRanker = registry.components.single { it.component.id == ranker.id }
        val selectedGenerator = registry.components.single { it.component.id == generatorB.id }

        val plan = LanguagePackageResolver(registry).resolve(
            request(
                overrides = mapOf(
                    LanguagePackageComponentKind.ContextRanker to LanguagePackageSlotOverride.Select(
                        LanguagePackageSelectionStrategy.Replace,
                        listOf(selectedRanker.coordinate),
                    ),
                    LanguagePackageComponentKind.CandidateGenerator to LanguagePackageSlotOverride.Select(
                        LanguagePackageSelectionStrategy.Replace,
                        listOf(selectedGenerator.coordinate),
                    ),
                ),
            ),
        )

        assertFalse(plan.isValid)
        assertTrue(plan.issues.any { it.code == "exclusive_dependency_conflict" })
        assertEquals(bundle.manifest.packageInfo.id, selectedRanker.coordinate.packageId)
    }

    private fun request(
        profile: LanguagePackageProfileCoordinate? = null,
        overrides: Map<LanguagePackageComponentKind, LanguagePackageSlotOverride> = emptyMap(),
    ): LanguagePackageResolutionRequest {
        return LanguagePackageResolutionRequest(
            target = LanguagePackageTarget("de-DE", "qwertz"),
            environment = LanguagePackageRuntimeEnvironment(
                keyboardApi = 2,
                androidAbi = "arm64-v8a",
                ramMb = 4096,
                supportedTasks = setOf(
                    "dictionary-lookup-v1",
                    "language-rules-v1",
                    "candidate-generation-v1",
                    "candidate-ranking-v1",
                    "tap-correction-v1",
                    "swipe-decoding-v1",
                    "personalization-v1",
                ),
                supportedCapabilities = setOf("unicode-graphemes"),
                runtimeFeatures = emptySet(),
            ),
            profile = profile,
            overrides = overrides,
        )
    }

    private fun component(
        id: String,
        kind: LanguagePackageComponentKind,
        priority: Int = 0,
    ): LanguagePackageComponent {
        val task = when (kind) {
            LanguagePackageComponentKind.Dictionary -> "dictionary-lookup-v1"
            LanguagePackageComponentKind.LanguageRules -> "language-rules-v1"
            LanguagePackageComponentKind.CandidateGenerator -> "candidate-generation-v1"
            LanguagePackageComponentKind.ContextRanker -> "candidate-ranking-v1"
            LanguagePackageComponentKind.CorrectionModel -> "tap-correction-v1"
            LanguagePackageComponentKind.SwipeModel -> "swipe-decoding-v1"
            LanguagePackageComponentKind.PersonalizationAdapter -> "personalization-v1"
        }
        return LanguagePackageComponent(
            id = id,
            name = id,
            version = "1.0.0",
            kind = kind,
            activation = LanguagePackageResolver.activationFor(kind),
            priority = priority,
            languages = listOf("de"),
            layouts = listOf("qwertz"),
            tasks = listOf(task),
            capabilities = LanguagePackageCapabilities(required = listOf("unicode-graphemes")),
            payload = LanguagePackagePayload(
                path = "components/$id.bin",
                mediaType = "application/octet-stream",
                sha256 = "c".repeat(64),
                sizeBytes = 16,
            ),
        )
    }

    private fun installedPackage(
        packageId: String,
        version: String,
        components: List<LanguagePackageComponent>,
        profiles: List<LanguagePackageProfile> = emptyList(),
    ): InstalledLanguagePackage {
        val directory = File(root, "${packageId.replace('.', '_')}-$version-${createdPackages.size}")
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
                version = version,
                kind = LanguagePackageKind.Bundle,
                authors = listOf(LanguagePackageAuthor("Test")),
                license = "Apache-2.0",
                languages = listOf("de"),
            ),
            components = components,
            profiles = profiles,
            defaultProfile = profiles.firstOrNull()?.id,
        )
        return InstalledLanguagePackage(
            manifest = manifest,
            installationDirectory = directory,
            archiveFile = archive,
            contentDirectory = content,
            archiveSha256 = "d".repeat(64),
            archiveSizeBytes = archive.length(),
        ).also(createdPackages::add)
    }
}
