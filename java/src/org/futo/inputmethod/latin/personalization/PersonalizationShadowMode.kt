package org.futo.inputmethod.latin.personalization

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.Dictionary
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo
import org.futo.inputmethod.latin.common.ComposedData
import org.futo.inputmethod.latin.utils.SuggestionResults

data class PersonalizationShadowCandidate(
    val word: String,
    val score: Int,
    val sourceType: String?,
    val rank: Int,
)

data class PersonalizationShadowObservation(
    val observedAt: Long,
    val locale: String,
    val typedWord: String,
    val candidates: List<PersonalizationShadowCandidate>,
    val inputStyle: Int,
    val sessionId: Int,
)

data class PersonalizationShadowFingerprint(
    val hash: String,
    val codePointLength: Int,
)

enum class PersonalizationShadowFinding {
    ManualPrefixCandidateMissing,
    PinnedTypedWordMissing,
    BlockedSuggestionVisible,
    BlockedAutocorrectCandidateRankedFirst,
    PreferredCorrectionMissing,
    LearnedTypedWordMissing,
}

data class PersonalizationShadowEvent(
    val observedAt: Long,
    val locale: String,
    val runtimeGenerationId: String,
    val runtimeDataSha256: String,
    val typedWord: PersonalizationShadowFingerprint,
    val productionTopCandidate: PersonalizationShadowFingerprint?,
    val productionTopSourceType: String?,
    val candidateCount: Int,
    val manualPrefixCandidateCount: Int,
    val manualPrefixCandidatesVisible: Int,
    val learnedTypedWordPresent: Boolean,
    val productionHistoryTypedWordVisible: Boolean,
    val wordRuleAction: WordRuleAction?,
    val blockedSuggestionCount: Int,
    val blockedAutocorrectCandidateCount: Int,
    val preferredCorrectionCount: Int,
    val findings: Set<PersonalizationShadowFinding>,
    val evaluationMicros: Long,
    val inputStyle: Int,
    val sessionId: Int,
)

data class PersonalizationShadowSnapshot(
    val enabled: Boolean,
    val runtimeGenerationId: String?,
    val runtimeDataSha256: String?,
    val lastRefreshAt: Long?,
    val lastError: String?,
    val observed: Long,
    val dropped: Long,
    val skippedWithoutRuntime: Long,
    val findings: Map<PersonalizationShadowFinding, Long>,
    val recentEvents: List<PersonalizationShadowEvent>,
)

object PersonalizationShadowEvaluator {
    fun evaluate(
        runtime: PersonalizationRuntimeSnapshot,
        observation: PersonalizationShadowObservation,
        fingerprinter: (String) -> PersonalizationShadowFingerprint,
    ): PersonalizationShadowEvent {
        val started = System.nanoTime()
        val locale = canonicalLocale(observation.locale)
        val typedKey = normalizeShadowText(observation.typedWord)
        val candidates = observation.candidates.take(MAX_SHADOW_CANDIDATES)
        val candidateKeySet = candidates.mapTo(linkedSetOf()) { normalizeShadowText(it.word) }

        val manualPrefixMatches = runtime.findManualWords(
            locale = locale,
            prefix = observation.typedWord,
            limit = MAX_MANUAL_PREFIX_MATCHES,
        )
        val visibleManualCount = manualPrefixMatches.count {
            normalizeShadowText(it.word) in candidateKeySet
        }
        val learnedTypedWord = runtime.learnedWord(locale, observation.typedWord)
        val historyTypedWordVisible = candidates.any {
            normalizeShadowText(it.word) == typedKey && it.sourceType == Dictionary.TYPE_USER_HISTORY
        }
        val wordRule = runtime.wordRule(locale, observation.typedWord)

        val correctionActions = candidates.mapNotNull { candidate ->
            runtime.correctionRule(
                locale = locale,
                typed = observation.typedWord,
                replacement = candidate.word,
                appScope = null,
            )?.action?.let { candidate to it }
        }
        val blockedSuggestions = correctionActions.filter {
            it.second == CorrectionRuleAction.BlockSuggestion
        }
        val blockedAutocorrectCandidates = correctionActions.filter {
            it.second == CorrectionRuleAction.BlockAutocorrect
        }
        val preferredRules = runtime.correctionRules
            .asSequence()
            .filter {
                it.appScope == null &&
                    normalizeShadowText(it.typed) == typedKey &&
                    it.action == CorrectionRuleAction.Prefer &&
                    (it.locale == null || canonicalLocale(it.locale) == locale)
            }
            .sortedByDescending { it.locale != null }
            .distinctBy { normalizeShadowText(it.replacement) }
            .toList()
        val missingPreferred = preferredRules.count {
            normalizeShadowText(it.replacement) !in candidateKeySet
        }

        val findings = linkedSetOf<PersonalizationShadowFinding>()
        if (manualPrefixMatches.size > visibleManualCount) {
            findings += PersonalizationShadowFinding.ManualPrefixCandidateMissing
        }
        if (wordRule?.action == WordRuleAction.Pin && typedKey !in candidateKeySet) {
            findings += PersonalizationShadowFinding.PinnedTypedWordMissing
        }
        if (blockedSuggestions.isNotEmpty()) {
            findings += PersonalizationShadowFinding.BlockedSuggestionVisible
        }
        if (blockedAutocorrectCandidates.any { it.first.rank == 0 }) {
            findings += PersonalizationShadowFinding.BlockedAutocorrectCandidateRankedFirst
        }
        if (missingPreferred > 0) {
            findings += PersonalizationShadowFinding.PreferredCorrectionMissing
        }
        if (learnedTypedWord != null && !historyTypedWordVisible) {
            findings += PersonalizationShadowFinding.LearnedTypedWordMissing
        }

        val topCandidate = candidates.minByOrNull { it.rank }
        return PersonalizationShadowEvent(
            observedAt = observation.observedAt,
            locale = locale,
            runtimeGenerationId = runtime.generationId,
            runtimeDataSha256 = runtime.dataSha256,
            typedWord = fingerprinter(observation.typedWord),
            productionTopCandidate = topCandidate?.let { fingerprinter(it.word) },
            productionTopSourceType = topCandidate?.sourceType,
            candidateCount = candidates.size,
            manualPrefixCandidateCount = manualPrefixMatches.size,
            manualPrefixCandidatesVisible = visibleManualCount,
            learnedTypedWordPresent = learnedTypedWord != null,
            productionHistoryTypedWordVisible = historyTypedWordVisible,
            wordRuleAction = wordRule?.action,
            blockedSuggestionCount = blockedSuggestions.size,
            blockedAutocorrectCandidateCount = blockedAutocorrectCandidates.size,
            preferredCorrectionCount = preferredRules.size,
            findings = findings.toSet(),
            evaluationMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started),
            inputStyle = observation.inputStyle,
            sessionId = observation.sessionId,
        )
    }
}

