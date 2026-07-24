package org.futo.inputmethod.latin.languagepack

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class LanguagePackageManifestValidatorTest {
    @Test
    fun validManifestPassesSemanticValidation() {
        val result = LanguagePackageManifestValidator.validate(validManifest())

        assertTrue(result.errors.joinToString { it.message }, result.isValid)
    }

    @Test
    fun duplicateComponentAndUnsafePathAreRejected() {
        val manifest = validManifest().let { original ->
            original.copy(
                components = original.components + original.components.first().copy(
                    payload = original.components.first().payload.copy(path = "../dictionary.dict"),
                ),
            )
        }

        val result = LanguagePackageManifestValidator.validate(manifest)
        val codes = result.errors.map { it.code }.toSet()

        assertFalse(result.isValid)
        assertTrue("duplicate_component_id" in codes)
        assertTrue("unsafe_archive_path" in codes)
    }

    @Test
    fun exclusiveProfileSlotCannotSelectMultipleComponents() {
        val original = validManifest()
        val secondRanker = original.components.last().copy(
            id = "ranker-second",
            payload = original.components.last().payload.copy(
                path = "components/ranker-second.gguf",
                sha256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            ),
        )
        val invalidSelection = original.profiles.first().selections.last().copy(
            components = listOf(
                LanguagePackageComponentReference(componentId = "ranker"),
                LanguagePackageComponentReference(componentId = "ranker-second"),
            ),
        )
        val manifest = original.copy(
            components = original.components + secondRanker,
            profiles = listOf(
                original.profiles.first().copy(
                    selections = original.profiles.first().selections.dropLast(1) + invalidSelection,
                ),
            ),
        )

        val result = LanguagePackageManifestValidator.validate(manifest)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.code == "multiple_components_for_exclusive_slot" })
    }

    @Test
    fun internalDependencyCycleIsRejected() {
        val original = validManifest()
        val dictionary = original.components.first().copy(
            dependencies = listOf(LanguagePackageComponentReference(componentId = "ranker")),
        )
        val ranker = original.components.last().copy(
            dependencies = listOf(LanguagePackageComponentReference(componentId = "dictionary")),
        )
        val manifest = original.copy(components = listOf(dictionary, ranker))

        val result = LanguagePackageManifestValidator.validate(manifest)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.code == "component_dependency_cycle" })
    }

    private fun validManifest(): LanguagePackageManifest {
        val dictionary = LanguagePackageComponent(
            id = "dictionary",
            name = "German Dictionary",
            version = "1.0.0",
            kind = LanguagePackageComponentKind.Dictionary,
            activation = LanguagePackageActivation.Stackable,
            languages = listOf("de"),
            layouts = listOf("qwertz"),
            tasks = listOf("dictionary-lookup-v1"),
            payload = LanguagePackagePayload(
                path = "components/dictionary.dict",
                mediaType = "application/vnd.futo.keyboard.dictionary",
                sha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                sizeBytes = 1024,
            ),
        )
        val ranker = LanguagePackageComponent(
            id = "ranker",
            name = "German Context Ranker",
            version = "1.0.0",
            kind = LanguagePackageComponentKind.ContextRanker,
            activation = LanguagePackageActivation.Exclusive,
            languages = listOf("de-DE"),
            layouts = listOf("qwertz"),
            tasks = listOf("candidate-ranking-v1"),
            capabilities = LanguagePackageCapabilities(
                required = listOf("full-candidate-logprob"),
            ),
            payload = LanguagePackagePayload(
                path = "components/ranker.gguf",
                mediaType = "application/vnd.futo.keyboard.model+gguf",
                sha256 = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                sizeBytes = 2048,
            ),
        )
        val profile = LanguagePackageProfile(
            id = "standard",
            name = "Standard",
            selections = listOf(
                LanguagePackageProfileSelection(
                    slot = LanguagePackageComponentKind.Dictionary,
                    strategy = LanguagePackageSelectionStrategy.Append,
                    components = listOf(
                        LanguagePackageComponentReference(componentId = "dictionary"),
                    ),
                ),
                LanguagePackageProfileSelection(
                    slot = LanguagePackageComponentKind.ContextRanker,
                    strategy = LanguagePackageSelectionStrategy.Replace,
                    components = listOf(
                        LanguagePackageComponentReference(componentId = "ranker"),
                    ),
                ),
            ),
        )

        return LanguagePackageManifest(
            formatVersion = CURRENT_LANGUAGE_PACKAGE_FORMAT_VERSION,
            packageInfo = LanguagePackageInfo(
                id = "org.futo.community.german.standard",
                name = "German Standard",
                version = "1.0.0",
                kind = LanguagePackageKind.Bundle,
                authors = listOf(LanguagePackageAuthor(name = "FUTO Community")),
                license = "Apache-2.0",
                languages = listOf("de"),
            ),
            components = listOf(dictionary, ranker),
            profiles = listOf(profile),
            defaultProfile = "standard",
        )
    }
}
