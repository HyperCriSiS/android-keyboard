package org.futo.inputmethod.latin.personalization

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val PERSONALIZATION_SHADOW_REPORT_FORMAT_VERSION = "0.1"

@Serializable
data class PersonalizationShadowReport(
    val formatVersion: String,
    val generatedAt: Long,
    val window: PersonalizationShadowReportWindow,
    val currentRuntime: PersonalizationShadowReportRuntime?,
    val runtimeGenerations: List<PersonalizationShadowReportRuntimeCount>,
    val counters: PersonalizationShadowReportCounters,
    val candidateStats: PersonalizationShadowReportCandidateStats,
    val latency: PersonalizationShadowReportLatency,
    val findings: List<PersonalizationShadowReportFinding>,
    val locales: List<PersonalizationShadowReportCount>,
    val topCandidateSourceTypes: List<PersonalizationShadowReportCount>,
    val inputStyles: List<PersonalizationShadowReportCount>,
    val privacy: PersonalizationShadowReportPrivacy = PersonalizationShadowReportPrivacy(),
)

@Serializable
data class PersonalizationShadowReportWindow(
    val startedAt: Long,
    val firstObservedAt: Long?,
    val lastObservedAt: Long?,
)

@Serializable
data class PersonalizationShadowReportRuntime(
    val generationId: String,
    val dataSha256: String,
)

@Serializable
data class PersonalizationShadowReportRuntimeCount(
    val generationId: String,
    val dataSha256: String,
    val eventCount: Long,
)

@Serializable
data class PersonalizationShadowReportCounters(
    val evaluatedEvents: Long,
    val droppedQueueItems: Long,
    val skippedWithoutRuntime: Long,
    val retainedRecentEvents: Int,
    val queueDropRate: Double,
)

@Serializable
data class PersonalizationShadowReportCandidateStats(
    val totalCandidates: Long,
    val averageCandidates: Double,
    val eventsWithManualPrefixMatches: Long,
    val manualPrefixMatches: Long,
    val visibleManualPrefixMatches: Long,
    val manualPrefixVisibilityRate: Double,
    val eventsWithLearnedTypedWord: Long,
    val learnedTypedWordVisibleFromHistory: Long,
    val learnedHistoryVisibilityRate: Double,
)

@Serializable
data class PersonalizationShadowReportLatency(
    val samples: Long,
    val totalMicros: Long,
    val averageMicros: Double,
    val maximumMicros: Long,
    val buckets: List<PersonalizationShadowReportLatencyBucket>,
)

@Serializable
data class PersonalizationShadowReportLatencyBucket(
    val upperBoundMicros: Long?,
    val count: Long,
)

@Serializable
data class PersonalizationShadowReportFinding(
    val finding: String,
    val count: Long,
    val eventRate: Double,
)

@Serializable
data class PersonalizationShadowReportCount(
    val key: String,
    val count: Long,
)

@Serializable
data class PersonalizationShadowReportPrivacy(
    val containsTypedWords: Boolean = false,
    val containsCandidateWords: Boolean = false,
    val containsFingerprints: Boolean = false,
    val containsSentenceContext: Boolean = false,
    val containsApplicationScopes: Boolean = false,
    val containsPersistentUserIdentifiers: Boolean = false,
)

object PersonalizationShadowReportCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = true
        encodeDefaults = true
    }

    fun encode(report: PersonalizationShadowReport): String {
        PersonalizationShadowReportValidator.requireValid(report)
        return json.encodeToString(report)
    }

    fun decode(source: String): PersonalizationShadowReport {
        val report = json.decodeFromString<PersonalizationShadowReport>(source)
        PersonalizationShadowReportValidator.requireValid(report)
        return report
    }
}

