package io.github.lucabarbalata.anprox.diagnostics

import android.content.Context
import android.os.Build
import io.github.lucabarbalata.anprox.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Privacy-preserving crash reporting (questionnaire Q36: no telemetry).
 *
 * If the app crashes, the stack trace is written to a private file — nothing is sent anywhere.
 * On the next launch the app offers to *share* the report through the normal Android share
 * sheet, so the user sees exactly what leaves the device and chooses where it goes.
 *
 * The report contains: app version, Android version, device model, the thread name and the
 * stack trace. Exception messages can mention host names, so the user is asked to review it.
 */
class CrashReporter(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    /** Chains in front of the existing handler, so Android still shows its crash dialog. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText(report(thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The report from the last crash, if the user hasn't dealt with it yet. */
    fun pendingReport(): String? = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear() {
        runCatching { file.delete() }
    }

    private fun report(thread: Thread, error: Throwable): String = buildString {
        appendLine("AnProx ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        appendLine("Thread: ${thread.name}")
        appendLine()
        append(error.stackTraceToString().take(MAX_TRACE_CHARS))
    }

    private companion object {
        const val FILE_NAME = "last-crash.txt"
        const val MAX_TRACE_CHARS = 60_000
    }
}
