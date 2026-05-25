package dev.mrwick.gixxerbridge.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app logger.
 *
 * Mirrors every entry to (a) android.util.Log so adb logcat still works, (b) an
 * in-memory ring buffer that the Diagnostics screen renders, (c) a rolling file
 * on disk so the log survives process restarts and can be reviewed after a
 * disconnected-from-laptop bike session.
 *
 * Designed to be called from anywhere without context — use [init] once from
 * Application.onCreate so the file mirror knows where to write.
 *
 * File layout (debuggable APK):
 *   /data/data/dev.mrwick.gixxerbridge.debug/files/diag/app.log     (active)
 *   /data/data/dev.mrwick.gixxerbridge.debug/files/diag/app.1.log   (previous rotation)
 *
 * Pull via:
 *   adb shell run-as dev.mrwick.gixxerbridge.debug cat files/diag/app.log
 */
object AppLog {

    enum class Level(val tag: String) { D("D"), I("I"), W("W"), E("E") }

    data class Entry(
        val tMillis: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val throwable: String? = null,
    )

    private const val RING_CAPACITY = 2000
    private const val MAX_FILE_BYTES = 512L * 1024L
    private const val TS_FMT = "MM-dd HH:mm:ss.SSS"

    private val ring = RingLog<Entry>(RING_CAPACITY)
    private val _flow = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _flow.asStateFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tsFormat = ThreadLocal.withInitial {
        SimpleDateFormat(TS_FMT, Locale.US)
    }

    @Volatile private var activeFile: File? = null
    @Volatile private var rotatedFile: File? = null

    /** Wire the file mirror. Safe to call multiple times — last-write-wins. */
    fun init(context: Context) {
        val dir = File(context.filesDir, "diag").apply { mkdirs() }
        activeFile = File(dir, "app.log")
        rotatedFile = File(dir, "app.1.log")
        i("AppLog", "logger initialized, file=${activeFile?.absolutePath}")
    }

    fun d(tag: String, msg: String) = log(Level.D, tag, msg, null)
    fun i(tag: String, msg: String) = log(Level.I, tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = log(Level.W, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(Level.E, tag, msg, t)

    private fun log(level: Level, tag: String, msg: String, t: Throwable?) {
        when (level) {
            Level.D -> Log.d(tag, msg, t)
            Level.I -> Log.i(tag, msg, t)
            Level.W -> Log.w(tag, msg, t)
            Level.E -> Log.e(tag, msg, t)
        }
        val entry = Entry(
            tMillis = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = msg,
            throwable = t?.let { stackOf(it) },
        )
        ring.add(entry)
        _flow.value = ring.snapshot()
        appendToFile(entry)
    }

    private fun stackOf(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun appendToFile(entry: Entry) {
        val file = activeFile ?: return
        ioScope.launch {
            try {
                if (file.length() >= MAX_FILE_BYTES) {
                    rotatedFile?.let { if (it.exists()) it.delete() }
                    rotatedFile?.let { file.renameTo(it) }
                }
                file.appendText(formatLine(entry))
            } catch (t: Throwable) {
                Log.w("AppLog", "file append failed: $t")
            }
        }
    }

    private fun formatLine(e: Entry): String {
        val ts = tsFormat.get()!!.format(Date(e.tMillis))
        val base = "$ts ${e.level.tag}/${e.tag}: ${e.message}\n"
        return if (e.throwable != null) base + e.throwable else base
    }

    /** Snapshot the ring to a single string — used by the share button. */
    fun snapshotText(): String {
        val snap = ring.snapshot()
        val sb = StringBuilder(snap.size * 100)
        for (entry in snap) sb.append(formatLine(entry))
        return sb.toString()
    }

    /** Returns the [activeFile] for share intents, or null if [init] was never called. */
    fun activeLogFile(): File? = activeFile

    /** Wipe ring + file. */
    fun clear() {
        ring.clear()
        _flow.value = emptyList()
        ioScope.launch {
            try {
                activeFile?.takeIf { it.exists() }?.delete()
                rotatedFile?.takeIf { it.exists() }?.delete()
            } catch (_: Throwable) { /* ignore */ }
        }
    }
}
