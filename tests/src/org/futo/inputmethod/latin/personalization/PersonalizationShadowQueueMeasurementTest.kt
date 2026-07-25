package org.futo.inputmethod.latin.personalization

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.test.InstrumentationRegistry
import androidx.test.filters.LargeTest
import androidx.test.runner.AndroidJUnit4
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.futo.inputmethod.latin.Dictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Characterizes the same single-worker and bounded-queue policy used by shadow mode.
 *
 * The forced-stall case is deterministic and verifies that submission never waits for queue space.
 * The sustained case executes the real evaluator and records device-specific throughput and drops
 * without inventing a universal latency threshold.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class PersonalizationShadowQueueMeasurementTest {
    @Test
    fun measureBoundedQueueDropsAndEvaluatorThroughput() {
        val context: Context = InstrumentationRegistry.getTargetContext()
        val runtime = createRuntime(context)
        val observation = createObservation()
        val forced = measureForcedQueueSaturation(runtime, observation)
        val sustained = measureSustainedEvaluatorBurst(runtime, observation)

        assertEquals(FORCED_SUBMISSIONS.toLong(), forced.executed + forced.dropped)
        assertEquals((FORCED_SUBMISSIONS - QUEUE_CAPACITY).toLong(), forced.dropped)
        assertEquals(QUEUE_CAPACITY.toLong(), forced.executed)
        assertEquals(SUSTAINED_SUBMISSIONS.toLong(), sustained.executed + sustained.dropped)
        assertTrue(sustained.maxEvaluationMicros >= sustained.averageEvaluationMicros)

        PersonalizationMeasurementReportPublisher.publish(
            context = context,
            fileName = REPORT_FILE_NAME,
            content = buildReport(forced, sustained),
        )
    }

    private fun measureForcedQueueSaturation(
        runtime: PersonalizationRuntimeSnapshot,
        observation: PersonalizationShadowObservation,
    ): QueueMeasurement {
        val executed = AtomicLong(0)
        val dropped = AtomicLong(0)
        val evaluationMicros = AtomicLong(0)
        val maxEvaluationMicros = AtomicLong(0)
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val executor = executor(dropped)

        executor.execute {
            blockerStarted.countDown()
            releaseBlocker.await(30, TimeUnit.SECONDS)
        }
        assertTrue(blockerStarted.await(10, TimeUnit.SECONDS))

        val submittedAt = SystemClock.elapsedRealtimeNanos()
        repeat(FORCED_SUBMISSIONS) {
            executor.execute(evaluationTask(
                runtime,
                observation,
                executed,
                evaluationMicros,
                maxEvaluationMicros,
            ))
        }
        val submissionMicros = nanosToMicros(SystemClock.elapsedRealtimeNanos() - submittedAt)

        releaseBlocker.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(60, TimeUnit.SECONDS))

        return QueueMeasurement(
            submissions = FORCED_SUBMISSIONS.toLong(),
            executed = executed.get(),
            dropped = dropped.get(),
            submissionMicros = submissionMicros,
            totalEvaluationMicros = evaluationMicros.get(),
            maxEvaluationMicros = maxEvaluationMicros.get(),
        )
    }

    private fun measureSustainedEvaluatorBurst(
        runtime: PersonalizationRuntimeSnapshot,
        observation: PersonalizationShadowObservation,
    ): QueueMeasurement {
        val executed = AtomicLong(0)
        val dropped = AtomicLong(0)
        val evaluationMicros = AtomicLong(0)
        val maxEvaluationMicros = AtomicLong(0)
        val executor = executor(dropped)

        val submittedAt = SystemClock.elapsedRealtimeNanos()
        repeat(SUSTAINED_SUBMISSIONS) {
            executor.execute(evaluationTask(
                runtime,
                observation,
                executed,
                evaluationMicros,
                maxEvaluationMicros,
            ))
        }
        val submissionMicros = nanosToMicros(SystemClock.elapsedRealtimeNanos() - submittedAt)

        executor.shutdown()
        assertTrue(executor.awaitTermination(60, TimeUnit.SECONDS))

        return QueueMeasurement(
            submissions = SUSTAINED_SUBMISSIONS.toLong(),
            executed = executed.get(),
            dropped = dropped.get(),
            submissionMicros = submissionMicros,
            totalEvaluationMicros = evaluationMicros.get(),
            maxEvaluationMicros = maxEvaluationMicros.get(),
        )
    }

    private fun evaluationTask(
        runtime: PersonalizationRuntimeSnapshot,
        observation: PersonalizationShadowObservation,
        executed: AtomicLong,
        totalEvaluationMicros: AtomicLong,
        maxEvaluationMicros: AtomicLong,
    ): Runnable {
        return Runnable {
            val event = PersonalizationShadowEvaluator.evaluate(
                runtime = runtime,
                observation = observation,
                fingerprinter = ::testFingerprint,
            )
            executed.incrementAndGet()
            totalEvaluationMicros.addAndGet(event.evaluationMicros)
            maxEvaluationMicros.accumulateAndGet(event.evaluationMicros) { current, update ->
                maxOf(current, update)
            }
        }
    }

    private fun executor(dropped: AtomicLong): ThreadPoolExecutor {
        return ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(QUEUE_CAPACITY),
            ThreadFactory { runnable ->
                Thread(runnable, "ShadowQueueMeasurement").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            },
        ) { _, _ -> dropped.incrementAndGet() }
    }

    private fun createRuntime(context: Context): PersonalizationRuntimeSnapshot {
        val manualWords = List(MANUAL_WORD_COUNT) { index ->
            ManualWordRecord(
                id = LegacyPersonalizationIds.manualWord(LOCALE, "manual$index", null),
                revision = 1,
                createdAt = 1_000,
                updatedAt = 1_000,
                locale = LOCALE,
                word = "manual$index",
                frequency = 100 + index % 156,
                source = ManualWordSource.Imported,
            )
        }
        val learnedWords = List(LEARNED_WORD_COUNT) { index ->
            LearnedWordRecord(
                id = LegacyPersonalizationIds.learnedWord(LOCALE, "learned$index"),
                revision = 1,
                createdAt = 1_000,
                updatedAt = 10_000,
                locale = LOCALE,
                word = "learned$index",
                observationCount = (index % 20 + 1).toLong(),
                firstSeenAt = 1_000,
                lastSeenAt = 10_000,
                confidence = (index % 100) / 100.0,
                state = LearnedRecordState.Active,
                source = LearnedRecordSource.MigratedUserHistory,
            )
        }
        val data = PersonalizationDataSet(
            formatVersion = PERSONALIZATION_FORMAT_VERSION,
            manualWords = manualWords,
            learnedWords = learnedWords,
        )
        val validation = PersonalizationDataValidator.validateData(data)
        assertTrue(validation.errors.joinToString { it.message }, validation.isValid)

        return PersonalizationRuntimeSnapshotCompiler.compile(
            PersonalizationStoreSnapshot(
                generation = PersonalizationStoreGeneration(
                    storeVersion = PERSONALIZATION_FORMAT_VERSION,
                    generation = 1,
                    generationId = "queue-measurement",
                    committedAt = 10_000,
                    reason = PersonalizationStoreCommitReason.Migration,
                    dataSha256 = "0".repeat(64),
                    dataSizeBytes = 0,
                ),
                data = data,
                generationDirectory = context.cacheDir,
            ),
        )
    }

    private fun createObservation(): PersonalizationShadowObservation {
        return PersonalizationShadowObservation(
            observedAt = 20_000,
            locale = LOCALE,
            typedWord = "learned999",
            candidates = List(CANDIDATE_COUNT) { index ->
                PersonalizationShadowCandidate(
                    word = if (index == 0) "learned999" else "candidate$index",
                    score = 1_000 - index,
                    sourceType = if (index == 0) {
                        Dictionary.TYPE_USER_HISTORY
                    } else {
                        Dictionary.TYPE_MAIN
                    },
                    rank = index,
                )
            },
            inputStyle = 1,
            sessionId = 0,
        )
    }

    private fun buildReport(
        forced: QueueMeasurement,
        sustained: QueueMeasurement,
    ): String {
        return buildString {
            append("{\n")
            append("  \"formatVersion\": \"0.1\",\n")
            append("  \"apiLevel\": ").append(Build.VERSION.SDK_INT).append(",\n")
            append("  \"testThreadPriority\": ").append(Process.getThreadPriority(Process.myTid()))
                .append(",\n")
            append("  \"queueCapacity\": ").append(QUEUE_CAPACITY).append(",\n")
            append("  \"forcedSaturation\": ").append(forced.toJson("  ")).append(",\n")
            append("  \"sustainedBurst\": ").append(sustained.toJson("  ")).append('\n')
            append("}\n")
        }
    }

    private data class QueueMeasurement(
        val submissions: Long,
        val executed: Long,
        val dropped: Long,
        val submissionMicros: Long,
        val totalEvaluationMicros: Long,
        val maxEvaluationMicros: Long,
    ) {
        val averageEvaluationMicros: Long
            get() = if (executed == 0L) 0L else totalEvaluationMicros / executed

        fun toJson(indent: String): String {
            val inner = "$indent  "
            return buildString {
                append("{\n")
                append(inner).append("\"submissions\": ").append(submissions).append(",\n")
                append(inner).append("\"executed\": ").append(executed).append(",\n")
                append(inner).append("\"dropped\": ").append(dropped).append(",\n")
                append(inner).append("\"dropRate\": ")
                    .append(if (submissions == 0L) 0.0 else dropped.toDouble() / submissions)
                    .append(",\n")
                append(inner).append("\"submissionMicros\": ").append(submissionMicros).append(",\n")
                append(inner).append("\"averageSubmissionNanos\": ")
                    .append(if (submissions == 0L) 0L else submissionMicros * 1_000L / submissions)
                    .append(",\n")
                append(inner).append("\"averageEvaluationMicros\": ")
                    .append(averageEvaluationMicros).append(",\n")
                append(inner).append("\"maxEvaluationMicros\": ")
                    .append(maxEvaluationMicros).append('\n')
                append(indent).append('}')
            }
        }
    }

    private fun testFingerprint(value: String): PersonalizationShadowFingerprint {
        return PersonalizationShadowFingerprint(
            hash = "len-${value.length}",
            codePointLength = value.codePointCount(0, value.length),
        )
    }

    private fun nanosToMicros(nanos: Long): Long = nanos / 1_000L

    companion object {
        const val REPORT_FILE_NAME = "personalization-shadow-queue-report.json"
        private const val LOCALE = "de-DE"
        private const val QUEUE_CAPACITY = 64
        private const val FORCED_SUBMISSIONS = 256
        private const val SUSTAINED_SUBMISSIONS = 5_000
        private const val MANUAL_WORD_COUNT = 500
        private const val LEARNED_WORD_COUNT = 2_000
        private const val CANDIDATE_COUNT = 16
    }
}
