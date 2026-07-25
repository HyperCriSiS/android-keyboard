package org.futo.inputmethod.latin.personalization

import android.content.Context
import java.text.Normalizer
import java.util.Locale


enum class LegacyManualStoreMigrationDecision {
    Add,
    AlreadyPresent,
    SuppressedByTombstone,
    Conflict,
}


data class LegacyManualStoreMigrationAction(
    val decision: LegacyManualStoreMigrationDecision,
    val stableId: String,
    val word: String,
    val locale: String?,
    val message: String,
)


data class LegacyManualStoreMigrationConflict(
    val code: String,
    val stableId: String,
    val word: String,
    val message: String,
)


data class LegacyManualStoreMigrationPlan(
    val sourceCount: Int,
    val sourceTruncated: Boolean,
    val migrationIssues: List<LegacyManualWordMigrationIssue>,
    val currentSnapshot: PersonalizationStoreSnapshot?,
    val previewData: PersonalizationDataSet?,
    val actions: List<LegacyManualStoreMigrationAction>,
    val conflicts: List<LegacyManualStoreMigrationConflict>,
) {
    val additions: Int
        get() = actions.count { it.decision == LegacyManualStoreMigrationDecision.Add }

    val alreadyPresent: Int
        get() = actions.count { it.decision == LegacyManualStoreMigrationDecision.AlreadyPresent }

    val suppressedByTombstone: Int
        get() = actions.count { it.decision == LegacyManualStoreMigrationDecision.SuppressedByTombstone }

    val canApply: Boolean
        get() = !sourceTruncated && conflicts.isEmpty() && previewData != null

    val isAlreadyUpToDate: Boolean
        get() = canApply && additions == 0 && currentSnapshot != null
}


object LegacyManualStoreMigrationPlanner {
    fun plan(
        inventory: LegacyPersonalDictionaryInventory,
        currentSnapshot: PersonalizationStoreSnapshot?,
        migratedAt: Long,
        originDeviceId: String? = null,
    ): LegacyManualStoreMigrationPlan {
        require(migratedAt >= 0L) { "migratedAt must not be negative." }

        val migration = LegacyManualWordMigration.convert(
            inventory = inventory,
            migratedAt = migratedAt,
            originDeviceId = originDeviceId,
        )
        val currentData = currentSnapshot?.data
            ?: TransactionalPersonalizationStore.emptyDataSet()
        val currentManualById = currentData.manualWords.associateBy {
            it.id.lowercase(Locale.ROOT)
        }
        val currentManualByLogicalKey = currentData.manualWords.associateBy(::logicalKey)
        val tombstonesById = currentData.tombstones.associateBy {
            it.targetId.lowercase(Locale.ROOT)
        }

        val additions = mutableListOf<ManualWordRecord>()
        val actions = mutableListOf<LegacyManualStoreMigrationAction>()
        val conflicts = mutableListOf<LegacyManualStoreMigrationConflict>()

        migration.records.forEach { incoming ->
            val idKey = incoming.id.lowercase(Locale.ROOT)
            val tombstone = tombstonesById[idKey]
            if (tombstone != null) {
                if (tombstone.targetKind != PersonalizationRecordKind.ManualWord) {
                    conflicts += LegacyManualStoreMigrationConflict(
                        code = "tombstone_kind_mismatch",
                        stableId = incoming.id,
                        word = incoming.word,
                        message = "A tombstone with the same ID targets a different record kind.",
                    )
                    actions += conflictAction(incoming, "Tombstone kind mismatch.")
                } else {
                    actions += LegacyManualStoreMigrationAction(
                        decision = LegacyManualStoreMigrationDecision.SuppressedByTombstone,
                        stableId = incoming.id,
                        word = incoming.word,
                        locale = incoming.locale,
                        message = "The word remains deleted because a local tombstone exists.",
                    )
                }
                return@forEach
            }

            val existingById = currentManualById[idKey]
            if (existingById != null) {
                if (sameManualContent(existingById, incoming)) {
                    actions += LegacyManualStoreMigrationAction(
                        decision = LegacyManualStoreMigrationDecision.AlreadyPresent,
                        stableId = incoming.id,
                        word = incoming.word,
                        locale = incoming.locale,
                        message = "An equivalent record with the same stable ID is already present.",
                    )
                } else {
                    conflicts += LegacyManualStoreMigrationConflict(
                        code = "stable_id_content_conflict",
                        stableId = incoming.id,
                        word = incoming.word,
                        message = "The existing record with this stable ID has different user-visible content.",
                    )
                    actions += conflictAction(incoming, "Stable ID content conflict.")
                }
                return@forEach
            }

            val logicalCollision = currentManualByLogicalKey[logicalKey(incoming)]
            if (logicalCollision != null) {
                conflicts += LegacyManualStoreMigrationConflict(
                    code = "logical_duplicate_with_different_id",
                    stableId = incoming.id,
                    word = incoming.word,
                    message = "A logically equivalent manual word exists under a different record ID.",
                )
                actions += conflictAction(incoming, "Logical duplicate with a different ID.")
                return@forEach
            }

            additions += incoming
            actions += LegacyManualStoreMigrationAction(
                decision = LegacyManualStoreMigrationDecision.Add,
                stableId = incoming.id,
                word = incoming.word,
                locale = incoming.locale,
                message = "The word will be added to the experimental source store.",
            )
        }

        val preview = currentData.copy(
            manualWords = currentData.manualWords + additions,
        )
        val validation = PersonalizationDataValidator.validateData(preview)
        validation.errors.forEach { issue ->
            conflicts += LegacyManualStoreMigrationConflict(
                code = "invalid_preview:${issue.code}",
                stableId = "preview",
                word = "",
                message = "Migration preview is invalid at ${issue.path}: ${issue.message}",
            )
        }

        return LegacyManualStoreMigrationPlan(
            sourceCount = inventory.words.size,
            sourceTruncated = inventory.truncated,
            migrationIssues = migration.issues,
            currentSnapshot = currentSnapshot,
            previewData = preview.takeIf { validation.isValid },
            actions = actions,
            conflicts = conflicts,
        )
    }

