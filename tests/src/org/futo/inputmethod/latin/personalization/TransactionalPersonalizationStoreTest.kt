package org.futo.inputmethod.latin.personalization

import android.content.Context
import androidx.test.InstrumentationRegistry
import androidx.test.filters.MediumTest
import androidx.test.runner.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@MediumTest
@RunWith(AndroidJUnit4::class)
class TransactionalPersonalizationStoreTest {
    private lateinit var root: File
    private lateinit var store: TransactionalPersonalizationStore

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getTargetContext()
        root = File(
            context.cacheDir,
            "transactional-personalization-${System.nanoTime()}",
        )
        store = TransactionalPersonalizationStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun initializationCreatesReadableImmutableGeneration() {
        val source = PersonalizationTestFixtures.validData()
        val initialized = store.initialize(source, committedAt = 10_000)
        assertTrue(initialized is PersonalizationStoreInitializeResult.Initialized)
        val snapshot = (initialized as PersonalizationStoreInitializeResult.Initialized).snapshot

        assertEquals(1L, snapshot.generation.generation)
        assertEquals(PersonalizationStoreCommitReason.Initialization, snapshot.generation.reason)
        assertEquals(source, snapshot.data)
        assertTrue(File(snapshot.generationDirectory, "data.json").isFile)
        assertTrue(File(snapshot.generationDirectory, "commit.json").isFile)
        assertUnsupportedMutation { (snapshot.data.manualWords as MutableList<*>).clear() }
        assertUnsupportedMutation { (snapshot.generation.changes as MutableList<*>).clear() }

        val read = store.readCurrent()
        assertTrue(read is PersonalizationStoreReadResult.Ready)
        assertEquals(
            snapshot.generation.generationId,
            (read as PersonalizationStoreReadResult.Ready).snapshot.generation.generationId,
        )

        val secondInitialization = store.initialize(
            TransactionalPersonalizationStore.emptyDataSet(),
            committedAt = 11_000,
        )
        assertTrue(secondInitialization is PersonalizationStoreInitializeResult.AlreadyInitialized)
        assertEquals(1, store.listHistory().size)
    }

    @Test
    fun editCommitCreatesJournaledGenerationAndRejectsStaleWriter() {
        val initial = initializedSnapshot()
        val edit = pin(
            data = initial.data,
            word = "ChatGPT",
            editedAt = 11_000,
            id = "99999999-9999-4999-8999-999999999999",
        )

        val committed = store.commitEdit(
            expectedGenerationId = initial.generation.generationId,
            edit = edit,
            committedAt = 11_000,
        )
        assertTrue(committed is PersonalizationStoreCommitResult.Committed)
        val current = (committed as PersonalizationStoreCommitResult.Committed).snapshot

        assertEquals(2L, current.generation.generation)
        assertEquals(initial.generation.generationId, current.generation.parentGenerationId)
        assertEquals(PersonalizationStoreCommitReason.UserEdit, current.generation.reason)
        assertTrue(current.generation.changes.isNotEmpty())
        assertTrue(current.data.manualWords.any { it.word == "ChatGPT" })

        val staleEdit = pin(
            data = initial.data,
            word = "Falscher Stand",
            editedAt = 12_000,
            id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        )
        val conflict = store.commitEdit(
            expectedGenerationId = initial.generation.generationId,
            edit = staleEdit,
            committedAt = 12_000,
        )
        assertTrue(conflict is PersonalizationStoreCommitResult.Conflict)
        assertEquals(
            current.generation.generationId,
            (conflict as PersonalizationStoreCommitResult.Conflict).actualGenerationId,
        )
        assertEquals(2, store.listHistory().size)
    }

    @Test
    fun rollbackCreatesNewGenerationWithoutRewritingHistory() {
        val initial = initializedSnapshot()
        val forgotten = PersonalizationEditPlanner.forgetWord(
            data = initial.data,
            locale = "de-DE",
            word = "wahrscheinlich",
            editedAt = 11_000,
        )
        val changed = store.commitEdit(
            expectedGenerationId = initial.generation.generationId,
            edit = forgotten,
            committedAt = 11_000,
        ) as PersonalizationStoreCommitResult.Committed
        assertFalse(changed.snapshot.data.learnedWords.any { it.word == "wahrscheinlich" })

        val rollback = store.rollback(
            expectedCurrentGenerationId = changed.snapshot.generation.generationId,
            targetGenerationId = initial.generation.generationId,
            committedAt = 12_000,
        )
        assertTrue(rollback is PersonalizationStoreCommitResult.Committed)
        val restored = (rollback as PersonalizationStoreCommitResult.Committed).snapshot

        assertEquals(3L, restored.generation.generation)
        assertEquals(PersonalizationStoreCommitReason.Rollback, restored.generation.reason)
        assertEquals(initial.generation.generationId, restored.generation.sourceGenerationId)
        assertEquals(initial.data, restored.data)
        assertEquals(listOf(3L, 2L, 1L), store.listHistory().map { it.generation })
        assertTrue(initial.generationDirectory.isDirectory)
        assertTrue(changed.snapshot.generationDirectory.isDirectory)
    }