object PersonalizationShadowReportValidator {
    fun validate(report: PersonalizationShadowReport): List<String> = buildList {
        if (report.formatVersion != PERSONALIZATION_SHADOW_REPORT_FORMAT_VERSION) {
            add("Unsupported shadow report format version: ${report.formatVersion}")
        }
        if (report.generatedAt < 0L || report.window.startedAt < 0L) {
            add("Report timestamps must not be negative.")
        }
        if (report.generatedAt < report.window.startedAt) {
            add("generatedAt must not precede the report window.")
        }
        if (report.window.firstObservedAt != null && report.window.lastObservedAt != null &&
            report.window.firstObservedAt > report.window.lastObservedAt
        ) {
            add("firstObservedAt must not follow lastObservedAt.")
        }
        if (report.counters.evaluatedEvents < 0L || report.counters.droppedQueueItems < 0L ||
            report.counters.skippedWithoutRuntime < 0L || report.counters.retainedRecentEvents < 0
        ) {
            add("Report counters must not be negative.")
        }
        if (!report.counters.queueDropRate.isValidRate()) {
            add("queueDropRate must be finite and between zero and one.")
        }
        if (!report.candidateStats.averageCandidates.isFinite() ||
            report.candidateStats.averageCandidates < 0.0
        ) {
            add("averageCandidates must be finite and non-negative.")
        }
        if (!report.candidateStats.manualPrefixVisibilityRate.isValidRate()) {
            add("manualPrefixVisibilityRate must be finite and between zero and one.")
        }
        if (!report.candidateStats.learnedHistoryVisibilityRate.isValidRate()) {
            add("learnedHistoryVisibilityRate must be finite and between zero and one.")
        }
        if (report.latency.samples < 0L || report.latency.totalMicros < 0L ||
            report.latency.maximumMicros < 0L || !report.latency.averageMicros.isFinite() ||
            report.latency.averageMicros < 0.0
        ) {
            add("Latency values must be finite and non-negative.")
        }
        if (report.latency.buckets.sumOf { it.count } != report.latency.samples) {
            add("Latency bucket counts must equal the sample count.")
        }
        if (report.findings.any { it.count < 0L || !it.eventRate.isValidRate() }) {
            add("Finding counts and rates are invalid.")
        }
        if (report.locales.any { it.key.isBlank() || it.count < 0L } ||
            report.topCandidateSourceTypes.any { it.key.isBlank() || it.count < 0L } ||
            report.inputStyles.any { it.key.isBlank() || it.count < 0L }
        ) {
            add("Aggregate keys must be non-blank and counts must not be negative.")
        }
        if (
            report.privacy.containsTypedWords ||
            report.privacy.containsCandidateWords ||
            report.privacy.containsFingerprints ||
            report.privacy.containsSentenceContext ||
            report.privacy.containsApplicationScopes ||
            report.privacy.containsPersistentUserIdentifiers
        ) {
            add("Format 0.1 aggregate shadow reports must not contain identifying text or context.")
        }
    }

    fun requireValid(report: PersonalizationShadowReport) {
        val errors = validate(report)
        require(errors.isEmpty()) { errors.joinToString(separator = " ") }
    }
}

