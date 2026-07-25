package org.futo.inputmethod.latin.personalization

import java.util.Collections

internal fun PersonalizationDataSet.deepImmutableCopy(): PersonalizationDataSet {
    return copy(
        manualWords = immutableList(manualWords),
        learnedWords = immutableList(learnedWords),
        learnedNgrams = immutableList(
            learnedNgrams.map { record ->
                record.copy(terms = immutableList(record.terms))
            },
        ),
        wordRules = immutableList(wordRules),
        correctionRules = immutableList(correctionRules),
        tombstones = immutableList(tombstones),
    )
}

internal fun PersonalizationStoreGeneration.deepImmutableCopy(): PersonalizationStoreGeneration {
    return copy(changes = immutableList(changes))
}

internal fun <T> immutableList(values: Collection<T>): List<T> {
    return Collections.unmodifiableList(ArrayList(values))
}

internal fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> {
    return Collections.unmodifiableMap(LinkedHashMap(values))
}