    private fun conflictAction(
        incoming: ManualWordRecord,
        message: String,
    ) = LegacyManualStoreMigrationAction(
        decision = LegacyManualStoreMigrationDecision.Conflict,
        stableId = incoming.id,
        word = incoming.word,
        locale = incoming.locale,
        message = message,
    )

    private fun sameManualContent(
        existing: ManualWordRecord,
        incoming: ManualWordRecord,
    ): Boolean {
        return canonicalLocale(existing.locale) == canonicalLocale(incoming.locale) &&
            normalize(existing.word) == normalize(incoming.word) &&
            normalizeNullable(existing.shortcut) == normalizeNullable(incoming.shortcut) &&
            existing.frequency == incoming.frequency
    }

    private fun logicalKey(record: ManualWordRecord): String {
        return listOf(
            canonicalLocale(record.locale).orEmpty(),
            normalize(record.word),
            normalizeNullable(record.shortcut).orEmpty(),
        ).joinToString("\u001f")
    }

    private fun normalize(value: String): String = Normalizer
        .normalize(value.trim(), Normalizer.Form.NFC)
        .lowercase(Locale.ROOT)

    private fun normalizeNullable(value: String?): String? = value?.let(::normalize)

    private fun canonicalLocale(value: String?): String? {
        if (value == null) return null
        return Locale.forLanguageTag(value.replace('_', '-'))
            .toLanguageTag()
            .lowercase(Locale.ROOT)
    }
}


sealed class LegacyManualStoreMigrationApplyResult {
    data class Applied(
        val storeSnapshot: PersonalizationStoreSnapshot,
        val runtimeSnapshot: PersonalizationRuntimeSnapshot,
        val initialized: Boolean,
    ) : LegacyManualStoreMigrationApplyResult()

    data class Unchanged(
        val storeSnapshot: PersonalizationStoreSnapshot,
        val runtimeSnapshot: PersonalizationRuntimeSnapshot,
    ) : LegacyManualStoreMigrationApplyResult()

    data class Rejected(val message: String) : LegacyManualStoreMigrationApplyResult()
    data class Conflict(val message: String) : LegacyManualStoreMigrationApplyResult()
    data class Failed(val message: String, val cause: Throwable? = null) : LegacyManualStoreMigrationApplyResult()
}


