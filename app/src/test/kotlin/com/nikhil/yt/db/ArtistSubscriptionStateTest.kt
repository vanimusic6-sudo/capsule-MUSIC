package com.nikhil.yt.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArtistSubscriptionStateTest {
    @Before
    fun setUp() = ArtistSubscriptionState.resetForTests()

    @After
    fun tearDown() = ArtistSubscriptionState.resetForTests()

    @Test
    fun freshSubscribeSurvivesStaleRemoteAbsence() {
        ArtistSubscriptionState.recordLocalIntent(
            artistId = "FEmusic_artist",
            channelId = "UC123",
            subscribed = true,
            nowMs = 1_000L,
        )

        assertFalse(
            ArtistSubscriptionState.shouldApplyRemoteAbsence(
                artistId = "FEmusic_artist",
                channelId = "UC123",
                nowMs = 1_001L,
            ),
        )
        assertEquals(
            true,
            ArtistSubscriptionState.pendingDesiredState("UC123", null, nowMs = 1_001L),
        )
    }

    @Test
    fun twoRemoteAbsencesAreRequiredBeforeRemovingEstablishedSubscription() {
        assertFalse(
            ArtistSubscriptionState.shouldApplyRemoteAbsence(
                artistId = "UC123",
                channelId = null,
                nowMs = 1_000L,
            ),
        )
        assertTrue(
            ArtistSubscriptionState.shouldApplyRemoteAbsence(
                artistId = "UC123",
                channelId = null,
                nowMs = 2_000L,
            ),
        )
    }

    @Test
    fun remotePresenceResetsMissingSnapshotCounter() {
        assertFalse(ArtistSubscriptionState.shouldApplyRemoteAbsence("UC123", null, 1_000L))
        ArtistSubscriptionState.observeRemotePresence("UC123", null)
        assertFalse(ArtistSubscriptionState.shouldApplyRemoteAbsence("UC123", null, 2_000L))
    }

    @Test
    fun staleRemotePresenceDoesNotBeatFreshLocalUnsubscribe() {
        ArtistSubscriptionState.recordLocalIntent(
            artistId = "FEmusic_artist",
            channelId = "UC123",
            subscribed = false,
            nowMs = 1_000L,
        )

        ArtistSubscriptionState.confirmRemoteState(
            artistId = "UC123",
            channelId = null,
            subscribed = true,
            nowMs = 1_001L,
        )

        assertEquals(
            false,
            ArtistSubscriptionState.pendingDesiredState("FEmusic_artist", "UC123", 1_002L),
        )

        ArtistSubscriptionState.confirmRemoteState(
            artistId = "UC123",
            channelId = null,
            subscribed = false,
            nowMs = 1_003L,
        )
        assertNull(ArtistSubscriptionState.pendingDesiredState("FEmusic_artist", "UC123", 1_004L))
    }

    @Test
    fun browseAndChannelAliasesIdentifyTheSameArtist() {
        assertTrue(
            ArtistSubscriptionState.identitiesMatch(
                localId = "FEmusic_artist",
                localChannelId = "UC123",
                remoteId = "UC123",
                remoteChannelId = null,
            ),
        )
    }

    @Test
    fun expiredLocalIntentYieldsBackToAuthenticatedRemoteState() {
        ArtistSubscriptionState.recordLocalIntent(
            artistId = "UC123",
            channelId = null,
            subscribed = true,
            nowMs = 0L,
        )

        assertNull(
            ArtistSubscriptionState.pendingDesiredState(
                artistId = "UC123",
                channelId = null,
                nowMs = 5 * 60_000L,
            ),
        )
    }
}
