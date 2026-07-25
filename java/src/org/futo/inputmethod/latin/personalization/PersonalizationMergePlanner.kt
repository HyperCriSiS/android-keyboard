package org.futo.inputmethod.latin.personalization

import java.text.Normalizer
import java.util.Locale

enum class PersonalizationMergeDecision {
    KeepLocal,
    AddIncoming,
    ReplaceWithIncoming,
    DeleteWithIncomingTombstone,
    KeepLocalTombstone,
    AddIncomingTombstone,
    ResurrectWithIncoming,
    Conflict,
}

data class PersonalizationMergeAction(
    val decision: PersonalizationMergeDecision,
    val recordKind: PersonalizationRecordKind,
    val localId: String? = null,
    val incomingId: String? = null,
    val logicalKey: String? = null,
    val message: String,
)

data class PersonalizationMergeConflict(
    val code: String,
    val recordKind: PersonalizationRecordKind?,
    val localId: String? = null,
    val incomingId: String? = null,
    val logicalKey: String? = null,
    val message: String,
)

data class PersonalizationMergePlan(
    val preview: PersonalizationDataSet?,
    val actions: List<PersonalizationMergeAction>,
    val conflicts: List<PersonalizationMergeConflict>,
) {
    val canApply: Boolean
        get() = preview != null && conflicts.isEmpty()
}

object PersonalizationMergePlanner {
    fun plan(
        local: PersonalizationDataSet,
        incoming: PersonalizationDataSet,
    ): PersonalizationMergePlan {
        val actions = mutableListOf<PersonalizationMergeAction>()
        val conflicts = mutableListOf<PersonalizationMergeConflict>()

        val localValidation = PersonalizationDataValidator.validateData(local)
        val incomingValidation = PersonalizationDataValidator.validateData(incoming)
        if (!localValidation.isValid || !incomingValidation.isValid) {
            localValidation.errors.forEach { issue ->
                conflicts += PersonalizationMergeConflict(
                    code = "invalid_local_data:${issue.code}",
                    recordKind = null,
                    message = "Local data is invalid at ${issue.path}: ${issue.message}",
                )
            }
            incomingValidation.errors.forEach { issue ->
                conflicts += PersonalizationMergeConflict(
                    code = "invalid_incoming_data:${issue.code}",
                    recordKind = null,
                    message = "Incoming data is invalid at ${issue.path}: ${issue.message}",
                )
            }
            return PersonalizationMergePlan(null, emptyList(), conflicts)
        }

        val liveById = linkedMapOf<String, RecordEnvelope>()
        local.liveRecords().forEach { liveById[it.idKey] = it }
        val tombstonesById = linkedMapOf<String, PersonalizationTombstone>()
        local.tombstones.forEach { tombstonesById[idKey(it.targetId)] = it }

        incoming.tombstones
            .sortedWith(compareBy<PersonalizationTombstone> { idKey(it.targetId) }.thenBy { it.revision })
            .forEach { incomingTombstone ->
                mergeTombstone(
                    incomingTombstone,
                    liveById,
                    tombstonesById,
                    actions,
                    conflicts,
                )
            }

        incoming.liveRecords()
            .sortedWith(compareBy<RecordEnvelope> { it.idKey }.thenBy { it.revision })
            .forEach { incomingRecord ->
                mergeLiveRecord(
                    incomingRecord,
                    liveById,
                    tombstonesById,
                    actions,
                    conflicts,
                )
            }

        val preview = buildDataSet(
            formatVersion = local.formatVersion,
            records = liveById.values,
            tombstones = tombstonesById.values,
        )
        val previewValidation = PersonalizationDataValidator.validateData(preview)
        previewValidation.errors.forEach { issue ->
            conflicts += PersonalizationMergeConflict(
                code = "invalid_merge_preview:${issue.code}",
                recordKind = null,
                message = "Merge preview is invalid at ${issue.path}: ${issue.message}",
            )
        }

        return PersonalizationMergePlan(
            preview = preview.takeIf { previewValidation.isValid },
            actions = actions,
            conflicts = conflicts,
        )
    }

