package org.futo.inputmethod.latin.personalization

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.test.InstrumentationRegistry
import androidx.test.filters.LargeTest
import androidx.test.runner.AndroidJUnit4
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Produces repeatable diagnostic measurements without enforcing device-specific latency limits.
 *
 * The JSON report is published into /data/local/tmp while the debug target is still installed and
 * pulled by the GitHub Actions device workflow. Correctness and the 64 MiB source-store payload
 * boundary remain hard assertions; timing and heap values are evidence for later reviewed
 * acceptance thresholds.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class PersonalizationLoadMeasurementTest {
    @Test
    fun measureLegacyMappingEncodingAndRuntimeCompilation() {
        val context: Context = InstrumentationRegistry.getTargetContext()
        val measurements = TEST_SIZES.map { size -> measure(context, size) }
        val report = buildReport(measurements)
        PersonalizationMeasurementReportPublisher.publish(context, REPORT_FILE_NAME, report)

        assertEquals(TEST_SIZES.toList(), measurements.map { it.wordCount })
        assertTrue(measurements.all { it.rejectedWordCount == 0 })
        assertTrue(measurements.all { it.rejectedNgramCount == 0 })
        assertTrue(measurements.all { it.validationErrors == 0 })
        assertTrue(measurements.all { it.encodedBytes <= MAX_STORE_DATA_BYTES })
    }

    private fun measure(context: Context, wordCount: Int): Measurement {
        forceGc()
        val heapBefore = usedHeapBytes()
        val inventoryStarted = SystemClock.elapsedRealtimeNanos()
        val inventory = createInventory(wordCount)
        val inventoryMicros = nanosToMicros(SystemClock.elapsedRealtimeNanos() - inventoryStarted)
        val heapAfterInventory = usedHeapBytes()

        val mappingStarted = SystemClock.elapsedRealtimeNanos()
        val migration = LegacyUserHistoryMigration.convert(
            inventory = inventory,
            migratedAt = MIGRATION_TIME_MILLIS,
        )
        val mappingMicros = nanosToMicros(SystemClock.elapsedRealtimeNanos() - mappingStarted)
        val heapAfterMapping = usedHeapBytes()

        val validationStarted = SystemClock.elapsedRealtimeNanos()
        val validation = PersonalizationDataValidator.validateData(migration.data)
        val validationMicros = nanosToMicros(SystemClock.elapsedRealtimeNanos() - validationStarted)

        val encodingStarted = SystemClock.elapsedRealtimeNanos()
        val encoded = PersonalizationDataCodec.encodeData(migration.data)
        val encodingMicros = nanosToMicros(SystemClock.elapsedRealtimeNanos() - encodingStarted)
        val encodedBytes = encoded.toByteArray(Charsets.UTF_8)
        val heapAfterEncoding = usedHeapBytes()

        val compilationStarted = SystemClock.elapsedRealtimeNanos()
        val runtime = PersonalizationRuntimeSnapshotCompiler.compile(
            PersonalizationStoreSnapshot(
                generation = PersonalizationStoreGeneration(
                    storeVersion = PERSONALIZATION_FORMAT_VERSION,
                    generation = 1,
                    generationId = "load-measurement-$wordCount",
                    committedAt = MIGRATION_TIME_MILLIS,
                    reason = PersonalizationStoreCommitReason.Migration,
                    dataSha256 = sha256(encodedBytes),
                    dataSizeBytes = encodedBytes.size.toLong(),
                ),
                data = migration.data,
                generationDirectory = context.cacheDir,
            ),
        )
        val compilationMicros = nanosToMicros(SystemClock.elapsedRealtimeNanos() - compilationStarted)
        val heapAfterCompilation = usedHeapBytes()

        assertEquals(wordCount, runtime.learnedWords.size)
        assertEquals(expectedNgramCount(wordCount), runtime.learnedNgrams.size)

        return Measurement(
            wordCount = wordCount,
            ngramCount = runtime.learnedNgrams.size,
            issueCount = migration.issues.sumOf { it.count },
            rejectedWordCount = migration.rejectedWordCount,
            rejectedNgramCount = migration.rejectedNgramCount,
            validationErrors = validation.errors.size,
            inventoryMicros = inventoryMicros,
            mappingMicros = mappingMicros,
            validationMicros = validationMicros,
            encodingMicros = encodingMicros,
            compilationMicros = compilationMicros,
            encodedBytes = encodedBytes.size.toLong(),
            heapBeforeBytes = heapBefore,
            heapAfterInventoryBytes = heapAfterInventory,
            heapAfterMappingBytes = heapAfterMapping,
            heapAfterEncodingBytes = heapAfterEncoding,
            heapAfterCompilationBytes = heapAfterCompilation,
            peakObservedHeapBytes = maxOf(
                heapBefore,
                heapAfterInventory,
                heapAfterMapping,
                heapAfterEncoding,
                heapAfterCompilation,
            ),
        )
    }

    private fun createInventory(wordCount: Int): LegacyUserHistoryInventory {
        val words = ArrayList<LegacyUserHistoryWordInventoryItem>(wordCount)
        repeat(wordCount) { index ->
            val word = "loadword$index"
            val ngrams = if (index % NGRAM_INTERVAL == 0) {
                listOf(
                    LegacyUserHistoryNgramInventoryItem(
                        stableId = LegacyPersonalizationIds.learnedNgram(
                            LOCALE,
                            listOf("context${index % CONTEXT_VARIANTS}"),
                            word,
                        ),
                        contextTerms = listOf("context${index % CONTEXT_VARIANTS}"),
                        targetWord = word,
                        evidence = evidence(index, count = 2),
                    ),
                )
            } else {
                emptyList()
            }
            words += LegacyUserHistoryWordInventoryItem(
                stableId = LegacyPersonalizationIds.learnedWord(LOCALE, word),
                word = word,
                evidence = evidence(index, count = 3),
                isNotAWord = false,
                isPossiblyOffensive = false,
                ngrams = ngrams,
            )
        }
        return LegacyUserHistoryInventory(
            locale = LOCALE,
            words = words,
            truncated = false,
            complete = true,
        )
    }

    private fun evidence(index: Int, count: Int): LegacyProbabilityEvidence {
        return LegacyProbabilityEvidence(
            probability = 96 + index % 128,
            hasHistoricalInfo = true,
            timestampRaw = BASE_TIMESTAMP_SECONDS + index % TIMESTAMP_SPAN_SECONDS,
            levelRaw = 0,
            countRaw = count,
        )
    }

    private fun buildReport(measurements: List<Measurement>): String {
        return buildString {
            append("{\n")
            append("  \"formatVersion\": \"0.1\",\n")
            append("  \"mappingVersion\": \"")
            append(LEGACY_USER_HISTORY_MAPPING_VERSION)
            append("\",\n")
            append("  \"apiLevel\": ")
            append(Build.VERSION.SDK_INT)
            append(",\n")
            append("  \"device\": \"")
            append(jsonEscape("${Build.MANUFACTURER} ${Build.MODEL}".trim()))
            append("\",\n")
            append("  \"runtimeMaxHeapBytes\": ")
            append(Runtime.getRuntime().maxMemory())
            append(",\n")
            append("  \"measurements\": [\n")
            measurements.forEachIndexed { index, measurement ->
                append(measurement.toJson("    "))
                if (index != measurements.lastIndex) append(',')
                append('\n')
            }
            append("  ]\n")
            append("}\n")
        }
    }

    private data class Measurement(
        val wordCount: Int,
        val ngramCount: Int,
        val issueCount: Int,
        val rejectedWordCount: Int,
        val rejectedNgramCount: Int,
        val validationErrors: Int,
        val inventoryMicros: Long,
        val mappingMicros: Long,
        val validationMicros: Long,
        val encodingMicros: Long,
        val compilationMicros: Long,
        val encodedBytes: Long,
        val heapBeforeBytes: Long,
        val heapAfterInventoryBytes: Long,
        val heapAfterMappingBytes: Long,
        val heapAfterEncodingBytes: Long,
        val heapAfterCompilationBytes: Long,
        val peakObservedHeapBytes: Long,
    ) {
        fun toJson(indent: String): String {
            return buildString {
                append(indent).append("{\n")
                appendField(indent, "wordCount", wordCount.toLong())
                appendField(indent, "ngramCount", ngramCount.toLong())
                appendField(indent, "issueCount", issueCount.toLong())
                appendField(indent, "rejectedWordCount", rejectedWordCount.toLong())
                appendField(indent, "rejectedNgramCount", rejectedNgramCount.toLong())
                appendField(indent, "validationErrors", validationErrors.toLong())
                appendField(indent, "inventoryMicros", inventoryMicros)
                appendField(indent, "mappingMicros", mappingMicros)
                appendField(indent, "validationMicros", validationMicros)
                appendField(indent, "encodingMicros", encodingMicros)
                appendField(indent, "compilationMicros", compilationMicros)
                appendField(indent, "encodedBytes", encodedBytes)
                appendField(indent, "heapBeforeBytes", heapBeforeBytes)
                appendField(indent, "heapAfterInventoryBytes", heapAfterInventoryBytes)
                appendField(indent, "heapAfterMappingBytes", heapAfterMappingBytes)
                appendField(indent, "heapAfterEncodingBytes", heapAfterEncodingBytes)
                appendField(indent, "heapAfterCompilationBytes", heapAfterCompilationBytes)
                append(indent).append("  \"peakObservedHeapBytes\": ")
                    .append(peakObservedHeapBytes).append('\n')
                append(indent).append('}')
            }
        }

        private fun StringBuilder.appendField(indent: String, name: String, value: Long) {
            append(indent).append("  \"").append(name).append("\": ")
                .append(value).append(",\n")
        }
    }

    private fun expectedNgramCount(wordCount: Int): Int {
        return if (wordCount == 0) 0 else ((wordCount - 1) / NGRAM_INTERVAL) + 1
    }

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun forceGc() {
        System.gc()
        System.runFinalization()
        Thread.sleep(50)
    }

    private fun nanosToMicros(nanos: Long): Long = nanos / 1_000L

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun jsonEscape(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    companion object {
        const val REPORT_FILE_NAME = "personalization-load-report.json"
        private const val LOCALE = "de-DE"
        private const val NGRAM_INTERVAL = 5
        private const val CONTEXT_VARIANTS = 100
        private const val BASE_TIMESTAMP_SECONDS = 1_700_000_000
        private const val TIMESTAMP_SPAN_SECONDS = 86_400
        private const val MIGRATION_TIME_MILLIS = 1_800_000_000_000L
        private const val MAX_STORE_DATA_BYTES = 64L * 1024L * 1024L
        private val TEST_SIZES = intArrayOf(1_000, 10_000, 25_000)
    }
}
