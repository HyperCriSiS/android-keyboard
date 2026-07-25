package org.futo.inputmethod.latin.personalization

import java.util.Locale

const val LEGACY_USER_HISTORY_MAPPING_VERSION = "0.1"

private const val MAX_MIGRATION_ISSUE_SAMPLES = 8
private const val MIN_LEGACY_PROBABILITY = 0
private const val MAX_LEGACY_PROBABILITY = 255

/**
 * Aggregated, bounded diagnostic emitted while converting an opaque legacy history snapshot.
 *
 * A large history may contain hundreds of thousands of entries. Diagnostics therefore retain a
 * count and only a small bounded set of stable record IDs rather than one object per occurrence.
 */
data class LegacyUserHistoryMigrationIssue(
    val code: String,
    val count: Int,
    val sampleRecordIds: List<String>,
    val message: String,
)

data class LegacyUserHistoryMigrationResult(
    val mappingVersion: String,
    val data: PersonalizationDataSet,
    val issues: List<LegacyUserHistoryMigrationIssue>,
    val sourceTruncated: Boolean,
    val sourceComplete: Boolean,
    val rejectedWordCount: Int,
    val rejectedNgramCount: Int,
) {
    val isComplete: Boolean
        get() = sourceComplete && !sourceTruncated &&
            rejectedWordCount == 0 && rejectedNgramCount == 0

    val containsApproximations: Boolean
        get() = issues.any {
            it.code == "first_seen_approximated" ||
                it.code == "missing_historical_evidence" ||
                it.code == "invalid_legacy_timestamp" ||
                it.code == "invalid_legacy_count" ||
                it.code == "invalid_legacy_probability"
        }
}

/**
 * Converts the current binary UserHistoryDictionary inventory into portable learned records.
 *
 * Mapping policy 0.1 deliberately preserves only semantics that can be justified by the current
 * native dictionary implementation:
 *
 * - the retained native count becomes observationCount;
 * - the native timestamp is interpreted as epoch seconds for lastSeenAt when it is plausible;
 * - the effective 0..255 native probability is normalized linearly to 0.0..1.0 confidence;
 * - the true first observation cannot be reconstructed, so firstSeenAt is anchored to lastSeenAt;
 * - native level values are ignored because they are implementation details;
 * - no sentence text, application scope, or surrounding context beyond the stored n-gram is added.
 *
 * The legacy dictionary remains authoritative. This converter only creates an experimental,
 * inspectable data set and never modifies the source dictionary.
 */
