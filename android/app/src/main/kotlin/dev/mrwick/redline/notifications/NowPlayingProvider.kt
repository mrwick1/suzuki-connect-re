package dev.mrwick.redline.notifications

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Polls active media sessions for "now playing" metadata.
 * Designed to feed the cluster's idle widget once playback starts.
 *
 * Requires NotificationListenerService to be enabled (Android security tie-in
 * documented in assumptions log A11).
 */
class NowPlayingProvider(private val context: Context) {

    private val _track = MutableStateFlow<NowPlaying?>(null)
    val track: StateFlow<NowPlaying?> = _track.asStateFlow()

    private val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager?

    /** Sample the active sessions right now and update the flow. Cheap, idempotent. */
    fun refresh() {
        val listenerComponent = ComponentName(context, NotificationCaptureService::class.java)
        val controllers = try {
            msm?.getActiveSessions(listenerComponent).orEmpty()
        } catch (_: SecurityException) {
            return
        }
        for (c in controllers) {
            val state = c.playbackState?.state ?: continue
            if (state != PlaybackState.STATE_PLAYING) continue
            val md = c.metadata ?: continue
            val title = md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: continue
            val artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            _track.value = NowPlaying(title = title, artist = artist, packageName = c.packageName)
            return
        }
        _track.value = null
    }
}

@Immutable
data class NowPlaying(val title: String, val artist: String?, val packageName: String) {
    /** Single-line for cluster display, max 20 chars. */
    fun forCluster(): String {
        val s = if (artist != null) "$title - $artist" else title
        return s.take(20)
    }
}
