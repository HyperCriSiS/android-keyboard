package org.futo.inputmethod.latin.personalization

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.InstrumentationRegistry
import java.io.File

/**
 * Publishes a measurement report while the debug target application is still installed.
 *
 * connectedAndroidTest may uninstall the target APK before the next GitHub Actions step. The test
 * therefore writes to private cache first and then asks the instrumentation shell to stream the
 * file through run-as into /data/local/tmp, which remains readable through adb after the run.
 */
object PersonalizationMeasurementReportPublisher {
    fun publish(
        context: Context,
        fileName: String,
        content: String,
    ) {
        require(fileName.matches(Regex("^[a-z0-9][a-z0-9._-]*$"))) {
            "Measurement report file name is not shell-safe."
        }
        val privateFile = File(context.cacheDir, fileName)
        privateFile.writeText(content)

        val command = "run-as ${shellQuote(context.packageName)} cat " +
            "${shellQuote(privateFile.absolutePath)} > ${shellQuote("/data/local/tmp/$fileName")}" 
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        drainAndClose(descriptor)
    }

    private fun drainAndClose(descriptor: ParcelFileDescriptor) {
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val buffer = ByteArray(1_024)
            while (input.read(buffer) >= 0) {
                // Draining waits for the shell command to complete.
            }
        }
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