object LegacyUserHistoryMigration {
    fun convert(
        inventory: LegacyUserHistoryInventory,
        migratedAt: Long,
        originDeviceId: String? = null,
    ): LegacyUserHistoryMigrationResult {
        require(migratedAt >= 0L) { "migratedAt must not be negative." }

        val issues = MigrationIssueCollector()
        val learnedWords = linkedMapOf<String, LearnedWordRecord>()
        val learnedNgrams = linkedMapOf<String, LearnedNgramRecord>()
        var rejectedWordCount = 0
        var rejectedNgramCount = 0

        inventory.words.forEach wordLoop@ { item ->
            if (item.word.isBlank()) {
                rejectedWordCount++
                issues.add(
                    code = "blank_word_rejected",
                    stableId = item.stableId,
                    message = "Blank legacy words cannot be represented as portable learned records.",
                )
                return@wordLoop
            }

            val wordEvidence = mapEvidence(
                evidence = item.evidence,
                migratedAt = migratedAt,
                stableId = item.stableId,
                issues = issues,
            )
            val incomingWord = LearnedWordRecord(
                id = item.stableId,
                revision = 1,
                createdAt = migratedAt,
                updatedAt = migratedAt,
                originDeviceId = originDeviceId,
                locale = canonicalLocale(inventory.locale),
                word = item.word,
                observationCount = wordEvidence.observationCount,
                firstSeenAt = wordEvidence.firstSeenAt,
                lastSeenAt = wordEvidence.lastSeenAt,
                confidence = wordEvidence.confidence,
                state = if (item.isNotAWord) {
                    LearnedRecordState.Suppressed
                } else {
                    LearnedRecordState.Active
                },
                source = LearnedRecordSource.MigratedUserHistory,
            )

            val wordIdKey = incomingWord.id.lowercase(Locale.ROOT)
            val previousWord = learnedWords[wordIdKey]
            if (previousWord == null) {
                learnedWords[wordIdKey] = incomingWord
            } else if (!sameLearnedWord(previousWord, incomingWord)) {
                rejectedWordCount++
                issues.add(
                    code = "conflicting_word_stable_id",
                    stableId = item.stableId,
                    message = "One legacy stable ID resolved to different learned-word content.",
                )
            } else {
                issues.add(
                    code = "duplicate_word_collapsed",
                    stableId = item.stableId,
                    message = "Equivalent duplicate learned words were collapsed by stable ID.",
                )
            }

            if (item.isPossiblyOffensive) {
                issues.add(
                    code = "offensive_flag_not_portable",
                    stableId = item.stableId,
                    message = "The legacy possibly-offensive flag has no field in portable format 0.1.",
                )
            }

            item.ngrams.forEach ngramLoop@ { ngram ->
                val terms = ngram.contextTerms + ngram.targetWord
                if (ngram.targetWord.isBlank() ||
                    ngram.contextTerms.any { it.isBlank() } ||
                    terms.size !in 2..4
                ) {
                    rejectedNgramCount++
                    issues.add(
                        code = "invalid_ngram_rejected",
                        stableId = ngram.stableId,
                        message = "Legacy n-grams must contain two to four non-blank terms.",
                    )
                    return@ngramLoop
                }

                val ngramEvidence = mapEvidence(
                    evidence = ngram.evidence,
                    migratedAt = migratedAt,
                    stableId = ngram.stableId,
                    issues = issues,
                )
                val incomingNgram = LearnedNgramRecord(
                    id = ngram.stableId,
                    revision = 1,
                    createdAt = migratedAt,
                    updatedAt = migratedAt,
                    originDeviceId = originDeviceId,
                    locale = canonicalLocale(inventory.locale),
                    terms = terms,
                    observationCount = ngramEvidence.observationCount,
                    firstSeenAt = ngramEvidence.firstSeenAt,
                    lastSeenAt = ngramEvidence.lastSeenAt,
                    confidence = ngramEvidence.confidence,
                    state = LearnedRecordState.Active,
                    source = LearnedRecordSource.MigratedUserHistory,
                )

                val ngramIdKey = incomingNgram.id.lowercase(Locale.ROOT)
                val previousNgram = learnedNgrams[ngramIdKey]
                if (previousNgram == null) {
                    learnedNgrams[ngramIdKey] = incomingNgram
                } else if (!sameLearnedNgram(previousNgram, incomingNgram)) {
                    rejectedNgramCount++
                    issues.add(
                        code = "conflicting_ngram_stable_id",
                        stableId = ngram.stableId,
                        message = "One legacy stable ID resolved to different learned n-gram content.",
                    )
                } else {
                    issues.add(
                        code = "duplicate_ngram_collapsed",
                        stableId = ngram.stableId,
                        message = "Equivalent duplicate learned n-grams were collapsed by stable ID.",
                    )
                }
            }
        }

        val data = PersonalizationDataSet(
            formatVersion = PERSONALIZATION_FORMAT_VERSION,
            learnedWords = learnedWords.values.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { record: LearnedWordRecord -> record.word }
                    .thenBy { it.id },
            ),
            learnedNgrams = learnedNgrams.values.sortedWith(
                compareBy<LearnedNgramRecord> {
                    it.terms.joinToString("\u001f").lowercase(Locale.ROOT)
                }.thenBy { it.id },
            ),
        )

        val validation = PersonalizationDataValidator.validateData(data)
        check(validation.isValid) {
            "Generated legacy-history data failed semantic validation: " +
                validation.errors.joinToString { "${it.path}: ${it.message}" }
        }

