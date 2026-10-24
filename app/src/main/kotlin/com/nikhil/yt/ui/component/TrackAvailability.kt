package com.nikhil.yt.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.media3.exoplayer.offline.Download
import com.nikhil.yt.LocalDownloadUtil
import com.nikhil.yt.R
import com.nikhil.yt.playback.PlaybackFailureKind
import com.nikhil.yt.playback.TrackAvailability

@Composable
internal fun trackUnavailableReason(id: String, catalogUnavailable: Boolean = false): String? {
    val states by TrackAvailability.states.collectAsState()
    val downloads by LocalDownloadUtil.current.downloads.collectAsState()
    // A removed online recording remains playable from a complete local download.
    if (downloads[id]?.state == Download.STATE_COMPLETED || id.startsWith("capsule-open:offline:")) return null
    return when (states[id] ?: if (catalogUnavailable) PlaybackFailureKind.UNAVAILABLE else null) {
        PlaybackFailureKind.UNAVAILABLE -> stringResource(R.string.error_track_unavailable_description)
        PlaybackFailureKind.AUTH_REQUIRED -> stringResource(R.string.error_auth_required_description)
        PlaybackFailureKind.AGE_RESTRICTED -> stringResource(R.string.error_age_restricted_description)
        PlaybackFailureKind.ACCESS_RESTRICTED -> stringResource(R.string.error_access_restricted_description)
        else -> null
    }
}
