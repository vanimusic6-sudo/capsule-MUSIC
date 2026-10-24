package com.nikhil.yt.playback.audio.potoken

import android.content.Context
import android.webkit.CookieManager

/** Startup and every extractor instance share the same bounded visitor session. */
class PoTokenGenerator(context: Context) {
    private val applicationContext = context.applicationContext
    private val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }

    private fun session(): PoTokenSession = synchronized(sessionLock) {
        sharedSession ?: PoTokenSession(create = { PoTokenWebView.create(applicationContext) })
            .also { sharedSession = it }
    }

    fun isReady(visitorData: String): Boolean = sharedSession?.isReady(visitorData.trim()) == true

    fun mayPrepare(visitorData: String): Boolean = sharedSession?.mayPrepare(visitorData.trim()) ?: true

    suspend fun prewarm(visitorData: String): Boolean =
        webViewSupported && session().prewarm(visitorData.trim())

    suspend fun getWebClientPoToken(videoId: String, visitorData: String): PoTokenResult? =
        if (webViewSupported) session().obtain(videoId, visitorData.trim()) else null

    suspend fun close() { sharedSession?.close() }

    private companion object {
        val sessionLock = Any()
        @Volatile var sharedSession: PoTokenSession? = null
    }
}
