package org.futo.inputmethod.latin.personalization

import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

data class LegacyPersonalDictionaryInventory(
    val words: List<LegacyManualWordInventoryItem>,
    val truncated: Boolean,
)

data class LegacyManualWordInventoryItem(
    val stableId: String,
    val locale: String?,
    val word: String,
    val shortcut: String?,
    val frequency: Int,
    val appId: Int,
)

data class LegacyUserHistoryInventory(
    val locale: String,
    val words: List<LegacyUserHistoryWordInventoryItem>,
    val truncated: Boolean,
    val complete: Boolean,
    val rawSemanticsWarning: String = RAW_HISTORY_SEMANTICS_WARNING,
)

data class LegacyUserHistoryWordInventoryItem(
    val stableId: String,
    val word: String,
    val evidence: LegacyProbabilityEvidence,
    val isNotAWord: Boolean,
    val isPossiblyOffensive: Boolean,
    val ngrams: List<LegacyUserHistoryNgramInventoryItem>,
)

data class LegacyUserHistoryNgramInventoryItem(
    val stableId: String,
    val contextTerms: List<String>,
    val targetWord: String,
    val evidence: LegacyProbabilityEvidence,
)

data class LegacyProbabilityEvidence(
    val probability: Int,
    val hasHistoricalInfo: Boolean,
    val timestampRaw: Int?,
    val levelRaw: Int?,
    val countRaw: Int?,
)

const val RAW_HISTORY_SEMANTICS_WARNING =
    "Legacy timestamp, level, and count values are native implementation details and must not " +
        "be presented as exact user-visible dates or observation counts."

object LegacyPersonalizationIds {
    fun manualWord(locale: String?, word: String, shortcut: String?): String {
        return deterministicId(
            "manual-word",
            normalizeLocale(locale),
            normalizeText(word),
            shortcut?.let(::normalizeText).orEmpty(),
        )
    }

    fun learnedWord(locale: String, word: String): String {
        return deterministicId("learned-word", normalizeLocale(locale), normalizeText(word))
    }

    fun learnedNgram(locale: String, contextTerms: List<String>, targetWord: String): String {
        return deterministicId(
            "learned-ngram",
            normalizeLocale(locale),
            contextTerms.joinToString("\u001e") { normalizeText(it) },
            normalizeText(targetWord),
        )
    }

    private fun deterministicId(vararg fields: String): String {
        val canonical = fields.joinToString("\u001f")
        return UUID.nameUUIDFromBytes(canonical.toByteArray(StandardCharsets.UTF_8)).toString()
    }

    private fun normalizeLocale(locale: String?): String {
        return locale?.lowercase(Locale.ROOT).orEmpty()
    }

    private fun normalizeText(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }
}