    private fun mergeTombstone(
        incoming: PersonalizationTombstone,
        liveById: MutableMap<String, RecordEnvelope>,
        tombstonesById: MutableMap<String, PersonalizationTombstone>,
        actions: MutableList<PersonalizationMergeAction>,
        conflicts: MutableList<PersonalizationMergeConflict>,
    ) {
        val key = idKey(incoming.targetId)
        val localLive = liveById[key]
        if (localLive != null) {
            if (incoming.targetKind != localLive.kind) {
                conflicts += PersonalizationMergeConflict(
                    code = "tombstone_kind_mismatch",
                    recordKind = incoming.targetKind,
                    localId = localLive.id,
                    incomingId = incoming.targetId,
                    message = "Incoming tombstone kind does not match the local live record.",
                )
                actions += conflictAction(incoming.targetKind, localLive.id, incoming.targetId)
                return
            }
            if (incoming.revision >= localLive.revision) {
                liveById.remove(key)
                tombstonesById[key] = incoming
                actions += PersonalizationMergeAction(
                    decision = PersonalizationMergeDecision.DeleteWithIncomingTombstone,
                    recordKind = incoming.targetKind,
                    localId = localLive.id,
                    incomingId = incoming.targetId,
                    logicalKey = localLive.logicalKey,
                    message = "Incoming tombstone supersedes the local live record.",
                )
            } else {
                actions += PersonalizationMergeAction(
                    decision = PersonalizationMergeDecision.KeepLocal,
                    recordKind = localLive.kind,
                    localId = localLive.id,
                    incomingId = incoming.targetId,
                    logicalKey = localLive.logicalKey,
                    message = "Local live record has a newer revision than the incoming tombstone.",
                )
            }
            return
        }

        val localTombstone = tombstonesById[key]
        if (localTombstone == null) {
            tombstonesById[key] = incoming
            actions += PersonalizationMergeAction(
                decision = PersonalizationMergeDecision.AddIncomingTombstone,
                recordKind = incoming.targetKind,
                incomingId = incoming.targetId,
                message = "Incoming tombstone was added.",
            )
            return
        }

        if (localTombstone.targetKind != incoming.targetKind) {
            conflicts += PersonalizationMergeConflict(
                code = "tombstone_kind_conflict",
                recordKind = incoming.targetKind,
                localId = localTombstone.targetId,
                incomingId = incoming.targetId,
                message = "Tombstones for the same target disagree about record kind.",
            )
            actions += conflictAction(incoming.targetKind, localTombstone.targetId, incoming.targetId)
            return
        }

        when {
            incoming.revision > localTombstone.revision -> {
                tombstonesById[key] = incoming
                actions += PersonalizationMergeAction(
                    decision = PersonalizationMergeDecision.AddIncomingTombstone,
                    recordKind = incoming.targetKind,
                    localId = localTombstone.targetId,
                    incomingId = incoming.targetId,
                    message = "Incoming tombstone has the newer revision.",
                )
            }

            incoming.revision < localTombstone.revision -> {
                actions += PersonalizationMergeAction(
                    decision = PersonalizationMergeDecision.KeepLocalTombstone,
                    recordKind = incoming.targetKind,
                    localId = localTombstone.targetId,
                    incomingId = incoming.targetId,
                    message = "Local tombstone has the newer revision.",
                )
            }

            incoming == localTombstone -> {
                actions += PersonalizationMergeAction(
                    decision = PersonalizationMergeDecision.KeepLocalTombstone,
                    recordKind = incoming.targetKind,
                    localId = localTombstone.targetId,
                    incomingId = incoming.targetId,
                    message = "Tombstones are identical.",
                )
            }

            else -> {
                conflicts += PersonalizationMergeConflict(
                    code = "same_revision_tombstone_conflict",
                    recordKind = incoming.targetKind,
                    localId = localTombstone.targetId,
                    incomingId = incoming.targetId,
                    message = "Tombstones have the same revision but different content.",
                )
                actions += conflictAction(incoming.targetKind, localTombstone.targetId, incoming.targetId)
            }
        }
    }

