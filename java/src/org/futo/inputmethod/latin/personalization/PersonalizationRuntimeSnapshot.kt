package org.futo.inputmethod.latin.personalization

import java.text.Normalizer
import java.util.Locale

data class PersonalizationRuntimeWordKey(
    val locale: String?,
    val normalizedWord: String,
)

data class PersonalizationRuntimeCorrectionKey(
    val locale: String?,
    val normalizedTyped: String,
    val normalizedReplacement: String,
    val appScope: String?,
)

/**
 * Immutable, pre-indexed view of one committed personalization generation.
 *
 * The runtime may retain this object while a newer editable generation is being written. Switching
 * generations is therefore a single reference replacement rather than a partially visible update.
 */
class PersonalizationRuntimeSnapshot internal constructor(
    val generationId: String,
    val generation: Long,
    val dataSha256: String,
    val manualWords: List<ManualWordRecord>,
    val learnedWords: List<LearnedWordRecord>,
    val learnedNgrams: List<LearnedNgramRecord>,
    val wordRules: List<WordRuleRecord>,
    val correctionRules: List<CorrectionRuleRecord>,
    val tombstones: List<PersonalizationTombstone>,
    private val manualWordsByLocale: Map<String?, List<ManualWordRecord>>,
    private val learnedWordsByKey: Map<PersonalizationRuntimeWordKey, LearnedWordRecord>,
    private val wordRulesByKey: Map<PersonalizationRuntimeWordKey, WordRuleRecord>,
    private val correctionRulesByKey: Map<PersonalizationRuntimeCorrectionKey, CorrectionRuleRecord>,
) {
    fun findManualWords(
        locale: String?,
        prefix: String = "",
        limit: Int = 32,
    ): List<ManualWordRecord> {
        require(limit > 0) { "limit must be positive." }
        val localeKey = canonicalLocale(locale)
        val normalizedPrefix = normalizeText(prefix)
        return buildList {
            manualWordsByLocale[localeKey]?.let(::addAll)
            if (localeKey != null) manualWordsByLocale[null]?.let(::addAll)
        }
            .asSequence()
            .filter { normalizedPrefix.isEmpty() || normalizeText(it.word).startsWith(normalizedPrefix) }
            .distinctBy { it.id.lowercase(Locale.ROOT) }
            .sortedWith(
                compareByDescending<ManualWordRecord> { it.frequency }
                    .thenBy { normalizeText(it.word) },
            )
            .take(limit)
            .toList()
    }

    fun learnedWord(locale: String, word: String): LearnedWordRecord? {
        return learnedWordsByKey[
            PersonalizationRuntimeWordKey(canonicalLocale(locale), normalizeText(word)),
        ]
    }

    fun wordRule(locale: String?, word: String): WordRuleRecord? {
        val normalizedWord = normalizeText(word)
        val localeKey = canonicalLocale(locale)
        return wordRulesByKey[PersonalizationRuntimeWordKey(localeKey, normalizedWord)]
            ?: if (localeKey != null) {
                wordRulesByKey[PersonalizationRuntimeWordKey(null, normalizedWord)]
            } else {
                null
            }
    }

    fun correctionRule(
        locale: String?,
        typed: String,
        replacement: String,
        appScope: String? = null,
    ): CorrectionRuleRecord? {
        val localeKey = canonicalLocale(locale)
        val typedKey = normalizeText(typed)
        val replacementKey = normalizeText(replacement)
        val appKey = canonicalAppScope(appScope)
        val candidates = listOfNotNull(
            PersonalizationRuntimeCorrectionKey(localeKey, typedKey, replacementKey, appKey),
            appKey?.let {
                PersonalizationRuntimeCorrectionKey(localeKey, typedKey, replacementKey, null)
            },
            localeKey?.let {
                PersonalizationRuntimeCorrectionKey(null, typedKey, replacementKey, appKey)
            },
            if (localeKey != null && appKey != null) {
                PersonalizationRuntimeCorrectionKey(null, typedKey, replacementKey, null)
            } else {
                null
            },
        )
        return candidates.firstNotNullOfOrNull { correctionRulesByKey[it] }
    }
}

object PersonalizationRuntimeSnapshotCompiler {
    fun compile(snapshot: PersonalizationStoreSnapshot): PersonalizationRuntimeSnapshot {
        val validation = PersonalizationDataValidator.validateData(snapshot.data)
        require(validation.isValid) {
            validation.errors.joinToString(separator = "; ") { "${it.path}: ${it.message}" }
        }
        require(snapshot.generation.dataSha256.matches(Regex("^[a-f0-9]{64}$"))) {
            "Store snapshot has an invalid data hash."
        }

        val manualWords = snapshot.data.manualWords.toList()
        val learnedWords = snapshot.data.learnedWords.toList()
        val learnedNgrams = snapshot.data.learnedNgrams.map { it.copy(terms = it.terms.toList()) }
        val wordRules = snapshot.data.wordRules.toList()
        val correctionRules = snapshot.data.correctionRules.toList()
        val tombstones = snapshot.data.tombstones.toList()

        val manualWordsByLocale = manualWords
            .groupBy { canonicalLocale(it.locale) }
            .mapValues { (_, records) -> records.toList() }
        val learnedWordsByKey = learnedWords.associateBy { record ->
            PersonalizationRuntimeWordKey(
                locale = canonicalLocale(record.locale),
                normalizedWord = normalizeText(record.word),
            )
        }
        val wordRulesByKey = wordRules.associateBy { record ->
            PersonalizationRuntimeWordKey(
                locale = canonicalLocale(record.locale),
                normalizedWord = normalizeText(record.word),
            )
        }
        val correctionRulesByKey = correctionRules.associateBy { record ->
            PersonalizationRuntimeCorrectionKey(
                locale = canonicalLocale(record.locale),
                normalizedTyped = normalizeText(record.typed),
                normalizedReplacement = normalizeText(record.replacement),
                appScope = canonicalAppScope(record.appScope),
            )
        }

        return PersonalizationRuntimeSnapshot(
            generationId = snapshot.generation.generationId,
            generation = snapshot.generation.generation,
            dataSha256 = snapshot.generation.dataSha256,
            manualWords = manualWords,
            learnedWords = learnedWords,
            learnedNgrams = learnedNgrams,
            wordRules = wordRules,
            correctionRules = correctionRules,
            tombstones = tombstones,
            manualWordsByLocale = manualWordsByLocale,
            learnedWordsByKey = learnedWordsByKey,
            wordRulesByKey = wordRulesByKey,
            correctionRulesByKey = correctionRulesByKey,
        )
    }
}

private fun normalizeText(value: String): String {
    return Normalizer.normalize(value.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)
}

private fun canonicalLocale(locale: String?): String? {
    if (locale == null) return null
    return Locale.forLanguageTag(locale.replace('_', '-'))
        .toLanguageTag()
        .lowercase(Locale.ROOT)
}

private fun canonicalAppScope(appScope: String?): String? {
    return appScope?.trim()?.lowercase(Locale.ROOT)
}
