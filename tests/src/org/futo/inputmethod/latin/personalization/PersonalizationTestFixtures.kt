package org.futo.inputmethod.latin.personalization

import java.security.MessageDigest

object PersonalizationTestFixtures {
    fun validData(): PersonalizationDataSet {
        return PersonalizationDataSet(
            formatVersion = PERSONALIZATION_FORMAT_VERSION,
            manualWords = listOf(
                ManualWordRecord(
                    id = "11111111-1111-4111-8111-111111111111",
                    revision = 1,
                    createdAt = 1_000,
                    updatedAt = 1_000,
                    locale = null,
                    word = "FUTO",
                    shortcut = "futo",
                    frequency = 250,
                    source = ManualWordSource.Manual,
                ),
            ),
            learnedWords = listOf(
                LearnedWordRecord(
                    id = "22222222-2222-4222-8222-222222222222",
                    revision = 3,
                    createdAt = 1_000,
                    updatedAt = 5_000,
                    locale = "de-DE",
                    word = "wahrscheinlich",
                    observationCount = 8,
                    firstSeenAt = 1_500,
                    lastSeenAt = 5_000,
                    confidence = 0.86,
                    state = LearnedRecordState.Active,
                    source = LearnedRecordSource.Typing,
                ),
            ),
            learnedNgrams = listOf(
                LearnedNgramRecord(
                    id = "33333333-3333-4333-8333-333333333333",
                    revision = 2,
                    createdAt = 1_000,
                    updatedAt = 6_000,
                    locale = "de-DE",
                    terms = listOf("sehr", "wahrscheinlich"),
                    observationCount = 4,
                    firstSeenAt = 2_000,
                    lastSeenAt = 6_000,
                    confidence = 0.72,
                    state = LearnedRecordState.Active,
                    source = LearnedRecordSource.Typing,
                ),
            ),
            wordRules = listOf(
                WordRuleRecord(
                    id = "44444444-4444-4444-8444-444444444444",
                    revision = 1,
                    createdAt = 2_000,
                    updatedAt = 2_000,
                    locale = "de-DE",
                    word = "FUTO",
                    action = WordRuleAction.Pin,
                ),
            ),
            correctionRules = listOf(
                CorrectionRuleRecord(
                    id = "55555555-5555-4555-8555-555555555555",
                    revision = 1,
                    createdAt = 2_000,
                    updatedAt = 2_000,
                    locale = "de-DE",
                    typed = "im",
                    replacement = "ihm",
                    action = CorrectionRuleAction.BlockAutocorrect,
                    appScope = "org.example.notes",
                ),
            ),
            tombstones = listOf(
                PersonalizationTombstone(
                    targetId = "66666666-6666-4666-8666-666666666666",
                    targetKind = PersonalizationRecordKind.LearnedWord,
                    revision = 4,
                    deletedAt = 7_000,
                    reason = PersonalizationDeletionReason.UserDelete,
                ),
            ),
        )
    }

    fun validManifest(
        data: PersonalizationDataSet,
        rawDataBytes: ByteArray,
    ): PersonalizationExportManifest {
        return PersonalizationExportManifest(
            formatVersion = PERSONALIZATION_FORMAT_VERSION,
            exportId = "77777777-7777-4777-8777-777777777777",
            createdAt = 8_000,
            application = PersonalizationExportApplication(
                id = "org.futo.inputmethod.latin",
                versionName = "0.1-test",
                versionCode = 1,
            ),
            originDeviceId = "88888888-8888-4888-8888-888888888888",
            includedCategories = PersonalizationCategory.entries,
            locales = data.locales().sorted(),
            privacy = PersonalizationPrivacy(
                containsNgrams = data.learnedNgrams.isNotEmpty(),
                containsAppScopes = data.correctionRules.any { it.appScope != null },
                containsSentenceText = false,
                encrypted = false,
            ),
            data = PersonalizationPayload(
                sha256 = sha256(rawDataBytes),
                sizeBytes = rawDataBytes.size.toLong(),
            ),
            counts = PersonalizationCounts.from(data),
        )
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
