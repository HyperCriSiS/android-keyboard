package org.futo.inputmethod.latin.personalization

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class PersonalizationMergePlannerTest {
    @Test
    fun newerIncomingRevisionReplacesLocalRecord() {
        val localRecord = learnedWord(revision = 1, count = 2, updatedAt = 2_000)
        val incomingRecord = localRecord.copy(
            revision = 2,
            observationCount = 4,
            updatedAt = 4_000,
            lastSeenAt = 4_000,
        )

        val plan = PersonalizationMergePlanner.plan(
            data(learnedWords = listOf(localRecord)),
            data(learnedWords = listOf(incomingRecord)),
        )

        assertTrue(plan.canApply)
        assertEquals(incomingRecord, plan.preview!!.learnedWords.single())
        assertTrue(plan.actions.any {
            it.decision == PersonalizationMergeDecision.ReplaceWithIncoming
        })
    }

    @Test
    fun incomingTombstoneDeletesOlderLocalRecord() {
        val localRecord = learnedWord(revision = 2)
        val tombstone = PersonalizationTombstone(
            targetId = localRecord.id,
            targetKind = PersonalizationRecordKind.LearnedWord,
            revision = 3,
            deletedAt = 5_000,
            reason = PersonalizationDeletionReason.UserDelete,
        )

        val plan = PersonalizationMergePlanner.plan(
            data(learnedWords = listOf(localRecord)),
            data(tombstones = listOf(tombstone)),
        )

        assertTrue(plan.canApply)
        assertTrue(plan.preview!!.learnedWords.isEmpty())
        assertEquals(tombstone, plan.preview.tombstones.single())
        assertTrue(plan.actions.any {
            it.decision == PersonalizationMergeDecision.DeleteWithIncomingTombstone
        })
    }

    @Test
    fun localNewerTombstoneSuppressesIncomingLiveRecord() {
        val incomingRecord = learnedWord(revision = 2)
        val localTombstone = PersonalizationTombstone(
            targetId = incomingRecord.id,
            targetKind = PersonalizationRecordKind.LearnedWord,
            revision = 3,
            deletedAt = 5_000,
            reason = PersonalizationDeletionReason.NeverLearn,
        )

        val plan = PersonalizationMergePlanner.plan(
            data(tombstones = listOf(localTombstone)),
            data(learnedWords = listOf(incomingRecord)),
        )

        assertTrue(plan.canApply)
        assertTrue(plan.preview!!.learnedWords.isEmpty())
        assertEquals(localTombstone, plan.preview.tombstones.single())
        assertTrue(plan.actions.any {
            it.decision == PersonalizationMergeDecision.KeepLocalTombstone
        })
    }

    @Test
    fun newerIncomingLiveRecordCanExplicitlySupersedeTombstone() {
        val incomingRecord = learnedWord(revision = 4, updatedAt = 6_000).copy(
            lastSeenAt = 6_000,
        )
        val localTombstone = PersonalizationTombstone(
            targetId = incomingRecord.id,
            targetKind = PersonalizationRecordKind.LearnedWord,
            revision = 3,
            deletedAt = 5_000,
            reason = PersonalizationDeletionReason.UserDelete,
        )

        val plan = PersonalizationMergePlanner.plan(
            data(tombstones = listOf(localTombstone)),
            data(learnedWords = listOf(incomingRecord)),
        )

        assertTrue(plan.canApply)
        assertEquals(incomingRecord, plan.preview!!.learnedWords.single())
        assertTrue(plan.preview.tombstones.isEmpty())
        assertTrue(plan.actions.any {
            it.decision == PersonalizationMergeDecision.ResurrectWithIncoming
        })
    }

    @Test
    fun sameRevisionDifferentContentRequiresConflictResolution() {
        val localRecord = learnedWord(revision = 2, count = 2)
        val incomingRecord = localRecord.copy(observationCount = 99)

        val plan = PersonalizationMergePlanner.plan(
            data(learnedWords = listOf(localRecord)),
            data(learnedWords = listOf(incomingRecord)),
        )

        assertFalse(plan.canApply)
        assertTrue(plan.conflicts.any { it.code == "same_revision_record_conflict" })
        assertTrue(plan.actions.any { it.decision == PersonalizationMergeDecision.Conflict })
    }

    @Test
    fun differentIdsWithSameLogicalWordAreNotSilentlyCombined() {
        val localRecord = learnedWord(
            id = "11111111-1111-4111-8111-111111111111",
            revision = 1,
        )
        val incomingRecord = learnedWord(
            id = "22222222-2222-4222-8222-222222222222",
            revision = 1,
        )

        val plan = PersonalizationMergePlanner.plan(
            data(learnedWords = listOf(localRecord)),
            data(learnedWords = listOf(incomingRecord)),
        )

        assertFalse(plan.canApply)
        assertNotNull(plan.preview)
        assertEquals(listOf(localRecord), plan.preview!!.learnedWords)
        assertTrue(plan.conflicts.any { it.code == "duplicate_logical_record" })
    }

    @Test
    fun invalidInputProducesNoPreview() {
        val invalid = data(
            learnedWords = listOf(learnedWord().copy(confidence = Double.NaN)),
        )

        val plan = PersonalizationMergePlanner.plan(data(), invalid)

        assertFalse(plan.canApply)
        assertEquals(null, plan.preview)
        assertTrue(plan.conflicts.any { it.code.startsWith("invalid_incoming_data:") })
    }

    private fun learnedWord(
        id: String = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        revision: Long = 1,
        count: Long = 2,
        updatedAt: Long = 3_000,
    ): LearnedWordRecord {
        return LearnedWordRecord(
            id = id,
            revision = revision,
            createdAt = 1_000,
            updatedAt = updatedAt,
            locale = "de-DE",
            word = "wahrscheinlich",
            observationCount = count,
            firstSeenAt = 1_500,
            lastSeenAt = minOf(updatedAt, 3_000),
            confidence = 0.8,
            state = LearnedRecordState.Active,
            source = LearnedRecordSource.Typing,
        )
    }

    private fun data(
        learnedWords: List<LearnedWordRecord> = emptyList(),
        tombstones: List<PersonalizationTombstone> = emptyList(),
    ): PersonalizationDataSet {
        return PersonalizationDataSet(
            formatVersion = PERSONALIZATION_FORMAT_VERSION,
            learnedWords = learnedWords,
            tombstones = tombstones,
        )
    }
}
