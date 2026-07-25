package org.futo.inputmethod.latin.personalization

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File

/**
 * Publishes a measurement report while instrumentation is still running.
 *
 * connectedAndroidTest may uninstall the target APK before the next GitHub Actions step. Android
 * also prevents the app identity used by run-as from writing into shell-owned staging paths on all
 * supported API levels. The report is therefore retained in private cache for local inspection and
 * emitted as bounded Base64 chunks into logcat. The device workflow reconstructs and validates the
 * JSON after the test process exits.
 */
object PersonalizationMeasurementReportPublisher {
    private const val LOG_TAG = "PersonalizationReport"
    private const val LOG_PREFIX = "PERSONALIZATION_REPORT"
    private const val LOG_CHUNK_SIZE = 1_800

    fun publish(
        context: Context,
        fileName: String,
        content: String,
    ) {
        require(fileName.matches(Regex("^[a-z0-9][a-z0-9._-]*$"))) {
            "Measurement report file name is not marker-safe."
        }
        File(context.cacheDir, fileName).writeText(content)

        val encoded = Base64.encodeToString(
            content.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        val chunks = encoded.chunked(LOG_CHUNK_SIZE).ifEmpty { listOf("") }
        chunks.forEachIndexed { index, chunk ->
            Log.i(
                LOG_TAG,
                "$LOG_PREFIX|$fileName|${index + 1}|${chunks.size}|$chunk",
            )
        }
    }
}