class LegacyManualStoreMigrationService(
    private val store: TransactionalPersonalizationStore,
    private val controller: PersonalizationSourceController = PersonalizationSourceController(store),
) {
    companion object {
        fun forContext(context: Context): LegacyManualStoreMigrationService {
            val store = TransactionalPersonalizationStore.forContext(context.applicationContext)
            return LegacyManualStoreMigrationService(store)
        }
    }

    fun apply(
        plan: LegacyManualStoreMigrationPlan,
        committedAt: Long,
    ): LegacyManualStoreMigrationApplyResult {
        if (!plan.canApply) {
            return LegacyManualStoreMigrationApplyResult.Rejected(
                when {
                    plan.sourceTruncated -> "Source inventory is truncated and cannot be migrated safely."
                    plan.conflicts.isNotEmpty() -> "Migration has unresolved conflicts."
                    else -> "Migration preview is unavailable."
                },
            )
        }
        val preview = plan.previewData
            ?: return LegacyManualStoreMigrationApplyResult.Rejected("Migration preview is unavailable.")

        val current = plan.currentSnapshot
        if (current == null) {
            return when (val opened = controller.open(
                initialData = preview,
                committedAt = committedAt,
                reason = PersonalizationStoreCommitReason.Migration,
            )) {
                is PersonalizationSourceOpenResult.Ready -> {
                    if (!opened.initialized && opened.storeSnapshot.data != preview) {
                        LegacyManualStoreMigrationApplyResult.Conflict(
                            "The store was initialized by another writer after the preview was built.",
                        )
                    } else {
                        LegacyManualStoreMigrationApplyResult.Applied(
                            storeSnapshot = opened.storeSnapshot,
                            runtimeSnapshot = opened.runtimeSnapshot,
                            initialized = opened.initialized,
                        )
                    }
                }
                is PersonalizationSourceOpenResult.Invalid -> {
                    LegacyManualStoreMigrationApplyResult.Rejected(
                        opened.validation.errors.joinToString { "${it.path}: ${it.message}" },
                    )
                }
                is PersonalizationSourceOpenResult.Failed -> {
                    LegacyManualStoreMigrationApplyResult.Failed(opened.message, opened.cause)
                }
            }
        }

        return when (val updated = controller.replaceData(
            expectedGenerationId = current.generation.generationId,
            data = preview,
            committedAt = committedAt,
            reason = PersonalizationStoreCommitReason.Migration,
            sourceGenerationId = null,
            journalMessage = "Migrated Android personal-dictionary words into the experimental source store.",
        )) {
            is PersonalizationSourceUpdateResult.Activated -> {
                LegacyManualStoreMigrationApplyResult.Applied(
                    storeSnapshot = updated.storeSnapshot,
                    runtimeSnapshot = updated.runtimeSnapshot,
                    initialized = false,
                )
            }
            is PersonalizationSourceUpdateResult.Unchanged -> {
                LegacyManualStoreMigrationApplyResult.Unchanged(
                    updated.storeSnapshot,
                    updated.runtimeSnapshot,
                )
            }
            is PersonalizationSourceUpdateResult.Conflict -> {
                LegacyManualStoreMigrationApplyResult.Conflict(
                    "Expected generation ${updated.expectedGenerationId}, but ${updated.actualGenerationId} is current.",
                )
            }
            is PersonalizationSourceUpdateResult.Invalid -> {
                LegacyManualStoreMigrationApplyResult.Rejected(
                    updated.validation.errors.joinToString { "${it.path}: ${it.message}" },
                )
            }
            is PersonalizationSourceUpdateResult.RuntimeActivationFailed -> {
                LegacyManualStoreMigrationApplyResult.Failed(
                    "Migration was persisted as ${updated.committedStoreSnapshot.generation.generationId}, " +
                        "but runtime activation failed: ${updated.message}",
                    updated.cause,
                )
            }
            is PersonalizationSourceUpdateResult.Failed -> {
                LegacyManualStoreMigrationApplyResult.Failed(updated.message, updated.cause)
            }
        }
    }

    fun readCurrent(): PersonalizationStoreReadResult = store.readCurrent()

    fun history(limit: Int = 100): List<PersonalizationStoreGeneration> = store.listHistory(limit)
}
