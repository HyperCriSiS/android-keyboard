package org.futo.inputmethod.latin.personalization

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class PersonalizationRuntimeSnapshotTest {
    @Test
    fun compilerCopiesMutableSourcesAndIndexesRecords() {
        val sourceManualWords = PersonalizationTestFixtures.validData().manualWords.toMutableList()
        val sourceTerms = mutableListOf("sehr", "wahrscheinlich")
        val sourceData = PersonalizationTestFixtures.validData()
        val data = sourceData.copy(
            manualWords = sourceManualWords,
            learnedNgrams = listOf(sourceData.learnedNgrams.single().copy(terms = sourceTerms)),
        )
        val runtime = PersonalizationRuntimeSnapshotCompiler.compile(snapshot(data))

        sourceManualWords += ManualWordRecord(
            id = "99999999-9999-4999-8999-999999999999",
            revision = 1,
            createdAt = 9_000,
            updatedAt = 9_000,
            locale = "de-DE",
            word = "später hinzugefügt",
            frequency = 250,
            source = ManualWordSource.Manual,
        )
        sourceTerms += "verändert"

        assertEquals(1, runtime.manualWords.size)
        assertEquals(2, runtime.learnedNgrams.single().terms.size)
        assertEquals("FUTO", runtime.findManualWords("de-DE", "fu").single().word)
        assertEquals(
            "wahrscheinlich",
            runtime.learnedWord("de_de", "WAHRSCHEINLICH")?.word,
        )
        assertEquals(WordRuleAction.Pin, runtime.wordRule("de-DE", "futo")?.action)
        assertUnsupportedMutation { (runtime.manualWords as MutableList).clear() }
        assertUnsupportedMutation { (runtime.learnedNgrams.single().terms as MutableList).clear() }
        assertUnsupportedMutation {
            (runtime.findManualWords("de-DE") as MutableList).clear()
        }
    }

    @Test
    fun correctionResolutionPrefersSpecificLocaleAndAppScope() {
        val base = PersonalizationTestFixtures.validData()
        val global = CorrectionRuleRecord(
            id = "99999999-9999-4999-8999-999999999999",
            revision = 1,
            createdAt = 9_000,
            updatedAt = 9_000,
            locale = null,
            typed = "im",
            replacement = "ihm",
            action = CorrectionRuleAction.Allow,
            appScope = null,
        )
        val localeOnly = CorrectionRuleRecord(
            id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            revision = 1,
            createdAt = 9_000,
            updatedAt = 9_000,
            locale = "de-DE",
            typed = "im",
            replacement = "ihm",
            action = CorrectionRuleAction.Prefer,
            appScope = null,
        )
        val data = base.copy(
            correctionRules = base.correctionRules + global + localeOnly,
        )
        val runtime = PersonalizationRuntimeSnapshotCompiler.compile(snapshot(data))

        assertEquals(
            CorrectionRuleAction.BlockAutocorrect,
            runtime.correctionRule("de-DE", "IM", "IHM", "org.example.notes")?.action,
        )
        assertEquals(
            CorrectionRuleAction.Prefer,
            runtime.correctionRule("de-DE", "im", "ihm", "org.other.app")?.action,
        )
        assertEquals(
            CorrectionRuleAction.Allow,
            runtime.correctionRule("de-AT", "im", "ihm", "org.other.app")?.action,
        )
        assertNull(runtime.correctionRule("de-DE", "seit", "seid", null))
    }

    @Test
    fun heldRuntimeGenerationDoesNotChangeWhenNewStoreSnapshotExists() {
        val first = PersonalizationRuntimeSnapshotCompiler.compile(
            snapshot(PersonalizationTestFixtures.validData(), generation = 1L),
        )
        val secondData = PersonalizationTestFixtures.validData().copy(
            learnedWords = emptyList(),
            learnedNgrams = emptyList(),
        )
        val second = PersonalizationRuntimeSnapshotCompiler.compile(
            snapshot(secondData, generation = 2L),
        )

        assertEquals(1L, first.generation)
        assertTrue(first.learnedWords.isNotEmpty())
        assertEquals(2L, second.generation)
        assertTrue(second.learnedWords.isEmpty())
        assertTrue(first.learnedWords.isNotEmpty())
    }

    private fun snapshot(
        data: PersonalizationDataSet,
        generation: Long = 1L,
    ): PersonalizationStoreSnapshot {
        val hash = generation.toString().padStart(64, '0').takeLast(64)
        val generationId = "%020d-%s".format(Locale.ROOT, generation, hash.take(12))
        return PersonalizationStoreSnapshot(
            generation = PersonalizationStoreGeneration(
                storeVersion = "0.1",
                generation = generation,
                generationId = generationId,
                committedAt = 10_000 + generation,
                reason = PersonalizationStoreCommitReason.UserEdit,
                dataSha256 = hash,
                dataSizeBytes = 0,
            ),
            data = data,
            generationDirectory = File("."),
        )
    }

    private fun assertUnsupportedMutation(block: () -> Unit) {
        try {
            block()
            fail("Expected immutable collection mutation to fail.")
        } catch (_: UnsupportedOperationException) {
            // Expected.
        }
    }
}
