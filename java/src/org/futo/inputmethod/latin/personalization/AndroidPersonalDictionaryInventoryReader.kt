package org.futo.inputmethod.latin.personalization

import android.content.Context
import android.provider.UserDictionary

class AndroidPersonalDictionaryInventoryReader(context: Context) {
    private val contentResolver = context.applicationContext.contentResolver

    fun read(maxEntries: Int = 100_000): LegacyPersonalDictionaryInventory {
        require(maxEntries > 0) { "maxEntries must be positive." }

        val projection = arrayOf(
            UserDictionary.Words.WORD,
            UserDictionary.Words.FREQUENCY,
            UserDictionary.Words.LOCALE,
            UserDictionary.Words.APP_ID,
            UserDictionary.Words.SHORTCUT,
        )
        val words = mutableListOf<LegacyManualWordInventoryItem>()
        var truncated = false

        contentResolver.query(
            UserDictionary.Words.CONTENT_URI,
            projection,
            null,
            null,
            null,
        )?.use { cursor ->
            val wordColumn = cursor.getColumnIndexOrThrow(UserDictionary.Words.WORD)
            val frequencyColumn = cursor.getColumnIndexOrThrow(UserDictionary.Words.FREQUENCY)
            val localeColumn = cursor.getColumnIndexOrThrow(UserDictionary.Words.LOCALE)
            val appIdColumn = cursor.getColumnIndexOrThrow(UserDictionary.Words.APP_ID)
            val shortcutColumn = cursor.getColumnIndexOrThrow(UserDictionary.Words.SHORTCUT)

            while (cursor.moveToNext()) {
                if (words.size >= maxEntries) {
                    truncated = true
                    break
                }
                val word = cursor.getString(wordColumn) ?: continue
                if (word.isBlank()) continue
                val locale = cursor.getString(localeColumn)?.takeIf { it.isNotBlank() }
                val shortcut = cursor.getString(shortcutColumn)?.takeIf { it.isNotBlank() }
                words += LegacyManualWordInventoryItem(
                    stableId = LegacyPersonalizationIds.manualWord(locale, word, shortcut),
                    locale = locale,
                    word = word,
                    shortcut = shortcut,
                    frequency = cursor.getInt(frequencyColumn),
                    appId = cursor.getInt(appIdColumn),
                )
            }
        }

        return LegacyPersonalDictionaryInventory(
            words = words.sortedWith(
                compareBy<LegacyManualWordInventoryItem> { it.locale.orEmpty() }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.word }
                    .thenBy { it.shortcut.orEmpty() },
            ),
            truncated = truncated,
        )
    }
}

data class LegacyManualWordMigrationIssue(
    val stableId: String,
    val code: String,
    val message: String,
)

data class LegacyManualWordMigrationResult(
    val records: List<ManualWordRecord>,
    val issues: List<LegacyManualWordMigrationIssue>,
)

object LegacyManualWordMigration {
    fun convert(
        inventory: LegacyPersonalDictionaryInventory,
        migratedAt: Long,
        originDeviceId: String? = null,
    ): LegacyManualWordMigrationResult {
        require(migratedAt >= 0L) { "migratedAt must not be negative." }

        val issues = mutableListOf<LegacyManualWordMigrationIssue>()
        val records = inventory.words.map { item ->
            val normalizedFrequency = item.frequency.coerceIn(0, 255)
            if (normalizedFrequency != item.frequency) {
                issues += LegacyManualWordMigrationIssue(
                    stableId = item.stableId,
                    code = "frequency_clamped",
                    message = "Legacy frequency ${item.frequency} was clamped to $normalizedFrequency.",
                )
            }
            if (item.appId != 0) {
                issues += LegacyManualWordMigrationIssue(
                    stableId = item.stableId,
                    code = "legacy_app_id_not_exported",
                    message = "Legacy app ID ${item.appId} is intentionally omitted from portable data.",
                )
            }
            ManualWordRecord(
                id = item.stableId,
                revision = 1,
                createdAt = migratedAt,
                updatedAt = migratedAt,
                originDeviceId = originDeviceId,
                locale = item.locale,
                word = item.word,
                shortcut = item.shortcut,
                frequency = normalizedFrequency,
                source = ManualWordSource.MigratedAndroidUserDictionary,
            )
        }

        return LegacyManualWordMigrationResult(records, issues)
    }
}