/**
 * Debug-only, local-only comparison layer for the experimental personalization runtime.
 *
 * The suggestion hot path only copies a bounded candidate list and enqueues it. Store I/O,
 * runtime compilation, and comparison execute on one bounded background worker. Events contain
 * salted per-process fingerprints instead of words or surrounding text and are never persisted.
 */
object PersonalizationShadowMode {
    private const val MAX_EVENTS = 256
    private const val MAX_QUEUE = 64

    private val applicationContext = AtomicReference<Context?>(null)
    private val runtime = AtomicReference<PersonalizationRuntimeSnapshot?>(null)
    private val events = ArrayDeque<PersonalizationShadowEvent>(MAX_EVENTS)
    private val reportAccumulator = PersonalizationShadowReportAccumulator()
    private val salt = ByteArray(32).also(SecureRandom()::nextBytes)
    private val observed = AtomicLong(0)
    private val dropped = AtomicLong(0)
    private val skippedWithoutRuntime = AtomicLong(0)
    private val findingCounts = PersonalizationShadowFinding.entries.associateWith { AtomicLong(0) }
    private val worker = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_QUEUE),
        ShadowThreadFactory,
    ) { _, _ -> dropped.incrementAndGet() }

    @Volatile
    private var enabled = false

    @Volatile
    private var lastRefreshAt: Long? = null

    @Volatile
    private var lastError: String? = null

    @JvmStatic
    fun configure(context: Context) {
        if (!BuildConfig.DEBUG) return
        applicationContext.compareAndSet(null, context.applicationContext)
    }

    @JvmStatic
    fun setEnabled(value: Boolean) {
        if (!BuildConfig.DEBUG) return
        enabled = value
        if (value) refreshAsync()
    }

    @JvmStatic
    fun isEnabled(): Boolean = BuildConfig.DEBUG && enabled

    @JvmStatic
    fun refreshAsync() {
        if (!BuildConfig.DEBUG) return
        val context = applicationContext.get()
        if (context == null) {
            lastError = "Shadow mode has not been configured with an application context."
            return
        }
        worker.execute {
            when (val read = TransactionalPersonalizationStore.forContext(context).readCurrent()) {
                PersonalizationStoreReadResult.Empty -> {
                    runtime.set(null)
                    lastError = "Experimental personalization store is not initialized."
                }
                is PersonalizationStoreReadResult.Failed -> {
                    runtime.set(null)
                    lastError = read.message
                }
                is PersonalizationStoreReadResult.Ready -> {
                    try {
                        runtime.set(PersonalizationRuntimeSnapshotCompiler.compile(read.snapshot))
                        lastError = null
                    } catch (exception: Exception) {
                        runtime.set(null)
                        lastError = exception.message ?: exception.javaClass.simpleName
                    }
                }
            }
            lastRefreshAt = System.currentTimeMillis()
        }
    }

    @JvmStatic
    fun observe(
        locale: Locale?,
        composedData: ComposedData,
        results: SuggestionResults,
        inputStyle: Int,
        sessionId: Int,
    ) {
        if (!BuildConfig.DEBUG || !enabled || composedData.mIsBatchMode) return
        val typedWord = composedData.mTypedWord
        if (typedWord.isBlank()) return
        val activeRuntime = runtime.get()
        if (activeRuntime == null) {
            skippedWithoutRuntime.incrementAndGet()
            return
        }

        val candidates = results.toArray()
            .asSequence()
            .filterIsInstance<SuggestedWordInfo>()
            .take(MAX_SHADOW_CANDIDATES)
            .mapIndexed { index, info ->
                PersonalizationShadowCandidate(
                    word = info.mWord,
                    score = info.mScore,
                    sourceType = info.mSourceDict?.mDictType,
                    rank = index,
                )
            }
            .toList()
        val observation = PersonalizationShadowObservation(
            observedAt = System.currentTimeMillis(),
            locale = locale?.toLanguageTag().orEmpty(),
            typedWord = typedWord,
            candidates = candidates,
            inputStyle = inputStyle,
            sessionId = sessionId,
        )

        worker.execute {
            try {
                val event = PersonalizationShadowEvaluator.evaluate(
                    runtime = activeRuntime,
                    observation = observation,
                    fingerprinter = ::fingerprint,
                )
                reportAccumulator.record(event)
                synchronized(events) {
                    if (events.size >= MAX_EVENTS) events.removeFirst()
                    events.addLast(event)
                }
                observed.incrementAndGet()
                event.findings.forEach { findingCounts.getValue(it).incrementAndGet() }
            } catch (exception: Exception) {
                lastError = "Shadow evaluation failed: ${exception.message ?: exception.javaClass.simpleName}"
            }
        }
    }

    @JvmStatic
    fun clearEvents() {
        synchronized(events) { events.clear() }
        reportAccumulator.reset()
        observed.set(0)
        dropped.set(0)
        skippedWithoutRuntime.set(0)
        findingCounts.values.forEach { it.set(0) }
    }

    fun snapshot(): PersonalizationShadowSnapshot {
        val activeRuntime = runtime.get()
        return PersonalizationShadowSnapshot(
            enabled = isEnabled(),
            runtimeGenerationId = activeRuntime?.generationId,
            runtimeDataSha256 = activeRuntime?.dataSha256,
            lastRefreshAt = lastRefreshAt,
            lastError = lastError,
            observed = observed.get(),
            dropped = dropped.get(),
            skippedWithoutRuntime = skippedWithoutRuntime.get(),
            findings = findingCounts.mapValues { it.value.get() },
            recentEvents = synchronized(events) { events.toList().asReversed() },
        )
    }

    fun aggregateReport(
        generatedAt: Long = System.currentTimeMillis(),
    ): PersonalizationShadowReport {
        val activeRuntime = runtime.get()
        val retainedEvents = synchronized(events) { events.size }
        return reportAccumulator.build(
            generatedAt = generatedAt,
            currentRuntime = activeRuntime?.let {
                PersonalizationShadowReportRuntime(
                    generationId = it.generationId,
                    dataSha256 = it.dataSha256,
                )
            },
            droppedQueueItems = dropped.get(),
            skippedWithoutRuntime = skippedWithoutRuntime.get(),
            retainedRecentEvents = retainedEvents,
        )
    }

    fun aggregateReportJson(
        generatedAt: Long = System.currentTimeMillis(),
    ): String = PersonalizationShadowReportCodec.encode(aggregateReport(generatedAt))

    private fun fingerprint(value: String): PersonalizationShadowFingerprint {
        val normalized = normalizeShadowText(value)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(normalized.toByteArray(Charsets.UTF_8))
        return PersonalizationShadowFingerprint(
            hash = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }.take(16),
            codePointLength = normalized.codePointCount(0, normalized.length),
        )
    }

    private object ShadowThreadFactory : ThreadFactory {
        override fun newThread(runnable: Runnable): Thread = Thread(
            runnable,
            "personalization-shadow",
        ).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
}

private const val MAX_SHADOW_CANDIDATES = 16
private const val MAX_MANUAL_PREFIX_MATCHES = 16

private fun normalizeShadowText(value: String): String = Normalizer
    .normalize(value.trim(), Normalizer.Form.NFC)
    .lowercase(Locale.ROOT)

private fun canonicalLocale(value: String?): String {
    if (value.isNullOrBlank()) return "und"
    return Locale.forLanguageTag(value.replace('_', '-'))
        .toLanguageTag()
        .lowercase(Locale.ROOT)
}
