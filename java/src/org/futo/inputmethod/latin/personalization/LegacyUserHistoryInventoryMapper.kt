package org.futo.inputmethod.latin.personalization

import org.futo.inputmethod.latin.makedict.ProbabilityInfo
import org.futo.inputmethod.latin.makedict.WordProperty

object LegacyUserHistoryInventoryMapper {
    fun map(
        locale: String,
        wordProperties: List<WordProperty>,
        truncated: Boolean = false,
        complete: Boolean = !truncated,
    ): LegacyUserHistoryInventory {
        require(locale.isNotBlank()) { "locale must not be blank." }

        val words = wordProperties
            .asSequence()
            .filter { it.isValid && it.mWord.isNotBlank() && !it.mIsBeginningOfSentence }
            .map { property ->
                LegacyUserHistoryWordInventoryItem(
                    stableId = LegacyPersonalizationIds.learnedWord(locale, property.mWord),
                    word = property.mWord,
                    evidence = property.mProbabilityInfo.toLegacyEvidence(),
                    isNotAWord = property.mIsNotAWord,
                    isPossiblyOffensive = property.mIsPossiblyOffensive,
                    ngrams = property.mNgrams.orEmpty().map { ngram ->
                        val contextTerms = ngram.mNgramContext
                            .extractPrevWordsContextArray()
                            .toList()
                        LegacyUserHistoryNgramInventoryItem(
                            stableId = LegacyPersonalizationIds.learnedNgram(
                                locale,
                                contextTerms,
                                ngram.mTargetWord.mWord,
                            ),
                            contextTerms = contextTerms,
                            targetWord = ngram.mTargetWord.mWord,
                            evidence = ngram.mTargetWord.mProbabilityInfo.toLegacyEvidence(),
                        )
                    }.sortedWith(
                        compareBy<LegacyUserHistoryNgramInventoryItem> {
                            it.contextTerms.joinToString("\u001f")
                        }.thenBy { it.targetWord },
                    ),
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.word })
            .toList()

        return LegacyUserHistoryInventory(
            locale = locale,
            words = words,
            truncated = truncated,
            complete = complete,
        )
    }

    private fun ProbabilityInfo.toLegacyEvidence(): LegacyProbabilityEvidence {
        val historical = hasHistoricalInfo()
        return LegacyProbabilityEvidence(
            probability = mProbability,
            hasHistoricalInfo = historical,
            timestampRaw = mTimestamp.takeIf { historical },
            levelRaw = mLevel.takeIf { historical },
            countRaw = mCount.takeIf { historical },
        )
    }
}
