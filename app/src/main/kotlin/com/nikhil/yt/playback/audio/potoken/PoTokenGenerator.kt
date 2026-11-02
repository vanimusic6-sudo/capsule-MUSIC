package com.nikhil.yt.playback.audio.potoken

import android.content.Context
import android.webkit.CookieManager

/** AUDIO uses one bounded visitor session. Optional VIDEO owns a separate lifetime. */
class PoTokenGenerator(context: Context, private val independent: Boolean = false) {
    private val applicationContext = context.applicationContext
    private val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }

    private val ownedSession by lazy { PoTokenSession(create = { PoTokenWebView.create(applicationContext) }) }
    private fun activeSession(): PoTokenSession? = if (independent) ownedSession else sharedSession

    private fun session(): PoTokenSession {
        if (independent) return ownedSession
        return synchronized(sessionLock) {
            sharedSession ?: PoTokenSession(create = { PoTokenWebView.create(applicationContext) })
                .also { sharedSession = it }
        }
    }

    fun isReady(visitorData: String): Boolean = activeSession()?.isReady(visitorData.trim()) == true

    fun mayPrepare(visitorData: String): Boolean = activeSession()?.mayPrepare(visitorData.trim()) ?: true

    suspend fun prewarm(visitorData: String): Boolean =
        webViewSupported && session().prewarm(visitorData.trim())

    suspend fun getWebClientPoToken(videoId: String, visitorData: String): PoTokenResult? =
        if (webViewSupported) session().obtain(videoId, visitorData.trim()) else null

    suspend fun close() { activeSession()?.close() }

    private companion object {
        val sessionLock = Any()
        @Volatile var sharedSession: PoTokenSession? = null
    }
}