    private fun mergeLiveRecord(
        incoming: RecordEnvelope,
        liveById: MutableMap<String, RecordEnvelope>,
        tombstonesById: MutableMap<String, PersonalizationTombstone>,
        actions: MutableList<PersonalizationMergeAction>,
        conflicts: MutableList<PersonalizationMergeConflict>,
    ) {
        val localTombstone = tombstonesById[incoming.idKey]
        if (localTombstone != null) {
            if (localTombstone.targetKind != incoming.kind) {
                conflicts += PersonalizationMergeConflict(
                    code = "live_tombstone_kind_mismatch",
                    recordKind = incoming.kind,
                    localId = localTombstone.targetId,
                    incomingId = incoming.id,
                    logicalKey = incoming.logicalKey,
                    message = "Incoming live record kind does not match the local tombstone.",
                )
                actions += conflictAction(incoming.kind, localTombstone.targetId, incoming.id)
                return
            }
            if (localTombstone.revision >= incoming.revision) {
                actions += PersonalizationMergeAction(
                    decision = PersonalizationMergeDecision.KeepLocalTombstone,
                    recordKind = incoming.kind,
                    localId = localTombstone.targetId,
                    incomingId = incoming.id,
                    logicalKey = incoming.logicalKey,
                    message = "Local tombstone suppresses the incoming live record.",
                )
                return
            }

            tombstonesById.remove(incoming.idKey)
            liveById[incoming.idKey] = incoming
            actions += PersonalizationMergeAction(
                decision = PersonalizationMergeDecision.ResurrectWithIncoming,
                recordKind = incoming.kind,
                localId = localTombstone.targetId,
                incomingId = incoming.id,
                logicalKey = incoming.logicalKey,
                message = "Incoming live record has a newer revision than the local tombstone.",
            )
            return
        }

        val local = liveById[incoming.idKey]
        if (local != null) {
            if (local.kind != incoming.kind) {
                conflicts += PersonalizationMergeConflict(
                    code = "record_kind_conflict",
                    recordKind = incoming.kind,
                    localId = local.id,
                    incomingId = incoming.id,
                    logicalKey = incoming.logicalKey,
                    message = "Records with the same ID have different kinds.",
                )
                actions += conflictAction(incoming.kind, local.id, incoming.id)
                return
            }

            when {
                incoming.revision > local.revision -> {
                    liveById[incoming.idKey] = incoming
                    actions += PersonalizationMergeAction(
                        decision = PersonalizationMergeDecision.ReplaceWithIncoming,
                        recordKind = incoming.kind,
                        localId = local.id,
                        incomingId = incoming.id,
                        logicalKey = incoming.logicalKey,
                        message = "Incoming record has the newer revision.",
                    )
                }

                incoming.revision < local.revision -> {
                    actions += PersonalizationMergeAction(
                        decision = PersonalizationMergeDecision.KeepLocal,
                        recordKind = incoming.kind,
                        localId = local.id,
                        incomingId = incoming.id,
                        logicalKey = local.logicalKey,
                        message = "Local record has the newer revision.",
                    )
                }

                incoming.record == local.record -> {
                    actions += PersonalizationMergeAction(
                        decision = PersonalizationMergeDecision.KeepLocal,
                        recordKind = incoming.kind,
                        localId = local.id,
                        incomingId = incoming.id,
                        logicalKey = local.logicalKey,
                        message = "Records are identical.",
                    )
                }

                else -> {
                    conflicts += PersonalizationMergeConflict(
                        code = "same_revision_record_conflict",
                        recordKind = incoming.kind,
                        localId = local.id,
                        incomingId = incoming.id,
                        logicalKey = incoming.logicalKey,
                        message = "Records have the same ID and revision but different content.",
                    )
                    actions += conflictAction(incoming.kind, local.id, incoming.id)
                }
            }
            return
        }

        val logicalCollision = liveById.values.firstOrNull {
            it.kind == incoming.kind && it.logicalKey == incoming.logicalKey
        }
        if (logicalCollision != null) {
            conflicts += PersonalizationMergeConflict(
                code = "duplicate_logical_record",
                recordKind = incoming.kind,
                localId = logicalCollision.id,
                incomingId = incoming.id,
                logicalKey = incoming.logicalKey,
                message = "Different record IDs describe the same logical personalization item.",
            )
            actions += conflictAction(
                incoming.kind,
                logicalCollision.id,
                incoming.id,
                incoming.logicalKey,
            )
            return
        }

        liveById[incoming.idKey] = incoming
        actions += PersonalizationMergeAction(
            decision = PersonalizationMergeDecision.AddIncoming,
            recordKind = incoming.kind,
            incomingId = incoming.id,
            logicalKey = incoming.logicalKey,
            message = "Incoming record was added.",
        )
    }

