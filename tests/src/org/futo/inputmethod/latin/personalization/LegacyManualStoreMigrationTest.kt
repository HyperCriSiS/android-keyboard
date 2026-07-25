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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@MediumTest
@RunWith(AndroidJUnit4::class)
class LegacyManualStoreMigrationTest {
    private lateinit var root: File
    private lateinit var store: TransactionalPersonalizationStore
    private lateinit var service: LegacyManualStoreMigrationService

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getTargetContext()
        root = File(context.cacheDir, "legacy-manual-migration-${System.nanoTime()}")
        store = TransactionalPersonalizationStore(root)
        service = LegacyManualStoreMigrationService(store)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun newStoreMigrationIsAppliedAndSecondRunIsIdempotent() {
        val inventory = inventory(word = "HyperCriSiS", frequency = 200)
        val firstPlan = LegacyManualStoreMigrationPlanner.plan(
            inventory = inventory,
            currentSnapshot = null,
            migratedAt = 10_000,
        )

        assertTrue(firstPlan.canApply)
        assertEquals(1, firstPlan.additions)
        val firstApply = service.apply(firstPlan, committedAt = 10_000)
        assertTrue(firstApply is LegacyManualStoreMigrationApplyResult.Applied)
        assertTrue((firstApply as LegacyManualStoreMigrationApplyResult.Applied).initialized)
        assertEquals(1, firstApply.storeSnapshot.data.manualWords.size)

        val current = (service.readCurrent() as PersonalizationStoreReadResult.Ready).snapshot
        val secondPlan = LegacyManualStoreMigrationPlanner.plan(
            inventory = inventory,
            currentSnapshot = current,
            migratedAt = 20_000,
        )

        assertTrue(secondPlan.canApply)
        assertTrue(secondPlan.isAlreadyUpToDate)
        assertEquals(0, secondPlan.additions)
        assertEquals(1, secondPlan.alreadyPresent)
        val secondApply = service.apply(secondPlan, committedAt = 20_000)
        assertTrue(secondApply is LegacyManualStoreMigrationApplyResult.Unchanged)
        assertEquals(1, service.history().size)
    }

    @Test
    fun tombstonePreventsMigrationFromResurrectingDeletedWord() {
        val source = inventory(word = "gelöscht", frequency = 180)
        val stableId = source.words.single().stableId
        val data = TransactionalPersonalizationStore.emptyDataSet().copy(
            tombstones = listOf(
                PersonalizationTombstone(
                    targetId = stableId,
                    targetKind = PersonalizationRecordKind.ManualWord,
                    revision = 2,
                    deletedAt = 9_000,
                    reason = PersonalizationDeletionReason.UserDelete,
                ),
            ),
        )
        val initialized = store.initialize(data, committedAt = 9_000)
            as PersonalizationStoreInitializeResult.Initialized

        val plan = LegacyManualStoreMigrationPlanner.plan(
            inventory = source,
            currentSnapshot = initialized.snapshot,
            migratedAt = 10_000,
        )

        assertTrue(plan.canApply)
        assertEquals(0, plan.additions)
        assertEquals(1, plan.suppressedByTombstone)
        assertFalse(plan.previewData!!.manualWords.any { it.id == stableId })
    }

    @Test
    fun changedExistingRecordProducesConflictInsteadOfOverwrite() {
        val source = inventory(word = "FUTO", frequency = 180)
        val migration = LegacyManualWordMigration.convert(source, migratedAt = 9_000)
        val locallyEdited = migration.records.single().copy(
            revision = 2,
            updatedAt = 9_500,
            frequency = 250,
            source = ManualWordSource.Manual,
        )
        val initialized = store.initialize(
            TransactionalPersonalizationStore.emptyDataSet().copy(
                manualWords = listOf(locallyEdited),
            ),
            committedAt = 9_500,
        ) as PersonalizationStoreInitializeResult.Initialized

        val plan = LegacyManualStoreMigrationPlanner.plan(
            inventory = source,
            currentSnapshot = initialized.snapshot,
            migratedAt = 10_000,
        )

        assertFalse(plan.canApply)
        assertEquals(1, plan.conflicts.size)
        assertEquals("stable_id_content_conflict", plan.conflicts.single().code)
        assertEquals(250, initialized.snapshot.data.manualWords.single().frequency)
        assertTrue(
            service.apply(plan, committedAt = 10_000) is
                LegacyManualStoreMigrationApplyResult.Rejected,
        )
    }

    @Test
    fun truncatedInventoryCannotBeApplied() {
        val source = inventory(word = "unvollständig", frequency = 180).copy(truncated = true)
        val plan = LegacyManualStoreMigrationPlanner.plan(
            inventory = source,
            currentSnapshot = null,
            migratedAt = 10_000,
        )

        assertFalse(plan.canApply)
        assertTrue(
            service.apply(plan, committedAt = 10_000) is
                LegacyManualStoreMigrationApplyResult.Rejected,
        )
        assertTrue(service.readCurrent() is PersonalizationStoreReadResult.Empty)
    }

    @Test
    fun logicalDuplicateWithDifferentIdRequiresReview() {
        val source = inventory(word = "Projektname", frequency = 190)
        val duplicate = LegacyManualWordMigration.convert(source, migratedAt = 9_000)
            .records.single()
            .copy(id = "99999999-9999-4999-8999-999999999999")
        val initialized = store.initialize(
            TransactionalPersonalizationStore.emptyDataSet().copy(
                manualWords = listOf(duplicate),
            ),
            committedAt = 9_000,
        ) as PersonalizationStoreInitializeResult.Initialized

        val plan = LegacyManualStoreMigrationPlanner.plan(
            inventory = source,
            currentSnapshot = initialized.snapshot,
            migratedAt = 10_000,
        )

        assertFalse(plan.canApply)
        assertEquals("logical_duplicate_with_different_id", plan.conflicts.single().code)
    }

    private fun inventory(
        word: String,
        frequency: Int,
        locale: String? = "de-DE",
        shortcut: String? = null,
    ): LegacyPersonalDictionaryInventory {
        return LegacyPersonalDictionaryInventory(
            words = listOf(
                LegacyManualWordInventoryItem(
                    stableId = LegacyPersonalizationIds.manualWord(locale, word, shortcut),
                    locale = locale,
                    word = word,
                    shortcut = shortcut,
                    frequency = frequency,
                    appId = 0,
                ),
            ),
            truncated = false,
        )
    }
}
