package dev.mrwick.gixxerbridge.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.gixxerbridge.app.AppGraph
import dev.mrwick.gixxerbridge.protocol.CallFrame
import dev.mrwick.gixxerbridge.protocol.Frame
import dev.mrwick.gixxerbridge.protocol.FrameType
import dev.mrwick.gixxerbridge.protocol.HeartbeatFrame
import dev.mrwick.gixxerbridge.protocol.IdentityFrame
import dev.mrwick.gixxerbridge.protocol.MissedCallFrame
import dev.mrwick.gixxerbridge.protocol.NavFrame
import dev.mrwick.gixxerbridge.protocol.SmsFrame
import dev.mrwick.gixxerbridge.protocol.TelemetryFrame
import dev.mrwick.gixxerbridge.util.Hex
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
