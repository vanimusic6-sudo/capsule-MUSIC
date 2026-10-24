package com.nikhil.yt.playback.audio

import com.nikhil.yt.constants.AudioClientOrder

/** Downloads never borrow the user's foreground WEB/token client. */
internal object AudioDownloadClientPolicy {
    val order = listOf(
        AudioClientOrder.VISIONOS_0_1,
        AudioClientOrder.WEB_EMBEDDED,
        AudioClientOrder.VISIONOS,
    )
}
