package org.futo.inputmethod.latin.personalization

import java.text.Normalizer
import java.util.Locale
import java.util.UUID

fun interface PersonalizationIdFactory {
    fun newId(): String
}

object RandomPersonalizationIdFactory : PersonalizationIdFactory {
    override fun newId(): String = UUID.randomUUID().toString()
}

enum class PersonalizationEditKind {
    RecordAdded,
    RecordUpdated,
    RecordDeleted,
    TombstoneAdded,
}

data class PersonalizationEditChange(
    val kind: PersonalizationEditKind,
    val recordKind: PersonalizationRecordKind,
    val recordId: String,
    val message: String,
)

data class PersonalizationEditResult(
    val data: PersonalizationDataSet,
    val changes: List<PersonalizationEditChange>,
) {
    val changed: Boolean
        get() = changes.isNotEmpty()
}

object PersonalizationEditPlanner {
    fun forgetWord(
        data: PersonalizationDataSet,
        locale: String?,
        word: String,
        editedAt: Long,
        originDeviceId: String? = null,
        removeRelatedNgrams: Boolean = true,
    ): PersonalizationEditResult {
        requireValidInput(data, word, editedAt)
        val changes = mutableListOf<PersonalizationEditChange>()
        val normalizedWord = normalizeText(word)

        val removedWords = data.learnedWords.filter {
            localeMatches(locale, it.locale) && normalizeText(it.word) == normalizedWord
        }
        val remainingWords = data.learnedWords - removedWords.toSet()

        val removedNgrams = if (removeRelatedNgrams) {
            data.learnedNgrams.filter { ngram ->
                localeMatches(locale, ngram.locale) &&
                    ngram.terms.any { normalizeText(it) == normalizedWord }
            }
        } else {
            emptyList()
        }
        val remainingNgrams = data.learnedNgrams - removedNgrams.toSet()

        var tombstones = data.tombstones
        removedWords.forEach { record ->
            tombstones = upsertTombstone(
                tombstones,
                tombstoneFor(
                    record,
                    PersonalizationRecordKind.LearnedWord,
                    editedAt,
                    PersonalizationDeletionReason.UserDelete,
                    originDeviceId,
                ),
            )
            changes += deletedChange(PersonalizationRecordKind.LearnedWord, record.id, record.word)
        }
        removedNgrams.forEach { record ->
            tombstones = upsertTombstone(
                tombstones,
                tombstoneFor(
                    record,
                    PersonalizationRecordKind.LearnedNgram,
                    editedAt,
                    PersonalizationDeletionReason.UserDelete,
                    originDeviceId,
                ),
            )
            changes += deletedChange(
                PersonalizationRecordKind.LearnedNgram,
                record.id,
                record.terms.joinToString(" "),
            )
        }

        return validatedResult(
            data.copy(
                learnedWords = remainingWords,
                learnedNgrams = remainingNgrams,
                tombstones = tombstones,
            ),
            changes,
        )
    }

    fun neverLearnWord(
        data: PersonalizationDataSet,
        locale: String?,
        word: String,
        editedAt: Long,
        originDeviceId: String? = null,
        idFactory: PersonalizationIdFactory = RandomPersonalizationIdFactory,
    ): PersonalizationEditResult {
        requireValidInput(data, word, editedAt)
        val forgotten = forgetWord(
            data = data,
            locale = locale,
            word = word,
            editedAt = editedAt,
            originDeviceId = originDeviceId,
            removeRelatedNgrams = true,
        )
        val newlyDeletedIds = forgotten.changes
            .asSequence()
            .filter { it.kind == PersonalizationEditKind.RecordDeleted }
            .map { it.recordId.lowercase(Locale.ROOT) }
            .toSet()
        val changes = forgotten.changes.toMutableList()
        val ruleResult = upsertWordRule(
            data = forgotten.data,
            locale = locale,
            word = word,
            action = WordRuleAction.DoNotLearn,
            editedAt = editedAt,
            originDeviceId = originDeviceId,
            idFactory = idFactory,
        )
        changes += ruleResult.changes

        val tombstones = ruleResult.data.tombstones.map { tombstone ->
            if (tombstone.reason == PersonalizationDeletionReason.UserDelete &&
                tombstone.targetId.lowercase(Locale.ROOT) in newlyDeletedIds
            ) {
                tombstone.copy(reason = PersonalizationDeletionReason.NeverLearn)
            } else {
                tombstone
            }
        }
        return validatedResult(ruleResult.data.copy(tombstones = tombstones), changes)
    }

