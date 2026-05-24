package dev.mrwick.gixxerbridge.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Default uncaught-exception handler that persists the stacktrace to
 * `filesDir/crash-logs/crash-<yyyyMMdd-HHmmss>.txt` and then chains to the
 * previous handler (usually Android's default), letting the OS kill the
 * process so START_STICKY on [dev.mrwick.gixxerbridge.ble.BikeBridgeService]
 * brings the bike link back up.
 *
 * Intentionally does NOT try to recover the current Activity — recovering a
 * partially-torn-down Compose tree is more risky than letting the OS restart
 * the foreground service. The user sees the last crash in the About screen
 * and can share the log via FileProvider.
 */
object CrashHandler {
    private const val DIR_NAME = "crash-logs"

    fun install(context: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        val appCtx = context.applicationContext
        // PERF: do the mkdir once at install time so the crash path doesn't
        // need to allocate a File + check existence under the (already
        // unhappy) crashing thread.
        val dir = File(appCtx.filesDir, DIR_NAME).also { it.mkdirs() }
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val out = File(dir, "crash-$ts.txt")
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                out.writeText(
                    "Thread: ${t.name}\nTime: ${Date()}\n\n$sw\n",
                )
            } catch (_: Throwable) {
                // ASSUMED: best-effort logging — if even the file write fails
                // (full disk, FS error, etc.) we still want to chain to the
                // OS handler so the process exits cleanly rather than hanging
                // in a crash-inside-crash loop.
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** Most recent crash file (or null if none). */
    fun latestCrashFile(context: Context): File? {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) return null
        return dir.listFiles()?.maxByOrNull { it.lastModified() }
    }

    /** Delete every persisted crash log. */
    fun clearAll(context: Context) {
        val dir = File(context.filesDir, DIR_NAME)
        dir.listFiles()?.forEach { it.delete() }
    }
}
