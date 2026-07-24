package org.futo.inputmethod.latin.personalization

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class PersonalizationDataValidatorTest {
    @Test
    fun validExportPassesValidation() {
        val data = PersonalizationTestFixtures.validData()
        val bytes = PersonalizationDataCodec.encodeData(data).toByteArray(Charsets.UTF_8)
        val manifest = PersonalizationTestFixtures.validManifest(data, bytes)

        val result = PersonalizationDataValidator.validate(manifest, data, bytes)

        assertTrue(result.errors.joinToString { it.message }, result.isValid)
    }

    @Test
    fun manifestCountAndHashMismatchAreRejected() {
        val data = PersonalizationTestFixtures.validData()
        val bytes = PersonalizationDataCodec.encodeData(data).toByteArray(Charsets.UTF_8)
        val manifest = PersonalizationTestFixtures.validManifest(data, bytes).copy(
            counts = PersonalizationCounts.from(data).copy(manualWords = 99),
            data = PersonalizationPayload(
                sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
                sizeBytes = bytes.size.toLong() + 1,
            ),
        )

        val result = PersonalizationDataValidator.validate(manifest, data, bytes)
        val codes = result.errors.map { it.code }.toSet()

        assertFalse(result.isValid)
        assertTrue("record_count_mismatch" in codes)
        assertTrue("data_size_mismatch" in codes)
        assertTrue("data_hash_mismatch" in codes)
    }

    @Test
    fun duplicateLogicalLearnedWordIsRejected() {
        val original = PersonalizationTestFixtures.validData()
        val duplicate = original.learnedWords.single().copy(
            id = "99999999-9999-4999-8999-999999999999",
            revision = 1,
            word = "WAHRSCHEINLICH",
        )
        val data = original.copy(learnedWords = original.learnedWords + duplicate)

        val result = PersonalizationDataValidator.validateData(data)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.code == "duplicate_learned_word" })
    }

    @Test
    fun activeLearnedWordCannotConflictWithDoNotLearnRule() {
        val original = PersonalizationTestFixtures.validData()
        val rule = WordRuleRecord(
            id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            revision = 1,
            createdAt = 6_000,
            updatedAt = 6_000,
            locale = "de-DE",
            word = "wahrscheinlich",
            action = WordRuleAction.DoNotLearn,
        )
        val data = original.copy(wordRules = original.wordRules + rule)

        val result = PersonalizationDataValidator.validateData(data)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.code == "active_word_blocked_from_learning" })
    }

    @Test
    fun liveRecordAndTombstoneCannotCoexist() {
        val original = PersonalizationTestFixtures.validData()
        val target = original.learnedWords.single()
        val data = original.copy(
            tombstones = original.tombstones + PersonalizationTombstone(
                targetId = target.id,
                targetKind = PersonalizationRecordKind.LearnedWord,
                revision = target.revision + 1,
                deletedAt = 8_000,
                reason = PersonalizationDeletionReason.UserDelete,
            ),
        )

        val result = PersonalizationDataValidator.validateData(data)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.code == "live_record_with_tombstone" })
    }

    @Test
    fun privacyFlagsMustDescribeNgramsAndAppScopes() {
        val data = PersonalizationTestFixtures.validData()
        val bytes = PersonalizationDataCodec.encodeData(data).toByteArray(Charsets.UTF_8)
        val manifest = PersonalizationTestFixtures.validManifest(data, bytes).copy(
            privacy = PersonalizationPrivacy(
                containsNgrams = false,
                containsAppScopes = false,
                containsSentenceText = false,
                encrypted = false,
            ),
        )

        val result = PersonalizationDataValidator.validate(manifest, data, bytes)
        val codes = result.errors.map { it.code }.toSet()

        assertFalse(result.isValid)
        assertTrue("ngram_privacy_flag_mismatch" in codes)
        assertTrue("app_scope_privacy_flag_mismatch" in codes)
    }

    @Test
    fun sentenceTextFlagIsAlwaysRejected() {
        val data = PersonalizationTestFixtures.validData()
        val bytes = PersonalizationDataCodec.encodeData(data).toByteArray(Charsets.UTF_8)
        val manifest = PersonalizationTestFixtures.validManifest(data, bytes).copy(
            privacy = PersonalizationTestFixtures.validManifest(data, bytes).privacy.copy(
                containsSentenceText = true,
            ),
        )

        val result = PersonalizationDataValidator.validate(manifest, data, bytes)

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.code == "sentence_text_forbidden" })
    }
}