class PersonalizationShadowReportAccumulator(
    startedAt: Long = System.currentTimeMillis(),
) {
    private val lock = Any()
    private var windowStartedAt = startedAt
    private var firstObservedAt: Long? = null
    private var lastObservedAt: Long? = null
    private var evaluatedEvents = 0L
    private var totalCandidates = 0L
    private var eventsWithManualPrefixMatches = 0L
    private var manualPrefixMatches = 0L
    private var visibleManualPrefixMatches = 0L
    private var eventsWithLearnedTypedWord = 0L
    private var learnedTypedWordVisibleFromHistory = 0L
    private var latencySamples = 0L
    private var latencyTotalMicros = 0L
    private var latencyMaximumMicros = 0L
    private val latencyBuckets = LongArray(LATENCY_BUCKET_UPPER_BOUNDS_MICROS.size + 1)
    private val findings = linkedMapOf<PersonalizationShadowFinding, Long>()
    private val locales = linkedMapOf<String, Long>()
    private val topCandidateSourceTypes = linkedMapOf<String, Long>()
    private val inputStyles = linkedMapOf<Int, Long>()
    private val runtimeGenerations = linkedMapOf<RuntimeKey, Long>()

    fun record(event: PersonalizationShadowEvent) = synchronized(lock) {
        firstObservedAt = firstObservedAt ?: event.observedAt
        lastObservedAt = maxOf(lastObservedAt ?: event.observedAt, event.observedAt)
        evaluatedEvents++
        totalCandidates += event.candidateCount.toLong()
        if (event.manualPrefixCandidateCount > 0) eventsWithManualPrefixMatches++
        manualPrefixMatches += event.manualPrefixCandidateCount.toLong()
        visibleManualPrefixMatches += event.manualPrefixCandidatesVisible.toLong()
        if (event.learnedTypedWordPresent) eventsWithLearnedTypedWord++
        if (event.learnedTypedWordPresent && event.productionHistoryTypedWordVisible) {
            learnedTypedWordVisibleFromHistory++
        }
        latencySamples++
        latencyTotalMicros += event.evaluationMicros
        latencyMaximumMicros = maxOf(latencyMaximumMicros, event.evaluationMicros)
        latencyBuckets[latencyBucketIndex(event.evaluationMicros)]++
        event.findings.forEach { findings[it] = (findings[it] ?: 0L) + 1L }
        locales[event.locale] = (locales[event.locale] ?: 0L) + 1L
        val sourceType = event.productionTopSourceType ?: "none"
        topCandidateSourceTypes[sourceType] = (topCandidateSourceTypes[sourceType] ?: 0L) + 1L
        inputStyles[event.inputStyle] = (inputStyles[event.inputStyle] ?: 0L) + 1L
        val runtimeKey = RuntimeKey(event.runtimeGenerationId, event.runtimeDataSha256)
        runtimeGenerations[runtimeKey] = (runtimeGenerations[runtimeKey] ?: 0L) + 1L
    }

    fun reset(startedAt: Long = System.currentTimeMillis()) = synchronized(lock) {
        windowStartedAt = startedAt
        firstObservedAt = null
        lastObservedAt = null
        evaluatedEvents = 0L
        totalCandidates = 0L
        eventsWithManualPrefixMatches = 0L
        manualPrefixMatches = 0L
        visibleManualPrefixMatches = 0L
        eventsWithLearnedTypedWord = 0L
        learnedTypedWordVisibleFromHistory = 0L
        latencySamples = 0L
        latencyTotalMicros = 0L
        latencyMaximumMicros = 0L
        latencyBuckets.fill(0L)
        findings.clear()
        locales.clear()
        topCandidateSourceTypes.clear()
        inputStyles.clear()
        runtimeGenerations.clear()
    }

    fun build(
        generatedAt: Long,
        currentRuntime: PersonalizationShadowReportRuntime?,
        droppedQueueItems: Long,
        skippedWithoutRuntime: Long,
        retainedRecentEvents: Int,
    ): PersonalizationShadowReport = synchronized(lock) {
        val attemptedQueueItems = evaluatedEvents + droppedQueueItems
        val report = PersonalizationShadowReport(
            formatVersion = PERSONALIZATION_SHADOW_REPORT_FORMAT_VERSION,
            generatedAt = generatedAt,
            window = PersonalizationShadowReportWindow(
                startedAt = windowStartedAt,
                firstObservedAt = firstObservedAt,
                lastObservedAt = lastObservedAt,
            ),
            currentRuntime = currentRuntime,
            runtimeGenerations = runtimeGenerations
                .map { (key, count) ->
                    PersonalizationShadowReportRuntimeCount(
                        generationId = key.generationId,
                        dataSha256 = key.dataSha256,
                        eventCount = count,
                    )
                }
                .sortedWith(
                    compareByDescending<PersonalizationShadowReportRuntimeCount> { it.eventCount }
                        .thenBy { it.generationId },
                ),
            counters = PersonalizationShadowReportCounters(
                evaluatedEvents = evaluatedEvents,
                droppedQueueItems = droppedQueueItems,
                skippedWithoutRuntime = skippedWithoutRuntime,
                retainedRecentEvents = retainedRecentEvents,
                queueDropRate = rate(droppedQueueItems, attemptedQueueItems),
            ),
            candidateStats = PersonalizationShadowReportCandidateStats(
                totalCandidates = totalCandidates,
                averageCandidates = average(totalCandidates, evaluatedEvents),
                eventsWithManualPrefixMatches = eventsWithManualPrefixMatches,
                manualPrefixMatches = manualPrefixMatches,
                visibleManualPrefixMatches = visibleManualPrefixMatches,
                manualPrefixVisibilityRate = rate(
                    visibleManualPrefixMatches,
                    manualPrefixMatches,
                ),
                eventsWithLearnedTypedWord = eventsWithLearnedTypedWord,
                learnedTypedWordVisibleFromHistory = learnedTypedWordVisibleFromHistory,
                learnedHistoryVisibilityRate = rate(
                    learnedTypedWordVisibleFromHistory,
                    eventsWithLearnedTypedWord,
                ),
            ),
            latency = PersonalizationShadowReportLatency(
                samples = latencySamples,
                totalMicros = latencyTotalMicros,
                averageMicros = average(latencyTotalMicros, latencySamples),
                maximumMicros = latencyMaximumMicros,
                buckets = latencyBuckets.mapIndexed { index, count ->
                    PersonalizationShadowReportLatencyBucket(
                        upperBoundMicros = LATENCY_BUCKET_UPPER_BOUNDS_MICROS.getOrNull(index),
                        count = count,
                    )
                },
            ),
            findings = PersonalizationShadowFinding.entries.map { finding ->
                val count = findings[finding] ?: 0L
                PersonalizationShadowReportFinding(
                    finding = finding.name,
                    count = count,
                    eventRate = rate(count, evaluatedEvents),
                )
            },
            locales = locales.toReportCounts(),
            topCandidateSourceTypes = topCandidateSourceTypes.toReportCounts(),
            inputStyles = inputStyles
                .map { (style, count) -> PersonalizationShadowReportCount(style.toString(), count) }
                .sortedWith(
                    compareByDescending<PersonalizationShadowReportCount> { it.count }
                        .thenBy { it.key },
                ),
        )
        PersonalizationShadowReportValidator.requireValid(report)
        report
    }

    private data class RuntimeKey(
        val generationId: String,
        val dataSha256: String,
    )
}

private val LATENCY_BUCKET_UPPER_BOUNDS_MICROS = longArrayOf(
    50L,
    100L,
    250L,
    500L,
    1_000L,
    2_500L,
    5_000L,
)

private fun latencyBucketIndex(value: Long): Int {
    val index = LATENCY_BUCKET_UPPER_BOUNDS_MICROS.indexOfFirst { value <= it }
    return if (index >= 0) index else LATENCY_BUCKET_UPPER_BOUNDS_MICROS.size
}

private fun average(numerator: Long, denominator: Long): Double =
    if (denominator == 0L) 0.0 else numerator.toDouble() / denominator.toDouble()

private fun rate(numerator: Long, denominator: Long): Double =
    if (denominator == 0L) 0.0 else numerator.toDouble() / denominator.toDouble()

private fun Double.isValidRate(): Boolean = isFinite() && this >= 0.0 && this <= 1.0

private fun Map<String, Long>.toReportCounts(): List<PersonalizationShadowReportCount> =
    map { (key, count) -> PersonalizationShadowReportCount(key, count) }
        .sortedWith(
            compareByDescending<PersonalizationShadowReportCount> { it.count }
                .thenBy { it.key },
        )
