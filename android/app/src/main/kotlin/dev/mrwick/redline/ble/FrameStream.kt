package dev.mrwick.redline.ble

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hot stream of every TX + RX byte array that crosses BLE.
 * Consumers: InspectorScreen (live view), and a rolling on-disk frame log so the
 * raw wire traffic of a whole bike session survives off-tether and can be
 * reviewed / shared later (parallels [dev.mrwick.redline.util.AppLog]).
 *
 * Disk layout (debuggable APK):
 *   filesDir/diag/frames.log    (active)
 *   filesDir/diag/frames.1.log  (previous rotation)
 * Pull via: adb shell run-as <pkg> cat files/diag/frames.log
 */
class FrameStream {
    // PERF: explicit DROP_OLDEST overflow policy. Inspector is the only
    // consumer and it's a UI surface — if it ever falls behind, we want the
    // newest frames to win rather than freezing on stale data. With the
    // default (SUSPEND) and `tryEmit`, slow consumers silently lose new
    // frames; DROP_OLDEST drops the back of the buffer first so the most
    // recent traffic stays visible (audit finding 3.3).
    private val _events = MutableSharedFlow<FrameEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<FrameEvent> = _events.asSharedFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tsFormat = ThreadLocal.withInitial { SimpleDateFormat(TS_FMT, Locale.US) }
    @Volatile private var activeFile: File? = null
    @Volatile private var rotatedFile: File? = null

    /** Wire the on-disk mirror. Call once from Application.onCreate. */
    fun init(context: Context) {
        val dir = File(context.filesDir, "diag").apply { mkdirs() }
        activeFile = File(dir, "frames.log")
        rotatedFile = File(dir, "frames.1.log")
    }

    fun emit(event: FrameEvent) {
        val ok = _events.tryEmit(event)
        android.util.Log.d("FrameStream", "emit dir=${event.direction} size=${event.bytes.size} ok=$ok subs=${_events.subscriptionCount.value}")
        appendToFile(event)
    }

    /** Returns the active frame-log file for share intents, or null if uninitialised. */
    fun activeLogFile(): File? = activeFile

    private fun appendToFile(event: FrameEvent) {
        // Skip the idle "nav-preview" frames the cluster preview emits while
        // disconnected — the on-disk log is meant to capture real wire traffic
        // from connected sessions, not the 1 Hz preview churn.
        if (event.note == "nav-preview") return
        val file = activeFile ?: return
        ioScope.launch {
            try {
                if (file.length() >= MAX_FILE_BYTES) {
                    rotatedFile?.let { if (it.exists()) it.delete() }
                    rotatedFile?.let { file.renameTo(it) }
                }
                file.appendText(formatLine(event))
            } catch (t: Throwable) {
                android.util.Log.w("FrameStream", "frame-log append failed: $t")
            }
        }
    }

    private fun formatLine(e: FrameEvent): String {
        val ts = tsFormat.get()!!.format(Date(e.tMillis))
        val hex = e.bytes.joinToString(" ") { "%02x".format(it.toInt() and 0xff) }
        val note = e.note?.let { " ($it)" } ?: ""
        return "$ts ${e.direction}$note  $hex\n"
    }

    private companion object {
        const val MAX_FILE_BYTES = 2L * 1024L * 1024L // 2 MB before rotation
        const val TS_FMT = "MM-dd HH:mm:ss.SSS"
    }
}

/** A single TX or RX BLE event with its raw bytes and timestamp. */
@Immutable
data class FrameEvent(
    val direction: Direction,
    val bytes: ByteArray,
    val tMillis: Long = System.currentTimeMillis(),
    val note: String? = null,
) {
    enum class Direction { TX, RX }

    override fun equals(other: Any?): Boolean =
        other is FrameEvent &&
            direction == other.direction &&
            bytes.contentEquals(other.bytes) &&
            tMillis == other.tMillis &&
            note == other.note

    override fun hashCode(): Int =
        ((direction.hashCode() * 31 + bytes.contentHashCode()) * 31 + tMillis.hashCode()) * 31 + (note?.hashCode() ?: 0)
}
