package org.futo.inputmethod.latin.personalization

import android.content.Context
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import org.futo.inputmethod.latin.ExpandableBinaryDictionarySnapshotReader

class LegacyUserHistoryInventoryService(
    context: Context,
    private val mappingExecutor: ExecutorService = sharedMappingExecutor,
) {
    private val applicationContext = context.applicationContext

    data class Result(
        val status: ExpandableBinaryDictionarySnapshotReader.Status,
        val inventory: LegacyUserHistoryInventory?,
        val message: String?,
        val traversedEntries: Int,
    ) {
        val isUsable: Boolean
            get() = inventory != null && (
                status == ExpandableBinaryDictionarySnapshotReader.Status.COMPLETE ||
                    status == ExpandableBinaryDictionarySnapshotReader.Status.TRUNCATED
                )
    }

    fun readAsync(
        locale: Locale,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
        cancellation: AtomicBoolean? = null,
        callback: (Result) -> Unit,
    ) {
        require(maxEntries in 1..MAX_ALLOWED_ENTRIES) {
            "maxEntries must be between 1 and $MAX_ALLOWED_ENTRIES."
        }
        val dictionary = PersonalizationHelper.getUserHistoryDictionary(
            applicationContext,
            locale,
            null,
        )

        ExpandableBinaryDictionarySnapshotReader.readAsync(
            dictionary,
            maxEntries,
            cancellation,
        ) { snapshot ->
            // Return the KEYBOARD executor immediately. Mapping and sorting can be expensive for a
            // large personal history and must not delay later dictionary writes.
            mappingExecutor.execute {
                val usable = snapshot.status ==
                    ExpandableBinaryDictionarySnapshotReader.Status.COMPLETE ||
                    snapshot.status == ExpandableBinaryDictionarySnapshotReader.Status.TRUNCATED
                val inventory = if (usable) {
                    LegacyUserHistoryInventoryMapper.map(
                        locale = locale.toLanguageTag(),
                        wordProperties = snapshot.wordProperties,
                        truncated = snapshot.status ==
                            ExpandableBinaryDictionarySnapshotReader.Status.TRUNCATED,
                        complete = snapshot.status ==
                            ExpandableBinaryDictionarySnapshotReader.Status.COMPLETE,
                    )
                } else {
                    null
                }
                callback(
                    Result(
                        status = snapshot.status,
                        inventory = inventory,
                        message = snapshot.message,
                        traversedEntries = snapshot.traversedEntries,
                    ),
                )
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 100_000
        const val MAX_ALLOWED_ENTRIES = 1_000_000

        private val sharedMappingExecutor: ExecutorService =
            Executors.newSingleThreadExecutor(
                ThreadFactory { runnable ->
                    Thread(runnable, "PersonalizationInventory").apply {
                        priority = Thread.NORM_PRIORITY - 1
                    }
                },
            )
    }
}
