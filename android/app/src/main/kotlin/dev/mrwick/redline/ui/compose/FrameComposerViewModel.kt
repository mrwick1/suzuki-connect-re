package dev.mrwick.redline.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.redline.app.AppGraph
import dev.mrwick.redline.protocol.CallFrame
import dev.mrwick.redline.protocol.Frame
import dev.mrwick.redline.protocol.FrameType
import dev.mrwick.redline.protocol.HeartbeatFrame
import dev.mrwick.redline.protocol.IdentityFrame
import dev.mrwick.redline.protocol.MissedCallFrame
import dev.mrwick.redline.protocol.NavFrame
import dev.mrwick.redline.protocol.SmsFrame
import dev.mrwick.redline.protocol.TelemetryFrame
import dev.mrwick.redline.util.Hex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the Frame Composer screen — holds the currently-selected [FrameType] tab
 * and the result line for the last [send] attempt. Each form composable owns its
 * own field state and hands a fully-built [Frame] to [send] when the user taps Send.
 */
class FrameComposerViewModel : ViewModel() {

    private val _frameType = MutableStateFlow(FrameType.NAV)
    val frameType: StateFlow<FrameType> = _frameType.asStateFlow()

    private val _lastResult = MutableStateFlow<String?>(null)
    val lastResult: StateFlow<String?> = _lastResult.asStateFlow()

    /** Switch the visible tab / form. */
    fun selectType(t: FrameType) {
        _frameType.value = t
    }

    /** Encode [frame] and ship it via [AppGraph.sendFrame]; record the result. */
    fun send(frame: Frame) {
        val bytes = when (frame) {
            is NavFrame -> frame.encode()
            is CallFrame -> frame.encode()
            is HeartbeatFrame -> frame.encode()
            is MissedCallFrame -> frame.encode()
            is SmsFrame -> frame.encode()
            is IdentityFrame -> frame.encode()
            is TelemetryFrame -> frame.encode()
        }
        viewModelScope.launch {
            val ok = AppGraph.sendFrame(bytes)
            _lastResult.value =
                if (ok) "Sent: ${Hex.encode(bytes, " ")}"
                else "Send FAILED: ${Hex.encode(bytes, " ")}"
        }
    }
}
