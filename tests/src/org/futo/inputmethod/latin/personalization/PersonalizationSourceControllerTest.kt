package org.futo.inputmethod.latin.personalization

import android.content.Context
import androidx.test.InstrumentationRegistry
import androidx.test.filters.MediumTest
import androidx.test.runner.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@MediumTest
@RunWith(AndroidJUnit4::class)
class PersonalizationSourceControllerTest {
    private lateinit var root: File
    private lateinit var store: TransactionalPersonalizationStore

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getTargetContext()
        root = File(context.cacheDir, "personalization-controller-${System.nanoTime()}")
        store = TransactionalPersonalizationStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun openAndCommitAtomicallyAdvanceRuntimeGeneration() {
        val controller = PersonalizationSourceController(store)
        val opened = controller.open(
            initialData = PersonalizationTestFixtures.validData(),
            committedAt = 10_000,
        ) as PersonalizationSourceOpenResult.Ready
        assertEquals(opened.runtimeSnapshot.generationId, controller.activeRuntimeSnapshot()?.generationId)

        val edit = pin(
            opened.storeSnapshot.data,
            word = "Controller",
            editedAt = 11_000,
        )
        val update = controller.commitEdit(
            expectedGenerationId = opened.storeSnapshot.generation.generationId,
            edit = edit,
            committedAt = 11_000,
        )
        assertTrue(update is PersonalizationSourceUpdateResult.Activated)
        val activated = update as PersonalizationSourceUpdateResult.Activated

        assertEquals(2L, activated.runtimeSnapshot.generation)
        assertEquals(
            activated.storeSnapshot.generation.generationId,
            controller.activeRuntimeSnapshot()?.generationId,
        )
        assertTrue(activated.runtimeSnapshot.manualWords.any { it.word == "Controller" })
    }

    @Test
    fun staleConflictNeverChangesActiveRuntime() {
        val controller = PersonalizationSourceController(store)
        val opened = controller.open(
            PersonalizationTestFixtures.validData(),
            committedAt = 10_000,
        ) as PersonalizationSourceOpenResult.Ready
        val firstUpdate = controller.commitEdit(
            expectedGenerationId = opened.storeSnapshot.generation.generationId,
            edit = pin(opened.storeSnapshot.data, "Erste Änderung", 11_000),
            committedAt = 11_000,
        ) as PersonalizationSourceUpdateResult.Activated
        val activeBeforeConflict = controller.activeRuntimeSnapshot()

        val conflict = controller.commitEdit(
            expectedGenerationId = opened.storeSnapshot.generation.generationId,
            edit = pin(opened.storeSnapshot.data, "Veraltete Änderung", 12_000),
            committedAt = 12_000,
        )

        assertTrue(conflict is PersonalizationSourceUpdateResult.Conflict)
        assertEquals(firstUpdate.runtimeSnapshot.generationId, activeBeforeConflict?.generationId)
        assertEquals(activeBeforeConflict, controller.activeRuntimeSnapshot())
    }

    @Test
    fun compilerFailureLeavesPreviousRuntimeActiveButCommittedStoreRecoverable() {
        val controller = PersonalizationSourceController(store) { snapshot ->
            if (snapshot.generation.generation >= 2L) {
                throw IllegalStateException("Synthetic runtime compiler failure")
            }
            PersonalizationRuntimeSnapshotCompiler.compile(snapshot)
        }
        val opened = controller.open(
            PersonalizationTestFixtures.validData(),
            committedAt = 10_000,
        ) as PersonalizationSourceOpenResult.Ready
        val activeBefore = controller.activeRuntimeSnapshot()

        val result = controller.commitEdit(
            expectedGenerationId = opened.storeSnapshot.generation.generationId,
            edit = pin(opened.storeSnapshot.data, "Persistiert", 11_000),
            committedAt = 11_000,
        )

        assertTrue(result is PersonalizationSourceUpdateResult.RuntimeActivationFailed)
        val failed = result as PersonalizationSourceUpdateResult.RuntimeActivationFailed
        assertEquals(2L, failed.committedStoreSnapshot.generation.generation)
        assertEquals(activeBefore, controller.activeRuntimeSnapshot())

        val persisted = store.readCurrent() as PersonalizationStoreReadResult.Ready
        assertEquals(2L, persisted.snapshot.generation.generation)
        assertTrue(persisted.snapshot.data.manualWords.any { it.word == "Persistiert" })

        val recoveredController = PersonalizationSourceController(store)
        val reloaded = recoveredController.reload() as PersonalizationSourceOpenResult.Ready
        assertEquals(2L, reloaded.runtimeSnapshot.generation)
        assertNotNull(recoveredController.activeRuntimeSnapshot())
    }

    @Test
    fun unchangedCommitKeepsExistingRuntimeWithoutRecompilation() {
        var compileCount = 0
        val controller = PersonalizationSourceController(store) { snapshot ->
            compileCount += 1
            PersonalizationRuntimeSnapshotCompiler.compile(snapshot)
        }
        val opened = controller.open(
            PersonalizationTestFixtures.validData(),
            committedAt = 10_000,
        ) as PersonalizationSourceOpenResult.Ready
        val unchangedEdit = PersonalizationEditResult(
            data = opened.storeSnapshot.data,
            changes = emptyList(),
        )

        val result = controller.commitEdit(
            expectedGenerationId = opened.storeSnapshot.generation.generationId,
            edit = unchangedEdit,
            committedAt = 11_000,
        )

        assertTrue(result is PersonalizationSourceUpdateResult.Unchanged)
        assertEquals(1, compileCount)
        assertEquals(opened.runtimeSnapshot, controller.activeRuntimeSnapshot())
    }

    private fun pin(
        data: PersonalizationDataSet,
        word: String,
        editedAt: Long,
    ): PersonalizationEditResult {
        val ids = ArrayDeque(
            listOf(
                "99999999-9999-4999-8999-999999999999",
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            ),
        )
        return PersonalizationEditPlanner.pinWord(
            data = data,
            locale = "de-DE",
            word = word,
            editedAt = editedAt,
            idFactory = PersonalizationIdFactory { ids.removeFirst() },
        )
    }
}
