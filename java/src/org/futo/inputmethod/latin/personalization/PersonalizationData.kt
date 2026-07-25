package org.futo.inputmethod.latin.personalization

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val PERSONALIZATION_FORMAT_VERSION = "0.1"
const val PERSONALIZATION_DATA_MEDIA_TYPE =
    "application/vnd.futo.keyboard.personalization+json"

@Serializable
data class PersonalizationExportManifest(
    val formatVersion: String,
    val exportId: String,
    val createdAt: Long,
    val application: PersonalizationExportApplication,
    val originDeviceId: String? = null,
    val includedCategories: List<PersonalizationCategory>,
    val locales: List<String>,
    val privacy: PersonalizationPrivacy,
    val data: PersonalizationPayload,
    val counts: PersonalizationCounts,
)

@Serializable
data class PersonalizationExportApplication(
    val id: String,
    val versionName: String,
    val versionCode: Long,
)

@Serializable
enum class PersonalizationCategory {
    @SerialName("manual-words")
    ManualWords,

    @SerialName("learned-words")
    LearnedWords,

    @SerialName("learned-ngrams")
    LearnedNgrams,

    @SerialName("word-rules")
    WordRules,

    @SerialName("correction-rules")
    CorrectionRules,

    @SerialName("tombstones")
    Tombstones,
}

@Serializable
data class PersonalizationPrivacy(
    val containsNgrams: Boolean,
    val containsAppScopes: Boolean,
    val containsSentenceText: Boolean = false,
    val encrypted: Boolean,
)

@Serializable
data class PersonalizationPayload(
    val path: String = "data.json",
    val mediaType: String = PERSONALIZATION_DATA_MEDIA_TYPE,
    val sha256: String,
    val sizeBytes: Long,
)

@Serializable
data class PersonalizationCounts(
    val manualWords: Int,
    val learnedWords: Int,
    val learnedNgrams: Int,
    val wordRules: Int,
    val correctionRules: Int,
    val tombstones: Int,
) {
    companion object {
        fun from(data: PersonalizationDataSet): PersonalizationCounts = PersonalizationCounts(
            manualWords = data.manualWords.size,
            learnedWords = data.learnedWords.size,
            learnedNgrams = data.learnedNgrams.size,
            wordRules = data.wordRules.size,
            correctionRules = data.correctionRules.size,
            tombstones = data.tombstones.size,
        )
    }
}

@Serializable
data class PersonalizationDataSet(
    val formatVersion: String,
    val manualWords: List<ManualWordRecord> = emptyList(),
    val learnedWords: List<LearnedWordRecord> = emptyList(),
    val learnedNgrams: List<LearnedNgramRecord> = emptyList(),
    val wordRules: List<WordRuleRecord> = emptyList(),
    val correctionRules: List<CorrectionRuleRecord> = emptyList(),
    val tombstones: List<PersonalizationTombstone> = emptyList(),
) {
    fun allRecordIds(): List<String> = buildList {
        manualWords.forEach { add(it.id) }
        learnedWords.forEach { add(it.id) }
        learnedNgrams.forEach { add(it.id) }
        wordRules.forEach { add(it.id) }
        correctionRules.forEach { add(it.id) }
    }

    fun locales(): Set<String> = buildSet {
        manualWords.mapNotNullTo(this) { it.locale }
        learnedWords.mapTo(this) { it.locale }
        learnedNgrams.mapTo(this) { it.locale }
        wordRules.mapNotNullTo(this) { it.locale }
        correctionRules.mapNotNullTo(this) { it.locale }
    }
}

interface PersonalizationRecord {
    val id: String
    val revision: Long
    val createdAt: Long
    val updatedAt: Long
    val originDeviceId: String?
}

@Serializable
data class ManualWordRecord(
    override val id: String,
    override val revision: Long,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val originDeviceId: String? = null,
    val locale: String? = null,
    val word: String,
    val shortcut: String? = null,
    val frequency: Int,
    val source: ManualWordSource,
) : PersonalizationRecord

@Serializable
enum class ManualWordSource {
    @SerialName("manual")
    Manual,

    @SerialName("imported")
    Imported,

    @SerialName("migrated-android-user-dictionary")
    MigratedAndroidUserDictionary,
}

@Serializable
data class LearnedWordRecord(
    override val id: String,
    override val revision: Long,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val originDeviceId: String? = null,
    val locale: String,
    val word: String,
    val observationCount: Long,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val confidence: Double,
    val state: LearnedRecordState,
    val source: LearnedRecordSource,
) : PersonalizationRecord

@Serializable
data class LearnedNgramRecord(
    override val id: String,
    override val revision: Long,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val originDeviceId: String? = null,
    val locale: String,
    val terms: List<String>,
    val observationCount: Long,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val confidence: Double,
    val state: LearnedRecordState,
    val source: LearnedRecordSource,
) : PersonalizationRecord

@Serializable
enum class LearnedRecordState {
    @SerialName("active")
    Active,

    @SerialName("suppressed")
    Suppressed,
}

@Serializable
enum class LearnedRecordSource {
    @SerialName("typing")
    Typing,

    @SerialName("manual-pick")
    ManualPick,

    @SerialName("autocorrect-undo")
    AutocorrectUndo,

    @SerialName("imported")
    Imported,

    @SerialName("migrated-user-history")
    MigratedUserHistory,
}

@Serializable
data class WordRuleRecord(
    override val id: String,
    override val revision: Long,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val originDeviceId: String? = null,
    val locale: String? = null,
    val word: String,
    val action: WordRuleAction,
) : PersonalizationRecord

@Serializable
enum class WordRuleAction {
    @SerialName("allow-learning")
    AllowLearning,

    @SerialName("do-not-learn")
    DoNotLearn,

    @SerialName("pin")
    Pin,
}

@Serializable
data class CorrectionRuleRecord(
    override val id: String,
    override val revision: Long,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val originDeviceId: String? = null,
    val locale: String? = null,
    val typed: String,
    val replacement: String,
    val action: CorrectionRuleAction,
    val appScope: String? = null,
) : PersonalizationRecord

@Serializable
enum class CorrectionRuleAction {
    @SerialName("allow")
    Allow,

    @SerialName("prefer")
    Prefer,

    @SerialName("block-autocorrect")
    BlockAutocorrect,

    @SerialName("block-suggestion")
    BlockSuggestion,
}

@Serializable
data class PersonalizationTombstone(
    val targetId: String,
    val targetKind: PersonalizationRecordKind,
    val revision: Long,
    val deletedAt: Long,
    val reason: PersonalizationDeletionReason,
    val originDeviceId: String? = null,
)

@Serializable
enum class PersonalizationRecordKind {
    @SerialName("manual-word")
    ManualWord,

    @SerialName("learned-word")
    LearnedWord,

    @SerialName("learned-ngram")
    LearnedNgram,

    @SerialName("word-rule")
    WordRule,

    @SerialName("correction-rule")
    CorrectionRule,
}

@Serializable
enum class PersonalizationDeletionReason {
    @SerialName("user-delete")
    UserDelete,

    @SerialName("never-learn")
    NeverLearn,

    @SerialName("retention-policy")
    RetentionPolicy,

    @SerialName("merge-resolution")
    MergeResolution,

    @SerialName("migration-rollback")
    MigrationRollback,
}
