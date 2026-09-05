package com.nikhil.yt.playback.audio

import androidx.datastore.preferences.core.Preferences
import com.nikhil.yt.constants.*
import com.nikhil.yt.innertube.PlaybackAuthState

internal fun Preferences.playbackAuthState() = PlaybackAuthState(
    cookie = this[InnerTubeCookieKey],
    visitorData = this[VisitorDataKey],
    dataSyncId = this[DataSyncIdKey],
    poToken = this[PoTokenKey],
    poTokenGvs = this[PoTokenGvsKey],
    poTokenPlayer = this[PoTokenPlayerKey],
    webClientPoTokenEnabled = this[WebClientPoTokenEnabledKey] ?: false,
).normalized()

internal data class AudioPlaybackContext(
    val quality: AudioQuality,
    val policy: AudioStreamPolicy,
    val metered: Boolean,
    val session: Any = CapsuleInnerTubeXPlayer.sessionIdentity(),
)
