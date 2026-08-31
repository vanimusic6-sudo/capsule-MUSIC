/*
 * Capsule MUSIC
 * YouTube playback client identities.
 *
 * Active playback identities are synchronized against yt-dlp's maintained
 * YouTube client table. Legacy constants are preserved only for source/API
 * compatibility and are NOT part of Capsule's normal AUDIO fallback chain.
 *
 * Snapshot basis: yt-dlp master, 2026-08-31.
 * GPL-3.0
 */

package com.nikhil.yt.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class YouTubeClient(
    val clientName: String,
    val clientVersion: String,
    val clientId: String,
    val userAgent: String,
    val osName: String? = null,
    val osVersion: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val androidSdkVersion: String? = null,
    val buildId: String? = null,
    val cronetVersion: String? = null,
    val packageName: String? = null,
    val friendlyName: String? = null,
    val loginSupported: Boolean = false,
    val loginRequired: Boolean = false,
    val useSignatureTimestamp: Boolean = false,
    val isEmbedded: Boolean = false,
) {
    fun toContext(
        locale: YouTubeLocale,
        visitorData: String?,
        dataSyncId: String?,
    ) =
        Context(
            client =
                Context.Client(
                    clientName = clientName,
                    clientVersion = clientVersion,
                    osName = osName,
                    osVersion = osVersion,
                    deviceMake = deviceMake,
                    deviceModel = deviceModel,
                    androidSdkVersion = androidSdkVersion,
                    gl = locale.gl,
                    hl = locale.hl,
                    visitorData = visitorData,
                ),
            user =
                Context.User(
                    onBehalfOfUser =
                        if (loginSupported) {
                            dataSyncId
                        } else {
                            null
                        },
                ),
        )

    companion object {
        const val USER_AGENT_WEB =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/141.0.0.0 Safari/537.36"

        const val ORIGIN_YOUTUBE_MUSIC = "https://music.youtube.com"
        const val REFERER_YOUTUBE_MUSIC = "$ORIGIN_YOUTUBE_MUSIC/"
        const val API_URL_YOUTUBE_MUSIC = "$ORIGIN_YOUTUBE_MUSIC/youtubei/v1/"

        const val ORIGIN_YOUTUBE = "https://www.youtube.com"
        const val REFERER_YOUTUBE_TV = "$ORIGIN_YOUTUBE/tv"

        /*
         * ------------------------------------------------------------------
         * CURRENT IDENTITIES
         * ------------------------------------------------------------------
         *
         * Do not blindly add a client to YTPlayerUtils's fallback chain merely
         * because it has the newest version. PO-Token policy matters more than
         * version freshness.
         */

        val WEB =
            YouTubeClient(
                clientName = "WEB",
                clientVersion = YouTubeClientUpstream.WEB_VERSION,
                clientId = "1",
                userAgent = USER_AGENT_WEB,
            )

        val WEB_REMIX =
            YouTubeClient(
                clientName = "WEB_REMIX",
                clientVersion = YouTubeClientUpstream.WEB_MUSIC_VERSION,
                clientId = "67",
                userAgent = USER_AGENT_WEB,
                loginSupported = true,
                useSignatureTimestamp = true,
                friendlyName = "Web Music",
            )

        val WEB_CREATOR =
            YouTubeClient(
                clientName = "WEB_CREATOR",
                clientVersion = YouTubeClientUpstream.WEB_CREATOR_VERSION,
                clientId = "62",
                userAgent = USER_AGENT_WEB,
                loginSupported = true,
                loginRequired = true,
                useSignatureTimestamp = true,
            )

        val WEB_EMBEDDED =
            YouTubeClient(
                clientName = "WEB_EMBEDDED_PLAYER",
                clientVersion = YouTubeClientUpstream.WEB_EMBEDDED_VERSION,
                clientId = "56",
                userAgent = USER_AGENT_WEB,
                friendlyName = "Web Embedded Player",
                loginSupported = false,
                useSignatureTimestamp = true,
                isEmbedded = true,
            )

        val WEB_MUSIC: YouTubeClient
            get() = WEB_REMIX

        val MWEB =
            YouTubeClient(
                clientName = "MWEB",
                clientVersion = YouTubeClientUpstream.MWEB_VERSION,
                clientId = "2",
                userAgent = YouTubeClientUpstream.MWEB_USER_AGENT,
                friendlyName = "Mobile Web",
                loginSupported = false,
            )

        val IOS =
            YouTubeClient(
                clientName = "IOS",
                clientVersion = YouTubeClientUpstream.IOS_VERSION,
                clientId = "5",
                userAgent = YouTubeClientUpstream.IOS_USER_AGENT,
                osName = YouTubeClientUpstream.IOS_OS_NAME,
                osVersion = YouTubeClientUpstream.IOS_OS_VERSION,
                deviceMake = YouTubeClientUpstream.IOS_DEVICE_MAKE,
                deviceModel = YouTubeClientUpstream.IOS_DEVICE_MODEL,
                friendlyName = "iOS",
                loginSupported = false,
                useSignatureTimestamp = false,
            )

        val MOBILE =
            YouTubeClient(
                clientName = "ANDROID",
                clientVersion = YouTubeClientUpstream.ANDROID_VERSION,
                clientId = "3",
                userAgent = YouTubeClientUpstream.ANDROID_USER_AGENT,
                osName = YouTubeClientUpstream.ANDROID_OS_NAME,
                osVersion = YouTubeClientUpstream.ANDROID_OS_VERSION,
                androidSdkVersion = YouTubeClientUpstream.ANDROID_SDK,
                friendlyName = "Android",
                loginSupported = false,
                useSignatureTimestamp = false,
            )

        /*
         * Current low-friction anonymous primary.
         *
         * yt-dlp currently does not declare GVS/Player PO-Token requirements
         * for this client. If upstream changes that policy, Capsule's workflow
         * stops automatic synchronization and opens a manual-review issue.
         */
        val VISIONOS =
            YouTubeClient(
                clientName = "VISIONOS",
                clientVersion = YouTubeClientUpstream.VISIONOS_VERSION,
                clientId = "101",
                userAgent = YouTubeClientUpstream.VISIONOS_USER_AGENT,
                osName = YouTubeClientUpstream.VISIONOS_OS_NAME,
                osVersion = YouTubeClientUpstream.VISIONOS_OS_VERSION,
                deviceMake = YouTubeClientUpstream.VISIONOS_DEVICE_MAKE,
                deviceModel = YouTubeClientUpstream.VISIONOS_DEVICE_MODEL,
                friendlyName = "visionOS",
                loginSupported = false,
                useSignatureTimestamp = false,
            )

        val TVHTML5 =
            YouTubeClient(
                clientName = "TVHTML5",
                clientVersion = YouTubeClientUpstream.TV_VERSION,
                clientId = "7",
                userAgent = YouTubeClientUpstream.TV_USER_AGENT,
                friendlyName = "TV",
                loginSupported = false,
                useSignatureTimestamp = false,
            )

        /*
         * yt-dlp keeps a downgraded TV client as a compatibility path.
         * Capsule uses it anonymously and only as a fallback.
         */
        val TVHTML5_DOWNGRADED =
            YouTubeClient(
                clientName = "TVHTML5",
                clientVersion = YouTubeClientUpstream.TV_DOWNGRADED_VERSION,
                clientId = "7",
                userAgent = YouTubeClientUpstream.TV_DOWNGRADED_USER_AGENT,
                friendlyName = "TV downgraded",
                loginSupported = false,
                useSignatureTimestamp = false,
            )

        /*
         * ------------------------------------------------------------------
         * LEGACY IDENTITIES
         * ------------------------------------------------------------------
         *
         * Kept so old settings/source references continue compiling.
         * They are intentionally excluded from Capsule's normal fallback
         * chain because several now have stricter PO-Token enforcement.
         */

        val ANDROID_VR_1_65_10 =
            YouTubeClient(
                clientName = "ANDROID_VR",
                clientVersion = "1.65.10",
                clientId = "28",
                userAgent =
                    "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
                        "(Linux; U; Android 12L; eureka-user " +
                        "Build/SQ3A.220605.009.A1) gzip",
                osName = "Android",
                osVersion = "12L",
                deviceMake = "Oculus",
                deviceModel = "Quest 3",
                androidSdkVersion = "32",
                friendlyName = "Android VR 1.65 (legacy)",
                loginSupported = false,
                useSignatureTimestamp = false,
            )

        val ANDROID_VR_NO_AUTH =
            YouTubeClient(
                clientName = "ANDROID_VR",
                clientVersion = "1.37",
                clientId = "28",
                userAgent =
                    "com.google.android.apps.youtube.vr.oculus/1.37 " +
                        "(Linux; U; Android 12; en_US; Quest 3; " +
                        "Build/SQ3A.220605.009.A1; Cronet/107.0.5284.2)",
                osName = "Android",
                osVersion = "12",
                deviceMake = "Oculus",
                deviceModel = "Quest 3",
                androidSdkVersion = "32",
                friendlyName = "Android VR 1.37 (legacy)",
                loginSupported = false,
                useSignatureTimestamp = false,
            )

        val ANDROID_VR_1_61_48 =
            YouTubeClient(
                clientName = "ANDROID_VR",
                clientVersion = "1.61.48",
                clientId = "28",
                userAgent =
                    "com.google.android.apps.youtube.vr.oculus/1.61.48 " +
                        "(Linux; U; Android 12; en_US; Quest 3; " +
                        "Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)",
                osName = "Android",
                osVersion = "12",
                deviceMake = "Oculus",
                deviceModel = "Quest 3",
                androidSdkVersion = "32",
                buildId = "SQ3A.220605.009.A1",
                cronetVersion = "132.0.6808.3",
                packageName = "com.google.android.apps.youtube.vr.oculus",
                friendlyName = "Android VR 1.61 (legacy)",
                loginSupported = false,
                useSignatureTimestamp = false,
            )

        val ANDROID_VR_1_43_32 =
            YouTubeClient(
                clientName = "ANDROID_VR",
                clientVersion = "1.43.32",
                clientId = "28",
                userAgent =
                    "com.google.android.apps.youtube.vr.oculus/1.43.32 " +
                        "(Linux; U; Android 12; en_US; Quest 3; " +
                        "Build/SQ3A.220605.009.A1; Cronet/107.0.5284.2)",
                osName = "Android",
                osVersion = "12",
                deviceMake = "Oculus",
                deviceModel = "Quest 3",
                androidSdkVersion = "32",
                buildId = "SQ3A.220605.009.A1",
                cronetVersion = "107.0.5284.2",
                packageName = "com.google.android.apps.youtube.vr.oculus",
                friendlyName = "Android VR 1.43 (legacy)",
                loginSupported = false,
                useSignatureTimestamp = false,
            )

        val TVHTML5_SIMPLY_EMBEDDED_PLAYER =
            YouTubeClient(
                clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                clientVersion = "2.0",
                clientId = "85",
                userAgent =
                    "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) " +
                        "AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                        "Version/15.4 Safari/605.1.15",
                loginSupported = true,
                loginRequired = true,
                useSignatureTimestamp = true,
                isEmbedded = true,
            )

        val ANDROID_CREATOR =
            YouTubeClient(
                clientName = "ANDROID_CREATOR",
                clientVersion = "23.47.101",
                clientId = "14",
                userAgent =
                    "com.google.android.apps.youtube.creator/23.47.101 " +
                        "(Linux; U; Android 15; en_US; Pixel 9 Pro Fold; " +
                        "Build/AP3A.241005.015.A2; Cronet/132.0.6779.0)",
                osName = "Android",
                osVersion = "15",
                deviceMake = "Google",
                deviceModel = "Pixel 9 Pro Fold",
                androidSdkVersion = "35",
                buildId = "AP3A.241005.015.A2",
                cronetVersion = "132.0.6779.0",
                packageName = "com.google.android.apps.youtube.creator",
                friendlyName = "Android Creator (legacy)",
                loginSupported = true,
                useSignatureTimestamp = true,
            )

        val IPADOS =
            YouTubeClient(
                clientName = "IOS",
                clientVersion = "19.22.3",
                clientId = "5",
                userAgent =
                    "com.google.ios.youtube/19.22.3 " +
                        "(iPad7,6; U; CPU iPadOS 17_7_10 like Mac OS X; en-US)",
                osName = "iPadOS",
                osVersion = "17.7.10.21H450",
                deviceMake = "Apple",
                deviceModel = "iPad7,6",
                friendlyName = "iPadOS (legacy)",
                loginSupported = false,
                useSignatureTimestamp = false,
                packageName = "com.google.ios.youtube",
            )

        val WEB_SAFARI =
            YouTubeClient(
                clientName = "WEB",
                clientVersion = YouTubeClientUpstream.WEB_VERSION,
                clientId = "1",
                userAgent =
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                        "AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                        "Version/15.5 Safari/605.1.15,gzip(gfe)",
                friendlyName = "Web Safari",
            )

        /*
         * Not part of the safe fallback chain. They are preserved for settings
         * compatibility until Capsule gets a real Android/iOS PO-token provider.
         */
        val ANDROID_MUSIC =
            YouTubeClient(
                clientName = "ANDROID_MUSIC",
                clientVersion = "7.27.52",
                clientId = "21",
                userAgent =
                    "com.google.android.apps.youtube.music/7.27.52 " +
                        "(Linux; U; Android 15; en_US; Pixel 9 Pro; " +
                        "Build/AP4A.250205.002; Cronet/132.0.6834.79) gzip",
                osName = "Android",
                osVersion = "15",
                deviceMake = "Google",
                deviceModel = "Pixel 9 Pro",
                androidSdkVersion = "35",
                buildId = "AP4A.250205.002",
                cronetVersion = "132.0.6834.79",
                packageName = "com.google.android.apps.youtube.music",
                friendlyName = "Android Music (legacy)",
                loginSupported = true,
                useSignatureTimestamp = true,
            )

        val ANDROID_TESTSUITE =
            YouTubeClient(
                clientName = "ANDROID_TESTSUITE",
                clientVersion = "1.9",
                clientId = "30",
                userAgent =
                    "com.google.android.youtube/1.9 " +
                        "(Linux; U; Android 15; en_US; Pixel 9 Pro; " +
                        "Build/AP4A.250205.002) gzip",
                osName = "Android",
                osVersion = "15",
                deviceMake = "Google",
                deviceModel = "Pixel 9 Pro",
                androidSdkVersion = "35",
                friendlyName = "Android TestSuite (legacy)",
                loginSupported = false,
                useSignatureTimestamp = false,
            )

        val ANDROID_UNPLUGGED =
            YouTubeClient(
                clientName = "ANDROID_UNPLUGGED",
                clientVersion = "8.49.0",
                clientId = "29",
                userAgent =
                    "com.google.android.apps.youtube.unplugged/8.49.0 " +
                        "(Linux; U; Android 15; en_US; Pixel 9 Pro; " +
                        "Build/AP4A.250205.002; Cronet/132.0.6834.79) gzip",
                osName = "Android",
                osVersion = "15",
                deviceMake = "Google",
                deviceModel = "Pixel 9 Pro",
                androidSdkVersion = "35",
                friendlyName = "Android TV (legacy)",
                loginSupported = true,
                useSignatureTimestamp = true,
            )

        val IOS_MUSIC =
            YouTubeClient(
                clientName = "IOS_MUSIC",
                clientVersion = "7.27.0",
                clientId = "26",
                userAgent =
                    "com.google.ios.youtubemusic/7.27.0 " +
                        "(iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)",
                osName = "iOS",
                osVersion = "17.5.1.21F90",
                deviceMake = "Apple",
                deviceModel = "iPhone16,2",
                friendlyName = "iOS Music (legacy)",
                loginSupported = false,
                useSignatureTimestamp = false,
            )
    }
}
