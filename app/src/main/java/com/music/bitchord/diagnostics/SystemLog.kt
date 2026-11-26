package com.music.bitchord.diagnostics

import android.content.Context
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The report a listener can send when something is wrong.
 *
 * Three things, because a diagnosis needs all three: what the app did (its
 * own log), what it cost (the performance numbers), and what the device was
 * doing at the same time (the system's log for this app). Any one of them
 * alone leaves the question open.
 */
object SystemLog {

    /** How much of the system's own log to keep. */
    private const val SYSTEM_LOG_LINES = 1500

    /** How much of the app's own log to keep. */
    private const val APP_LOG_LINES = 600

    /**
     * The whole report, as text.
     *
     * [includeSystemLog] adds the device's own log for this app, which is
     * where the platform says what it thought of what the app asked it to do.
     */
    suspend fun build(context: Context, includeSystemLog: Boolean = true): String =
        withContext(Dispatchers.IO) {
            val parts = mutableListOf<String>()
            parts += header(context)
            parts += "── app log ──────────────────────────────────────────────"
            parts += TrackLog.recentLines(APP_LOG_LINES)
            runCatching { parts += com.music.bitchord.perf.PerfMonitor.report(context) }
            if (includeSystemLog) {
                parts += "── system log ──────────────────────────────────────────"
                parts += systemLog()
            }
            parts.joinToString("\n")
        }

    /** The build, the device and the app's own version line. */
    private fun header(context: Context): String {
        val lines = mutableListOf<String>()
        lines += "Appix system log — ${java.util.Date()}"
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val version = info.versionName ?: "?"
            lines += "app: $version (${info.versionCode}) ${info.versionName}"
        }
        runCatching {
            lines += "device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
        }
        TrackLog.headerLines().forEach { lines += it }
        return lines.joinToString("\n")
    }

    /**
     * The system's own log, as far as this app may read it.
     *
     * An app may read its own log and no one's else, which is exactly what
     * is wanted here: the platform's account of what it did with this app's
     * requests.
     */
    private fun systemLog(): List<String> {
        val pid = android.os.Process.myPid()
        // The system's log for this app alone where the platform can narrow it
        // that way, and the whole log where it cannot — an app may read its
        // own log and no one's else, so the wider reading is this app's
        // lines anyway.
        val narrow = listOf(
            "logcat", "-d", "-t", SYSTEM_LOG_LINES.toString(),
            "-v", "threadtime", "--pid", pid.toString(),
        )
        val wide = listOf(
            "logcat", "-d", "-t", SYSTEM_LOG_LINES.toString(),
            "-v", "threadtime",
        )
        return runCatching { read(narrow) }
            .recoverCatching { read(wide) }
            .getOrElse { listOf("(the system's log could not be read: ${it.message})") }
    }

    /** The output of one command, as lines. */
    private fun read(command: List<String>): List<String> {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        return text.lineSequence().filter { it.isNotBlank() }.take(SYSTEM_LOG_LINES).toList()
    }
}
