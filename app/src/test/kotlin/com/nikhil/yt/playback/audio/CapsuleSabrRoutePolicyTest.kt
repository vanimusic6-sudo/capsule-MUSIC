package com.nikhil.yt.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapsuleSabrRoutePolicyTest {
    @Test fun forcedSabrIsFirstEvenWhenNoDirectAttemptFailed() {
        assertEquals(CapsuleSabrRoutePolicy.PROFILE, CapsuleSabrRoutePolicy.select(
            forceSabr = true, download = false, priority = AudioResolvePriority.PLAYBACK,
            botSignalAlreadySeen = false, attempted = emptySet(),
        ))
    }

    @Test fun automaticSabrWaitsForTwoDirectFailures() {
        val attempted = setOf("WEB_REMIX")
        assertNull(CapsuleSabrRoutePolicy.select(false, false, AudioResolvePriority.PLAYBACK, false, attempted))
        assertEquals(CapsuleSabrRoutePolicy.PROFILE, CapsuleSabrRoutePolicy.select(
            false, false, AudioResolvePriority.PLAYBACK, false, attempted + "TVHTML5_SIMPLY"))
    }

    @Test fun twoHistoricalCdnClientFailuresPreferSabrBeforeSlowNextClient() {
        assertEquals(CapsuleSabrRoutePolicy.PROFILE, CapsuleSabrRoutePolicy.select(
            forceSabr = false, download = false, priority = AudioResolvePriority.PLAYBACK,
            botSignalAlreadySeen = false, attempted = emptySet(), previousDirectFailures = 2,
        ))
        assertNull(CapsuleSabrRoutePolicy.select(
            forceSabr = false, download = false, priority = AudioResolvePriority.PLAYBACK,
            botSignalAlreadySeen = false, attempted = emptySet(), previousDirectFailures = 1,
        ))
    }

    @Test fun historicalSabrFailureBlocksAutomaticRescueButNotDirectFallback() {
        assertNull(CapsuleSabrRoutePolicy.select(
            forceSabr = false, download = false, priority = AudioResolvePriority.PLAYBACK,
            botSignalAlreadySeen = false, attempted = setOf(CapsuleSabrRoutePolicy.PROFILE),
            previousDirectFailures = 3,
        ))
    }

    @Test fun eligibleDirectClientTakesPrecedenceOverAutomaticSabr() {
        assertNull(CapsuleSabrRoutePolicy.select(false, false, AudioResolvePriority.PLAYBACK,
            false, emptySet(), previousDirectFailures = 2, directCandidateAvailable = true))
        assertEquals(CapsuleSabrRoutePolicy.PROFILE, CapsuleSabrRoutePolicy.select(
            true, false, AudioResolvePriority.PLAYBACK, false, emptySet(), directCandidateAvailable = true))
    }

    @Test fun previousDirectFailuresNeverStartSabrInDownloadOrPrefetch() {
        assertNull(CapsuleSabrRoutePolicy.select(
            forceSabr = false, download = true, priority = AudioResolvePriority.DOWNLOAD,
            botSignalAlreadySeen = false, attempted = emptySet(), previousDirectFailures = 3,
        ))
        assertNull(CapsuleSabrRoutePolicy.select(
            forceSabr = false, download = false, priority = AudioResolvePriority.PREFETCH,
            botSignalAlreadySeen = false, attempted = emptySet(), previousDirectFailures = 3,
        ))
    }

    @Test fun forcedSabrCannotRetryItselfOrActivateForDownloads() {
        assertNull(CapsuleSabrRoutePolicy.select(true, false, AudioResolvePriority.PLAYBACK,
            false, setOf(CapsuleSabrRoutePolicy.PROFILE)))
        assertNull(CapsuleSabrRoutePolicy.select(true, true, AudioResolvePriority.DOWNLOAD,
            false, emptySet()))
    }

    @Test fun automaticSabrNeverFiresDuringPrefetchOrAfterBotChallenge() {
        val attempted = setOf("WEB_REMIX", "TVHTML5_SIMPLY")
        assertNull(CapsuleSabrRoutePolicy.select(false, false, AudioResolvePriority.PREFETCH,
            false, attempted))
        assertNull(CapsuleSabrRoutePolicy.select(false, false, AudioResolvePriority.PLAYBACK,
            true, attempted))
    }
}
