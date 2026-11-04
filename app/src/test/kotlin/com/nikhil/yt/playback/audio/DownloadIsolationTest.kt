package com.nikhil.yt.playback.audio

import com.nikhil.yt.constants.AudioClientOrder
import com.nikhil.yt.playback.AudioWebStartupClientPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DownloadIsolationTest {
    @After fun clean() { CapsuleInnerTubeXPlayer.clearStreamClientFailures() }

    @Test fun downloadsAlwaysStartWithZeroPointOneAndKeepTokenFreeReserves() {
        val order = AudioDownloadClientPolicy.order
        assertEquals(AudioClientOrder.VISIONOS_0_1, order.first())
        assertEquals(order.size, order.distinct().size)
        assertTrue(order.none(AudioWebStartupClientPolicy::requiresToken))
        for (authenticated in listOf(false, true)) {
            val plan = CapsuleAudioFallbackPolicy.profilePlan(
                order.first(), AudioResolvePriority.DOWNLOAD, authenticated,
                isUploaded = false, excludedProfiles = emptySet(), preferredProfiles = order,
            )
            assertEquals(order, plan)
            val fallback = CapsuleAudioFallbackPolicy.profilePlan(
                order.first(), AudioResolvePriority.DOWNLOAD, authenticated,
                isUploaded = false, excludedProfiles = setOf(order.first()), preferredProfiles = order,
            )
            assertEquals(order.drop(1), fallback)
            assertTrue(fallback.size < CapsuleAudioFallbackPolicy.MAX_RESOLVE_ATTEMPTS)
        }
    }

    @Test fun signedInDownloadsHaveAccountBackedFallbackWithoutChangingAnonymousDownloads() {
        val order = AudioDownloadClientPolicy.orderForSession(hasLoginCookie = true)
        assertEquals(
            listOf(AudioClientOrder.VISIONOS_0_1, AudioClientOrder.WEB_REMIX, AudioClientOrder.WEB_CREATOR),
            order,
        )
        assertEquals(AudioDownloadClientPolicy.order, AudioDownloadClientPolicy.orderForSession(false))
        assertEquals(order.size, order.distinct().size)
        val plan = CapsuleAudioFallbackPolicy.profilePlan(
            order.first(), AudioResolvePriority.DOWNLOAD, authenticated = true,
            isUploaded = false, excludedProfiles = emptySet(), preferredProfiles = order,
        )
        assertEquals(order, plan)
        val afterVisionLoginRequired = CapsuleAudioFallbackPolicy.profilePlan(
            order.first(), AudioResolvePriority.DOWNLOAD, authenticated = true,
            isUploaded = false, excludedProfiles = setOf(AudioClientOrder.VISIONOS_0_1),
            preferredProfiles = order,
        )
        assertEquals(listOf(AudioClientOrder.WEB_REMIX, AudioClientOrder.WEB_CREATOR), afterVisionLoginRequired)
        assertEquals(
            listOf(AudioClientOrder.WEB_REMIX, AudioClientOrder.WEB_CREATOR),
            CapsuleAudioFallbackPolicy.profilePlan(
                order.first(), AudioResolvePriority.DOWNLOAD, authenticated = true,
                isUploaded = true, excludedProfiles = emptySet(), preferredProfiles = order,
            ),
        )
    }

    @Test fun signedInDownloadUsesAccountSessionButRetainsDistinctExtractionAndFailureIsolation() {
        // Structural guards: unit tests cannot make a privileged live YouTube
        // account request. They prevent accidentally regressing to guest-only
        // download extraction during future playback refactors.
        val source = listOf(
            java.io.File("src/main/kotlin/com/nikhil/yt/playback/audio/CapsuleInnerTubeXPlayer.kt"),
            java.io.File("app/src/main/kotlin/com/nikhil/yt/playback/audio/CapsuleInnerTubeXPlayer.kt"),
        ).first(java.io.File::isFile).readText()
        val extractors = source.substringAfter("ExtractionBundle(").substringBefore(").also { currentBundle = it }")
        assertTrue(extractors.contains("downloadExtractor = InnerTubeExtractor("))
        assertTrue(extractors.contains("if (lockedKey.auth.hasLoginCookie) tokenProvider(lockedKey.auth) else null"))
        assertTrue(source.contains("AudioDownloadClientPolicy.orderForSession("))
        assertTrue(source.contains("extractionBundle.innerTube.hasSapCookieAuth()"))
        assertTrue(source.contains("downloadStreamClientFailures = TrackClientFailureCache()"))
        assertTrue(source.contains("if (download) extractionBundle.downloadExtractor else extractionBundle.extractor"))
    }

    @Test fun downloadRejectionDoesNotQuarantineForegroundClientAndViceVersa() {
        CapsuleInnerTubeXPlayer.markStreamClientFailed("same-track", "VISIONOS_0_1", nowMs = 100L, download = true)
        assertEquals(setOf("VISIONOS_0_1"), CapsuleInnerTubeXPlayer.failedStreamClients("same-track", 101L, true))
        assertTrue(CapsuleInnerTubeXPlayer.failedStreamClients("same-track", 101L).isEmpty())
        CapsuleInnerTubeXPlayer.markStreamClientFailed("same-track", "WEB_REMIX", nowMs = 102L)
        assertEquals(setOf("WEB_REMIX"), CapsuleInnerTubeXPlayer.failedStreamClients("same-track", 103L))
        assertEquals(setOf("VISIONOS_0_1"), CapsuleInnerTubeXPlayer.failedStreamClients("same-track", 103L, true))
        CapsuleInnerTubeXPlayer.clearDownloadClientFailures("same-track")
        assertTrue(CapsuleInnerTubeXPlayer.failedStreamClients("same-track", 104L, true).isEmpty())
        assertEquals(setOf("WEB_REMIX"), CapsuleInnerTubeXPlayer.failedStreamClients("same-track", 104L))
    }

    @Test fun transientPlayerTimeoutDoesNotPoisonTheNextDownloadClientPlan() {
        CapsuleInnerTubeXPlayer.markStreamClientFailed(
            "timed-out-track", "VISIONOS_0_1", nowMs = 100L, download = true,
            failureKind = com.nikhil.yt.innertube.YouTubeFailureKind.TRANSIENT,
        )
        val excluded = CapsuleInnerTubeXPlayer.failedStreamClients("timed-out-track", 101L, true)
        assertTrue(excluded.isEmpty())
        assertEquals(AudioDownloadClientPolicy.order, CapsuleAudioFallbackPolicy.profilePlan(
            "VISIONOS_0_1", AudioResolvePriority.DOWNLOAD, false, false, excluded,
            AudioDownloadClientPolicy.order,
        ))
        assertNull(CapsulePlaybackSafety.blockedExceptionOrNull())
    }

    @Test fun challengeStopsLaterDownloadsWithoutSlidingDeadlineOrOpeningPlaybackBreaker() {
        var now = 1_000L
        val gate = DownloadBotCheckGate { now }
        gate.check()
        gate.onChallenge()
        assertEquals(60_000L, gate.remainingMs())
        repeat(50) {
            now += 1_000L
            assertThrows(DownloadBotCheckException::class.java) { gate.check() }
        }
        assertEquals(10_000L, gate.remainingMs())
        assertNull(CapsulePlaybackSafety.blockedExceptionOrNull())
        now += 10_000L
        gate.check()
        assertEquals(0L, gate.remainingMs())
    }
}
