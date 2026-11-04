package com.nikhil.yt.playback.audio

import com.nikhil.yt.constants.AudioClientOrder

/**
 * Downloads have an independent, bounded client order; they never copy the user's
 * foreground preferences or reuse a foreground signed media URL.
 *
 * The anonymous plan is deliberately token-free. A signed-in user, however, can
 * have music that Vision/Embedded cannot access. After the fast Vision attempt,
 * let the account-backed WEB_REMIX and WEB_CREATOR profiles resolve that music
 * with the *current* session. Never try an authenticated profile for a guest.
 */
internal object AudioDownloadClientPolicy {
    val order = listOf(
        AudioClientOrder.VISIONOS_0_1,
        AudioClientOrder.WEB_EMBEDDED,
        AudioClientOrder.VISIONOS,
    )

    private val authenticatedOrder = listOf(
        AudioClientOrder.VISIONOS_0_1,
        AudioClientOrder.WEB_REMIX,
        AudioClientOrder.WEB_CREATOR,
    )

    fun orderForSession(hasLoginCookie: Boolean): List<String> =
        if (hasLoginCookie) authenticatedOrder else order
}
