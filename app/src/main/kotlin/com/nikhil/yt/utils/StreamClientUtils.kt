/*
 * Capsule MUSIC
 * Shared YouTube stream-header helpers.
 * GPL-3.0
 */

package com.nikhil.yt.utils

import com.nikhil.yt.innertube.models.YouTubeClient

object StreamClientUtils {
    fun resolveUserAgent(clientParam: String): String {
        val c = clientParam.trim()

        return when {
            c.equals("WEB_REMIX", ignoreCase = true) ||
                c.equals("WEB", ignoreCase = true) ||
                c.equals("WEB_CREATOR", ignoreCase = true) ||
                c.equals("WEB_EMBEDDED_PLAYER", ignoreCase = true) ->
                YouTubeClient.USER_AGENT_WEB

            c.equals("MWEB", ignoreCase = true) ->
                YouTubeClient.MWEB.userAgent

            c.equals("TVHTML5", ignoreCase = true) ||
                c.equals("TVHTML5_SIMPLY_EMBEDDED_PLAYER", ignoreCase = true) ||
                c.equals("TVHTML5_SIMPLY", ignoreCase = true) ->
                YouTubeClient.TVHTML5.userAgent

            c.equals("IOS_MUSIC", ignoreCase = true) ->
                YouTubeClient.IOS_MUSIC.userAgent

            c.startsWith("IOS", ignoreCase = true) ->
                YouTubeClient.IOS.userAgent

            c.startsWith("ANDROID_VR", ignoreCase = true) ->
                YouTubeClient.ANDROID_VR_1_65_10.userAgent

            c.equals("ANDROID_MUSIC", ignoreCase = true) ->
                YouTubeClient.ANDROID_MUSIC.userAgent

            c.equals("ANDROID_TESTSUITE", ignoreCase = true) ->
                YouTubeClient.ANDROID_TESTSUITE.userAgent

            c.equals("ANDROID_UNPLUGGED", ignoreCase = true) ->
                YouTubeClient.ANDROID_UNPLUGGED.userAgent

            c.startsWith("ANDROID_CREATOR", ignoreCase = true) ->
                YouTubeClient.ANDROID_CREATOR.userAgent

            c.startsWith("ANDROID", ignoreCase = true) ->
                YouTubeClient.MOBILE.userAgent

            c.startsWith("VISIONOS", ignoreCase = true) ->
                YouTubeClient.VISIONOS.userAgent

            /*
             * Unknown `c` must not silently masquerade as Android VR.
             * visionOS is Capsule's current conservative anonymous default.
             */
            else -> YouTubeClient.VISIONOS.userAgent
        }
    }

    data class OriginReferer(
        val origin: String?,
        val referer: String?,
    )

    fun resolveOriginReferer(clientParam: String): OriginReferer {
        val c = clientParam.trim()

        return when {
            c.equals("WEB_REMIX", ignoreCase = true) ||
                c.equals("WEB", ignoreCase = true) ||
                c.equals("WEB_CREATOR", ignoreCase = true) ||
                c.equals("WEB_EMBEDDED_PLAYER", ignoreCase = true) ||
                c.equals("MWEB", ignoreCase = true) ->
                OriginReferer(
                    YouTubeClient.ORIGIN_YOUTUBE_MUSIC,
                    YouTubeClient.REFERER_YOUTUBE_MUSIC,
                )

            c.equals("TVHTML5", ignoreCase = true) ||
                c.equals("TVHTML5_SIMPLY_EMBEDDED_PLAYER", ignoreCase = true) ||
                c.equals("TVHTML5_SIMPLY", ignoreCase = true) ->
                OriginReferer(
                    YouTubeClient.ORIGIN_YOUTUBE,
                    YouTubeClient.REFERER_YOUTUBE_TV,
                )

            else -> OriginReferer(null, null)
        }
    }

    fun isWebClient(clientParam: String): Boolean {
        val c = clientParam.trim()

        return c.equals("WEB", ignoreCase = true) ||
            c.equals("WEB_REMIX", ignoreCase = true) ||
            c.equals("WEB_CREATOR", ignoreCase = true) ||
            c.equals("MWEB", ignoreCase = true) ||
            c.equals("WEB_EMBEDDED_PLAYER", ignoreCase = true)
    }

    fun patchClientVersion(
        url: String,
        clientVersion: String,
    ): String {
        if (!url.contains("cver=")) return url
        return url.replace(
            Regex("cver=[^&]+"),
            "cver=$clientVersion",
        )
    }

    fun appendPoToken(
        url: String,
        poToken: String,
    ): String {
        if (url.contains("pot=")) return url

        val separator = if (url.contains("?")) "&" else "?"
        return "$url${separator}pot=$poToken"
    }
}
