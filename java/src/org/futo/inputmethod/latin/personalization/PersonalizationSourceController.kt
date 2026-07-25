package org.futo.inputmethod.latin.personalization

import java.util.concurrent.atomic.AtomicReference

sealed class PersonalizationSourceOpenResult {
    data class Ready(
        val storeSnapshot: PersonalizationStoreSnapshot,
        val runtimeSnapshot: PersonalizationRuntimeSnapshot,
        val initialized: Boolean,
    ) : PersonalizationSourceOpenResult()

    data class Invalid(val validation: PersonalizationValidationResult) : PersonalizationSourceOpenResult()
    data class Failed(val message: String, val cause: Throwable? = null) : PersonalizationSourceOpenResult()
}

sealed class PersonalizationSourceUpdateResult {
    data class Activated(
        val storeSnapshot: PersonalizationStoreSnapshot,
        val runtimeSnapshot: PersonalizationRuntimeSnapshot,
    ) : PersonalizationSourceUpdateResult()

    data class Unchanged(
        val storeSnapshot: PersonalizationStoreSnapshot,
        val runtimeSnapshot: PersonalizationRuntimeSnapshot,
    ) : PersonalizationSourceUpdateResult()

    data class Conflict(
        val expectedGenerationId: String,
        val actualGenerationId: String,
        val activeRuntimeGenerationId: String?,
    ) : PersonalizationSourceUpdateResult()

    data class Invalid(val validation: PersonalizationValidationResult) : PersonalizationSourceUpdateResult()

    data class RuntimeActivationFailed(
        val committedStoreSnapshot: PersonalizationStoreSnapshot,
        val activeRuntimeGenerationId: String?,
        val message: String,
        val cause: Throwable? = null,
    ) : PersonalizationSourceUpdateResult()

    data class Failed(val message: String, val cause: Throwable? = null) : PersonalizationSourceUpdateResult()
}

/**
 * Coordinates the editable source store with the immutable runtime view.
 *
 * Persisted data is authoritative. The active runtime reference changes only after a committed
 * store generation has been read back, validated, and compiled successfully. A stale edit, failed
 * write, invalid import, or compiler exception never partially changes the active runtime view.
 *
 * Methods perform blocking store I/O and must be invoked from an I/O dispatcher outside tests.
 */
