package com.nikhil.yt.playback.audio

import com.nikhil.yt.playback.AUDIO_CDN_IDLE_CONNECTIONS
import com.nikhil.yt.playback.AUDIO_CDN_IDLE_CONNECTION_SECONDS
import com.nikhil.yt.playback.AUDIO_CDN_TIMEOUT_SECONDS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shortest googlevideo connection age at which a capture has seen the server hang up. */
private const val OBSERVED_SERVER_HANGUP_SECONDS = 61L

/**
 * This app must let go of an idle googlevideo socket before googlevideo does.
 *
 * A capture caught two ConnectionResetExceptions on connections aged 61.1 s and 62.4 s, and none
 * below that, against OkHttp's five-minute default. Holding a socket the far end has already
 * closed never saves a handshake: it spends a failed round trip finding out, and then does the
 * handshake anyway — on exactly the resume-after-a-pause path that has been the complaint from
 * the start.
 */
class AudioCdnIdleConnectionTest {
    @Test fun `an idle socket is dropped before the server would drop it`() {
        assertTrue(
            "keeping a socket for $AUDIO_CDN_IDLE_CONNECTION_SECONDS s outlives the server",
            AUDIO_CDN_IDLE_CONNECTION_SECONDS < OBSERVED_SERVER_HANGUP_SECONDS,
        )
    }

    @Test fun `there is real margin, not a value tuned to the exact observation`() {
        // Two samples do not locate a server timeout to the second. Ten seconds of room means a
        // server that hangs up a little sooner than observed still never catches us.
        assertTrue(
            "no margin under the observed hangup",
            OBSERVED_SERVER_HANGUP_SECONDS - AUDIO_CDN_IDLE_CONNECTION_SECONDS >= 10L,
        )
    }

    @Test fun `the pool still outlives a single request`() {
        // Shorter than one request's own allowance would evict connections mid-track and undo
        // the reuse this whole line of work exists to protect.
        assertTrue(
            "idle time is shorter than one request may legitimately take",
            AUDIO_CDN_IDLE_CONNECTION_SECONDS > AUDIO_CDN_TIMEOUT_SECONDS,
        )
    }

    @Test fun `more than one socket may be kept`() {
        // A track's slices reuse one socket, but a track change overlaps two hosts.
        assertTrue(AUDIO_CDN_IDLE_CONNECTIONS >= 2)
    }
}

/**
 * A read is only evidence about the server when it asked for enough to be evidence.
 *
 * A capture raised eight slow-read warnings and not one was a slow server: the reads asked for
 * 19, 54, 152, 211, 248, 249, 313 and 327 bytes. Waiting a quarter of a second for a few dozen
 * bytes describes when the player wanted them, not how fast the far end can send.
 */
class SlowAudioReadTest {
    @Test fun `the tail-end dribbles that cried wolf no longer do`() {
        for (requested in listOf(19, 54, 152, 211, 248, 249, 313, 327)) {
            assertFalse(
                "a $requested byte read was called slow",
                isSlowAudioRead(readMs = 500L, requestedBytes = requested),
            )
        }
    }

    @Test fun `a full buffer that dribbles back is still caught`() {
        // The throttled stream this warning exists for: a real buffer asked for, a long wait.
        assertTrue(isSlowAudioRead(readMs = 250L, requestedBytes = 64 * 1024))
    }

    @Test fun `a fast read is never slow, however little it moved`() {
        assertFalse(isSlowAudioRead(readMs = 0L, requestedBytes = 64 * 1024))
        assertFalse(isSlowAudioRead(readMs = 249L, requestedBytes = 1024 * 1024))
    }

    @Test fun `both halves are required`() {
        assertFalse(isSlowAudioRead(readMs = 10_000L, requestedBytes = 8))
        assertFalse(isSlowAudioRead(readMs = 1L, requestedBytes = 1024 * 1024))
        assertTrue(isSlowAudioRead(readMs = 10_000L, requestedBytes = 1024 * 1024))
    }
}