    fun pinWord(
        data: PersonalizationDataSet,
        locale: String?,
        word: String,
        shortcut: String? = null,
        frequency: Int = 250,
        editedAt: Long,
        originDeviceId: String? = null,
        idFactory: PersonalizationIdFactory = RandomPersonalizationIdFactory,
    ): PersonalizationEditResult {
        requireValidInput(data, word, editedAt)
        require(frequency in 0..255) { "frequency must be between 0 and 255." }
        require(shortcut == null || shortcut.isNotBlank()) { "shortcut must not be blank." }

        val changes = mutableListOf<PersonalizationEditChange>()
        val existing = data.manualWords.firstOrNull {
            sameLocale(it.locale, locale) && normalizeText(it.word) == normalizeText(word) &&
                normalizeNullableText(it.shortcut) == normalizeNullableText(shortcut)
        }
        val manualWords = if (existing == null) {
            val record = ManualWordRecord(
                id = idFactory.newId(),
                revision = 1,
                createdAt = editedAt,
                updatedAt = editedAt,
                originDeviceId = originDeviceId,
                locale = locale,
                word = word,
                shortcut = shortcut,
                frequency = frequency,
                source = ManualWordSource.Manual,
            )
            changes += PersonalizationEditChange(
                PersonalizationEditKind.RecordAdded,
                PersonalizationRecordKind.ManualWord,
                record.id,
                "Manual word '$word' was added.",
            )
            data.manualWords + record
        } else {
            val updated = existing.copy(
                revision = existing.revision + 1,
                updatedAt = editedAt,
                originDeviceId = originDeviceId ?: existing.originDeviceId,
                frequency = frequency,
                source = ManualWordSource.Manual,
            )
            changes += PersonalizationEditChange(
                PersonalizationEditKind.RecordUpdated,
                PersonalizationRecordKind.ManualWord,
                existing.id,
                "Manual word '$word' was updated.",
            )
            data.manualWords.map { if (it.id == existing.id) updated else it }
        }

        val ruleResult = upsertWordRule(
            data = data.copy(manualWords = manualWords),
            locale = locale,
            word = word,
            action = WordRuleAction.Pin,
            editedAt = editedAt,
            originDeviceId = originDeviceId,
            idFactory = idFactory,
        )
        changes += ruleResult.changes
        return validatedResult(ruleResult.data, changes)
    }

    fun setWordLearningRule(
        data: PersonalizationDataSet,
        locale: String?,
        word: String,
        action: WordRuleAction,
        editedAt: Long,
        originDeviceId: String? = null,
        idFactory: PersonalizationIdFactory = RandomPersonalizationIdFactory,
    ): PersonalizationEditResult {
        requireValidInput(data, word, editedAt)
        return upsertWordRule(
            data,
            locale,
            word,
            action,
            editedAt,
            originDeviceId,
            idFactory,
        )
    }

    fun setCorrectionRule(
        data: PersonalizationDataSet,
        locale: String?,
        typed: String,
        replacement: String,
        action: CorrectionRuleAction,
        appScope: String? = null,
        editedAt: Long,
        originDeviceId: String? = null,
        idFactory: PersonalizationIdFactory = RandomPersonalizationIdFactory,
    ): PersonalizationEditResult {
        requireValidInput(data, typed, editedAt)
        require(replacement.isNotBlank()) { "replacement must not be blank." }
        require(normalizeText(typed) != normalizeText(replacement)) {
            "typed and replacement must differ."
        }

        val existing = data.correctionRules.firstOrNull {
            sameLocale(it.locale, locale) &&
                it.appScope.equals(appScope, ignoreCase = true) &&
                normalizeText(it.typed) == normalizeText(typed) &&
                normalizeText(it.replacement) == normalizeText(replacement)
        }
        val changes = mutableListOf<PersonalizationEditChange>()
        val correctionRules = if (existing == null) {
            val record = CorrectionRuleRecord(
                id = idFactory.newId(),
                revision = 1,
                createdAt = editedAt,
                updatedAt = editedAt,
                originDeviceId = originDeviceId,
                locale = locale,
                typed = typed,
                replacement = replacement,
                action = action,
                appScope = appScope,
            )
            changes += PersonalizationEditChange(
                PersonalizationEditKind.RecordAdded,
                PersonalizationRecordKind.CorrectionRule,
                record.id,
                "Correction rule '$typed' → '$replacement' was added.",
            )
            data.correctionRules + record
        } else {
            val updated = existing.copy(
                revision = existing.revision + 1,
                updatedAt = editedAt,
                originDeviceId = originDeviceId ?: existing.originDeviceId,
                action = action,
            )
            changes += PersonalizationEditChange(
                PersonalizationEditKind.RecordUpdated,
                PersonalizationRecordKind.CorrectionRule,
                existing.id,
                "Correction rule '$typed' → '$replacement' was updated.",
            )
            data.correctionRules.map { if (it.id == existing.id) updated else it }
        }

        return validatedResult(data.copy(correctionRules = correctionRules), changes)
    }

