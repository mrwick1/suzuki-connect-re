package dev.mrwick.gixxerbridge.ui.inspector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.gixxerbridge.ble.FrameEvent
import dev.mrwick.gixxerbridge.ble.FrameStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Holds the rolling buffer of [FrameEvent]s observed from a [FrameStream], plus
 * pause + type-filter UI state for the BLE Frame Inspector screen.
 */
class InspectorViewModel(private val stream: FrameStream) : ViewModel() {

    private val _events = MutableStateFlow<List<FrameEvent>>(emptyList())

    /** Rolling window of captured frames, oldest first, capped at [MAX_EVENTS]. */
    val events: StateFlow<List<FrameEvent>> = _events.asStateFlow()

    private val _paused = MutableStateFlow(false)

    /** True when the inspector is paused — new events are dropped, existing list is preserved. */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _typeFilter = MutableStateFlow<Set<Int>>(emptySet())

    /** Bitset of frame type bytes (0x31..0x37) to show. Empty set = show all. */
    val typeFilter: StateFlow<Set<Int>> = _typeFilter.asStateFlow()

    // PERF: rolling buffer kept as a mutable ArrayDeque on the collector side
    // (single-collector, accessed only inside `viewModelScope.launch`). Prior
    // implementation rebuilt a brand-new ArrayList<FrameEvent> on EVERY emitted
    // event (`(it + event).takeLast(MAX_EVENTS)`), which is O(n) allocations
    // and triggers GC churn under burst. We mutate-in-place and publish an
    // immutable snapshot to the StateFlow only when the window changes
    // (audit finding 3.1).
    private val buffer: ArrayDeque<FrameEvent> = ArrayDeque(MAX_EVENTS)

    init {
        viewModelScope.launch {
            stream.events.collect { event ->
                if (_paused.value) return@collect
                if (buffer.size >= MAX_EVENTS) buffer.removeFirst()
                buffer.addLast(event)
                _events.value = buffer.toList()
            }
        }
    }

    /** Toggle the paused state; while paused, incoming events are discarded. */
    fun togglePause() { _paused.update { !it } }

    /** Drop every captured event. */
    fun clear() {
        // PERF: keep the buffer in sync with the published snapshot — otherwise
        // a clear followed by new events would re-publish the pre-clear window.
        buffer.clear()
        _events.value = emptyList()
    }

    /** Toggle whether frames with [typeByte] are shown; empty filter shows all. */
    fun toggleTypeFilter(typeByte: Int) {
        _typeFilter.update { if (typeByte in it) it - typeByte else it + typeByte }
    }

    companion object {
        /** Maximum number of events retained in memory before the oldest are dropped. */
        const val MAX_EVENTS = 500
    }
}