    private fun conflictAction(
        kind: PersonalizationRecordKind,
        localId: String?,
        incomingId: String?,
        logicalKey: String? = null,
    ): PersonalizationMergeAction {
        return PersonalizationMergeAction(
            decision = PersonalizationMergeDecision.Conflict,
            recordKind = kind,
            localId = localId,
            incomingId = incomingId,
            logicalKey = logicalKey,
            message = "User or tool conflict resolution is required.",
        )
    }

    private data class RecordEnvelope(
        val kind: PersonalizationRecordKind,
        val record: PersonalizationRecord,
        val id: String,
        val idKey: String,
        val revision: Long,
        val logicalKey: String,
    )

    private fun PersonalizationDataSet.liveRecords(): List<RecordEnvelope> = buildList {
        manualWords.forEach { add(envelope(it)) }
        learnedWords.forEach { add(envelope(it)) }
        learnedNgrams.forEach { add(envelope(it)) }
        wordRules.forEach { add(envelope(it)) }
        correctionRules.forEach { add(envelope(it)) }
    }

    private fun envelope(record: PersonalizationRecord): RecordEnvelope {
        val kind = when (record) {
            is ManualWordRecord -> PersonalizationRecordKind.ManualWord
            is LearnedWordRecord -> PersonalizationRecordKind.LearnedWord
            is LearnedNgramRecord -> PersonalizationRecordKind.LearnedNgram
            is WordRuleRecord -> PersonalizationRecordKind.WordRule
            is CorrectionRuleRecord -> PersonalizationRecordKind.CorrectionRule
            else -> error("Unknown personalization record type: ${record::class.java.name}")
        }
        return RecordEnvelope(
            kind = kind,
            record = record,
            id = record.id,
            idKey = idKey(record.id),
            revision = record.revision,
            logicalKey = logicalKey(record),
        )
    }

    private fun logicalKey(record: PersonalizationRecord): String = when (record) {
        is ManualWordRecord -> listOf(
            normalizeLocale(record.locale),
            normalizeText(record.word),
            record.shortcut?.let(::normalizeText).orEmpty(),
        ).joinToString("\u001f")

        is LearnedWordRecord -> listOf(
            normalizeLocale(record.locale),
            normalizeText(record.word),
        ).joinToString("\u001f")

        is LearnedNgramRecord -> normalizeLocale(record.locale) + "\u001f" +
            record.terms.joinToString("\u001e") { normalizeText(it) }

        is WordRuleRecord -> listOf(
            normalizeLocale(record.locale),
            normalizeText(record.word),
        ).joinToString("\u001f")

        is CorrectionRuleRecord -> listOf(
            normalizeLocale(record.locale),
            record.appScope?.lowercase(Locale.ROOT).orEmpty(),
            normalizeText(record.typed),
            normalizeText(record.replacement),
        ).joinToString("\u001f")

        else -> error("Unknown personalization record type: ${record::class.java.name}")
    }

    private fun buildDataSet(
        formatVersion: String,
        records: Collection<RecordEnvelope>,
        tombstones: Collection<PersonalizationTombstone>,
    ): PersonalizationDataSet {
        return PersonalizationDataSet(
            formatVersion = formatVersion,
            manualWords = records.mapNotNull { it.record as? ManualWordRecord }.sortedBy { idKey(it.id) },
            learnedWords = records.mapNotNull { it.record as? LearnedWordRecord }.sortedBy { idKey(it.id) },
            learnedNgrams = records.mapNotNull { it.record as? LearnedNgramRecord }.sortedBy { idKey(it.id) },
            wordRules = records.mapNotNull { it.record as? WordRuleRecord }.sortedBy { idKey(it.id) },
            correctionRules = records.mapNotNull { it.record as? CorrectionRuleRecord }.sortedBy { idKey(it.id) },
            tombstones = tombstones.sortedBy { idKey(it.targetId) },
        )
    }

    private fun idKey(value: String): String = value.lowercase(Locale.ROOT)

    private fun normalizeLocale(value: String?): String = value?.lowercase(Locale.ROOT).orEmpty()

    private fun normalizeText(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }
}