    private fun upsertWordRule(
        data: PersonalizationDataSet,
        locale: String?,
        word: String,
        action: WordRuleAction,
        editedAt: Long,
        originDeviceId: String?,
        idFactory: PersonalizationIdFactory,
    ): PersonalizationEditResult {
        val existing = data.wordRules.firstOrNull {
            sameLocale(it.locale, locale) && normalizeText(it.word) == normalizeText(word)
        }
        val changes = mutableListOf<PersonalizationEditChange>()
        val rules = if (existing == null) {
            val record = WordRuleRecord(
                id = idFactory.newId(),
                revision = 1,
                createdAt = editedAt,
                updatedAt = editedAt,
                originDeviceId = originDeviceId,
                locale = locale,
                word = word,
                action = action,
            )
            changes += PersonalizationEditChange(
                PersonalizationEditKind.RecordAdded,
                PersonalizationRecordKind.WordRule,
                record.id,
                "Word rule '$action' was added for '$word'.",
            )
            data.wordRules + record
        } else if (existing.action == action) {
            data.wordRules
        } else {
            val updated = existing.copy(
                revision = existing.revision + 1,
                updatedAt = editedAt,
                originDeviceId = originDeviceId ?: existing.originDeviceId,
                action = action,
            )
            changes += PersonalizationEditChange(
                PersonalizationEditKind.RecordUpdated,
                PersonalizationRecordKind.WordRule,
                existing.id,
                "Word rule was changed to '$action' for '$word'.",
            )
            data.wordRules.map { if (it.id == existing.id) updated else it }
        }

        return validatedResult(data.copy(wordRules = rules), changes)
    }

    private fun tombstoneFor(
        record: PersonalizationRecord,
        kind: PersonalizationRecordKind,
        editedAt: Long,
        reason: PersonalizationDeletionReason,
        originDeviceId: String?,
    ): PersonalizationTombstone {
        return PersonalizationTombstone(
            targetId = record.id,
            targetKind = kind,
            revision = record.revision + 1,
            deletedAt = editedAt,
            reason = reason,
            originDeviceId = originDeviceId,
        )
    }

    private fun upsertTombstone(
        current: List<PersonalizationTombstone>,
        incoming: PersonalizationTombstone,
    ): List<PersonalizationTombstone> {
        val existing = current.firstOrNull { it.targetId.equals(incoming.targetId, true) }
        return if (existing == null) {
            current + incoming
        } else if (incoming.revision > existing.revision) {
            current.map { if (it.targetId.equals(incoming.targetId, true)) incoming else it }
        } else {
            current
        }
    }

    private fun deletedChange(
        kind: PersonalizationRecordKind,
        id: String,
        display: String,
    ): PersonalizationEditChange {
        return PersonalizationEditChange(
            PersonalizationEditKind.RecordDeleted,
            kind,
            id,
            "Learned evidence '$display' was removed and tombstoned.",
        )
    }

    private fun validatedResult(
        data: PersonalizationDataSet,
        changes: List<PersonalizationEditChange>,
    ): PersonalizationEditResult {
        val validation = PersonalizationDataValidator.validateData(data)
        check(validation.isValid) {
            "Edit produced invalid personalization data: " +
                validation.errors.joinToString { "${it.path}: ${it.message}" }
        }
        return PersonalizationEditResult(data, changes)
    }

    private fun requireValidInput(data: PersonalizationDataSet, word: String, editedAt: Long) {
        val validation = PersonalizationDataValidator.validateData(data)
        require(validation.isValid) {
            "Input personalization data is invalid: " +
                validation.errors.joinToString { "${it.path}: ${it.message}" }
        }
        require(word.isNotBlank()) { "word must not be blank." }
        require(editedAt >= 0L) { "editedAt must not be negative." }
    }

    private fun localeMatches(requested: String?, recordLocale: String): Boolean {
        return requested == null || sameLocale(requested, recordLocale)
    }

    private fun sameLocale(first: String?, second: String?): Boolean {
        return first?.lowercase(Locale.ROOT).orEmpty() ==
            second?.lowercase(Locale.ROOT).orEmpty()
    }

    private fun normalizeNullableText(value: String?): String = value?.let(::normalizeText).orEmpty()

    private fun normalizeText(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }
}