class PersonalizationSourceController(
    private val store: TransactionalPersonalizationStore,
    private val compiler: (PersonalizationStoreSnapshot) -> PersonalizationRuntimeSnapshot =
        PersonalizationRuntimeSnapshotCompiler::compile,
) {
    private val activeRuntime = AtomicReference<PersonalizationRuntimeSnapshot?>(null)

    fun activeRuntimeSnapshot(): PersonalizationRuntimeSnapshot? = activeRuntime.get()

    @Synchronized
    fun open(
        initialData: PersonalizationDataSet = TransactionalPersonalizationStore.emptyDataSet(),
        committedAt: Long,
        reason: PersonalizationStoreCommitReason = PersonalizationStoreCommitReason.Initialization,
    ): PersonalizationSourceOpenResult {
        val initialization = store.initialize(initialData, committedAt, reason)
        val snapshot = when (initialization) {
            is PersonalizationStoreInitializeResult.Initialized -> initialization.snapshot
            is PersonalizationStoreInitializeResult.AlreadyInitialized -> initialization.snapshot
            is PersonalizationStoreInitializeResult.Invalid -> {
                return PersonalizationSourceOpenResult.Invalid(initialization.validation)
            }
            is PersonalizationStoreInitializeResult.Failed -> {
                return PersonalizationSourceOpenResult.Failed(
                    initialization.message,
                    initialization.cause,
                )
            }
        }

        return try {
            val runtime = compiler(snapshot)
            activeRuntime.set(runtime)
            PersonalizationSourceOpenResult.Ready(
                storeSnapshot = snapshot,
                runtimeSnapshot = runtime,
                initialized = initialization is PersonalizationStoreInitializeResult.Initialized,
            )
        } catch (exception: Exception) {
            PersonalizationSourceOpenResult.Failed(
                message = "Personalization source is persisted but could not be compiled for runtime: " +
                    (exception.message ?: exception.javaClass.simpleName),
                cause = exception,
            )
        }
    }

    @Synchronized
    fun reload(): PersonalizationSourceOpenResult {
        return when (val read = store.readCurrent()) {
            PersonalizationStoreReadResult.Empty -> PersonalizationSourceOpenResult.Failed(
                "Personalization store has not been initialized.",
            )
            is PersonalizationStoreReadResult.Failed -> PersonalizationSourceOpenResult.Failed(
                read.message,
                read.cause,
            )
            is PersonalizationStoreReadResult.Ready -> {
                try {
                    val runtime = compiler(read.snapshot)
                    activeRuntime.set(runtime)
                    PersonalizationSourceOpenResult.Ready(
                        storeSnapshot = read.snapshot,
                        runtimeSnapshot = runtime,
                        initialized = false,
                    )
                } catch (exception: Exception) {
                    PersonalizationSourceOpenResult.Failed(
                        exception.message ?: exception.javaClass.simpleName,
                        exception,
                    )
                }
            }
        }
    }

    @Synchronized
    fun commitEdit(
        expectedGenerationId: String,
        edit: PersonalizationEditResult,
        committedAt: Long,
    ): PersonalizationSourceUpdateResult {
        return activateStoreResult(
            store.commitEdit(expectedGenerationId, edit, committedAt),
        )
    }

    @Synchronized
    fun replaceData(
        expectedGenerationId: String,
        data: PersonalizationDataSet,
        committedAt: Long,
        reason: PersonalizationStoreCommitReason,
        sourceGenerationId: String? = null,
        journalMessage: String,
    ): PersonalizationSourceUpdateResult {
        return activateStoreResult(
            store.replaceData(
                expectedGenerationId = expectedGenerationId,
                data = data,
                committedAt = committedAt,
                reason = reason,
                sourceGenerationId = sourceGenerationId,
                journalMessage = journalMessage,
            ),
        )
    }

    @Synchronized
    fun rollback(
        expectedCurrentGenerationId: String,
        targetGenerationId: String,
        committedAt: Long,
    ): PersonalizationSourceUpdateResult {
        return activateStoreResult(
            store.rollback(
                expectedCurrentGenerationId = expectedCurrentGenerationId,
                targetGenerationId = targetGenerationId,
                committedAt = committedAt,
            ),
        )
    }

    private fun activateStoreResult(
        result: PersonalizationStoreCommitResult,
    ): PersonalizationSourceUpdateResult {
        return when (result) {
            is PersonalizationStoreCommitResult.Conflict -> PersonalizationSourceUpdateResult.Conflict(
                expectedGenerationId = result.expectedGenerationId,
                actualGenerationId = result.actualGenerationId,
                activeRuntimeGenerationId = activeRuntime.get()?.generationId,
            )
            is PersonalizationStoreCommitResult.Invalid -> {
                PersonalizationSourceUpdateResult.Invalid(result.validation)
            }
            is PersonalizationStoreCommitResult.Failed -> PersonalizationSourceUpdateResult.Failed(
                result.message,
                result.cause,
            )
            is PersonalizationStoreCommitResult.Unchanged -> {
                val active = activeRuntime.get()
                if (active != null && active.generationId == result.snapshot.generation.generationId) {
                    PersonalizationSourceUpdateResult.Unchanged(result.snapshot, active)
                } else {
                    compileAndActivate(result.snapshot, unchanged = true)
                }
            }
            is PersonalizationStoreCommitResult.Committed -> {
                compileAndActivate(result.snapshot, unchanged = false)
            }
        }
    }

    private fun compileAndActivate(
        snapshot: PersonalizationStoreSnapshot,
        unchanged: Boolean,
    ): PersonalizationSourceUpdateResult {
        val previous = activeRuntime.get()
        return try {
            val runtime = compiler(snapshot)
            activeRuntime.set(runtime)
            if (unchanged) {
                PersonalizationSourceUpdateResult.Unchanged(snapshot, runtime)
            } else {
                PersonalizationSourceUpdateResult.Activated(snapshot, runtime)
            }
        } catch (exception: Exception) {
            PersonalizationSourceUpdateResult.RuntimeActivationFailed(
                committedStoreSnapshot = snapshot,
                activeRuntimeGenerationId = previous?.generationId,
                message = exception.message ?: exception.javaClass.simpleName,
                cause = exception,
            )
        }
    }
}
