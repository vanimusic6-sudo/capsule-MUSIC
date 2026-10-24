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