    @Test
    fun corruptNewestGenerationFallsBackAndNextNumberIsNeverReused() {
        val initial = initializedSnapshot()
        val second = store.commitEdit(
            expectedGenerationId = initial.generation.generationId,
            edit = pin(
                data = initial.data,
                word = "zweite Generation",
                editedAt = 11_000,
                id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            ),
            committedAt = 11_000,
        ) as PersonalizationStoreCommitResult.Committed
        File(second.snapshot.generationDirectory, "data.json").writeText("{}", Charsets.UTF_8)

        val recovered = store.readCurrent()
        assertTrue(recovered is PersonalizationStoreReadResult.Ready)
        val recoveredSnapshot = (recovered as PersonalizationStoreReadResult.Ready).snapshot
        assertEquals(initial.generation.generationId, recoveredSnapshot.generation.generationId)
        assertEquals(1, recoveredSnapshot.recoveryIssues.size)
        assertEquals(
            second.snapshot.generation.generationId,
            recoveredSnapshot.recoveryIssues.single().generationDirectory,
        )

        val third = store.commitEdit(
            expectedGenerationId = recoveredSnapshot.generation.generationId,
            edit = pin(
                data = recoveredSnapshot.data,
                word = "dritte Generation",
                editedAt = 12_000,
                id = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            ),
            committedAt = 12_000,
        )
        assertTrue(third is PersonalizationStoreCommitResult.Committed)
        assertEquals(
            3L,
            (third as PersonalizationStoreCommitResult.Committed).snapshot.generation.generation,
        )
    }

    @Test
    fun stagingIsCleanedAndCorruptOnlyStoreIsNotOverwritten() {
        val junk = File(root, ".staging/unfinished")
        assertTrue(junk.mkdirs())
        File(junk, "partial").writeText("partial")

        assertTrue(store.readCurrent() is PersonalizationStoreReadResult.Empty)
        assertFalse(junk.exists())

        val corruptGeneration = File(
            root,
            "generations/00000000000000000001-deadbeefdead",
        )
        assertTrue(corruptGeneration.mkdirs())
        File(corruptGeneration, "data.json").writeText("{}")
        File(corruptGeneration, "commit.json").writeText("{}")

        val initialization = store.initialize(
            PersonalizationTestFixtures.validData(),
            committedAt = 10_000,
        )
        assertTrue(initialization is PersonalizationStoreInitializeResult.Failed)
        assertEquals(1, File(root, "generations").listFiles().orEmpty().size)
    }

    @Test
    fun invalidReplacementNeverCreatesGeneration() {
        val initial = initializedSnapshot()
        val invalid = initial.data.copy(formatVersion = "99.0")
        val result = store.replaceData(
            expectedGenerationId = initial.generation.generationId,
            data = invalid,
            committedAt = 11_000,
            reason = PersonalizationStoreCommitReason.Import,
            journalMessage = "Invalid import should not commit.",
        )

        assertTrue(result is PersonalizationStoreCommitResult.Invalid)
        assertEquals(1, store.listHistory().size)
    }

    @Test
    fun configuredDataLimitRejectsOversizedGenerationBeforeVisibility() {
        val limited = TransactionalPersonalizationStore(
            rootDirectory = root,
            limits = PersonalizationStoreLimits(
                maxDataBytes = 32,
                maxCommitMetadataBytes = 8 * 1024,
                maxJournalChanges = 10,
            ),
        )

        val result = limited.initialize(
            PersonalizationTestFixtures.validData(),
            committedAt = 10_000,
        )

        assertTrue(result is PersonalizationStoreInitializeResult.Failed)
        assertTrue(File(root, "generations").listFiles().orEmpty().isEmpty())
        assertTrue(File(root, ".staging").listFiles().orEmpty().isEmpty())
    }

    private fun initializedSnapshot(): PersonalizationStoreSnapshot {
        val result = store.initialize(
            PersonalizationTestFixtures.validData(),
            committedAt = 10_000,
        )
        return (result as PersonalizationStoreInitializeResult.Initialized).snapshot
    }

    private fun pin(
        data: PersonalizationDataSet,
        word: String,
        editedAt: Long,
        id: String,
    ): PersonalizationEditResult {
        var nextId = id
        return PersonalizationEditPlanner.pinWord(
            data = data,
            locale = "de-DE",
            word = word,
            editedAt = editedAt,
            idFactory = PersonalizationIdFactory {
                val result = nextId
                nextId = when (nextId) {
                    "99999999-9999-4999-8999-999999999999" ->
                        "77777777-7777-4777-8777-777777777778"
                    "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" ->
                        "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaab"
                    else -> "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbc"
                }
                result
            },
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