        return LegacyUserHistoryMigrationResult(
            mappingVersion = LEGACY_USER_HISTORY_MAPPING_VERSION,
            data = data,
            issues = issues.build(),
            sourceTruncated = inventory.truncated,
            sourceComplete = inventory.complete,
            rejectedWordCount = rejectedWordCount,
            rejectedNgramCount = rejectedNgramCount,
        )
    }

    private data class PortableEvidence(
        val observationCount: Long,
        val firstSeenAt: Long,
        val lastSeenAt: Long,
        val confidence: Double,
    )

    private fun mapEvidence(
        evidence: LegacyProbabilityEvidence,
        migratedAt: Long,
        stableId: String,
        issues: MigrationIssueCollector,
    ): PortableEvidence {
        val observationCount = if (evidence.hasHistoricalInfo &&
            evidence.countRaw != null && evidence.countRaw > 0
        ) {
            evidence.countRaw.toLong()
        } else {
            issues.add(
                code = if (evidence.hasHistoricalInfo) {
                    "invalid_legacy_count"
                } else {
                    "missing_historical_evidence"
                },
                stableId = stableId,
                message = if (evidence.hasHistoricalInfo) {
                    "A missing or non-positive retained count was replaced by one portable observation."
                } else {
                    "Legacy historical evidence was unavailable; one import observation was used."
                },
            )
            1L
        }

        val timestampMillis = evidence.timestampRaw
            ?.takeIf { evidence.hasHistoricalInfo && it >= 0 }
            ?.toLong()
            ?.times(1_000L)
            ?.takeIf { it <= migratedAt }

        val lastSeenAt = timestampMillis ?: migratedAt.also {
            if (evidence.hasHistoricalInfo) {
                issues.add(
                    code = "invalid_legacy_timestamp",
                    stableId = stableId,
                    message = "An invalid or future legacy timestamp was anchored to migration time.",
                )
            }
        }

        issues.add(
            code = "first_seen_approximated",
            stableId = stableId,
            message = "Legacy history stores no recoverable first-seen time; lastSeenAt is used as a conservative anchor.",
        )

        if (evidence.hasHistoricalInfo && evidence.levelRaw != null && evidence.levelRaw != 0) {
            issues.add(
                code = "legacy_level_ignored",
                stableId = stableId,
                message = "The native legacy level is implementation-specific and was not exported.",
            )
        }

        val probability = evidence.probability
        val confidence = if (probability in MIN_LEGACY_PROBABILITY..MAX_LEGACY_PROBABILITY) {
            probability.toDouble() / MAX_LEGACY_PROBABILITY.toDouble()
        } else {
            issues.add(
                code = "invalid_legacy_probability",
                stableId = stableId,
                message = "An out-of-range legacy probability was clamped before confidence normalization.",
            )
            probability.coerceIn(MIN_LEGACY_PROBABILITY, MAX_LEGACY_PROBABILITY).toDouble() /
                MAX_LEGACY_PROBABILITY.toDouble()
        }

        return PortableEvidence(
            observationCount = observationCount,
            firstSeenAt = lastSeenAt,
            lastSeenAt = lastSeenAt,
            confidence = confidence,
        )
    }

    private fun sameLearnedWord(
        first: LearnedWordRecord,
        second: LearnedWordRecord,
    ): Boolean {
        return first.locale.equals(second.locale, ignoreCase = true) &&
            first.word == second.word &&
            first.observationCount == second.observationCount &&
            first.firstSeenAt == second.firstSeenAt &&
            first.lastSeenAt == second.lastSeenAt &&
            first.confidence == second.confidence &&
            first.state == second.state &&
            first.source == second.source
    }

    private fun sameLearnedNgram(
        first: LearnedNgramRecord,
        second: LearnedNgramRecord,
    ): Boolean {
        return first.locale.equals(second.locale, ignoreCase = true) &&
            first.terms == second.terms &&
            first.observationCount == second.observationCount &&
            first.firstSeenAt == second.firstSeenAt &&
            first.lastSeenAt == second.lastSeenAt &&
            first.confidence == second.confidence &&
            first.state == second.state &&
            first.source == second.source
    }

    private fun canonicalLocale(locale: String): String {
        return Locale.forLanguageTag(locale.replace('_', '-')).toLanguageTag()
    }

    private class MigrationIssueCollector {
        private data class MutableIssue(
            var count: Int,
            val sampleRecordIds: MutableList<String>,
            val message: String,
        )

        private val issues = linkedMapOf<String, MutableIssue>()

        fun add(code: String, stableId: String, message: String) {
            val issue = issues.getOrPut(code) {
                MutableIssue(count = 0, sampleRecordIds = mutableListOf(), message = message)
            }
            issue.count++
            if (issue.sampleRecordIds.size < MAX_MIGRATION_ISSUE_SAMPLES &&
                stableId !in issue.sampleRecordIds
            ) {
                issue.sampleRecordIds += stableId
            }
        }

        fun build(): List<LegacyUserHistoryMigrationIssue> {
            return issues.map { (code, issue) ->
                LegacyUserHistoryMigrationIssue(
                    code = code,
                    count = issue.count,
                    sampleRecordIds = issue.sampleRecordIds.toList(),
                    message = issue.message,
                )
            }
        }
    }
}
