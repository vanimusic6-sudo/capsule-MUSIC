@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.video

import android.app.Application
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.nikhil.yt.playback.CapsuleEqPresets
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CapsuleVideoPlayerLifecycleTest {
    @Test fun extractorUsesAndroidJsonHierarchyAndParsesPlayerResponses() {
        assertSame(Exception::class.java, org.json.JSONException::class.java.superclass)
        val player = org.json.JSONObject("""{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"abcdefghijk"}}""")
        assertEquals("OK", player.getJSONObject("playabilityStatus").getString("status"))
        assertEquals("abcdefghijk", player.getJSONObject("videoDetails").getString("videoId"))
    }
    @Test fun indexedDashUsesOneSupportedBitrateWhileHlsKeepsAdaptation() {
        val initial = androidx.media3.common.TrackSelectionParameters.Builder(RuntimeEnvironment.getApplication()).build()
        val dash = videoTrackSelectionParameters(initial, com.nikhil.yt.innertube.video.CapsuleVideoDelivery.DASH)
        org.junit.Assert.assertTrue(dash.forceHighestSupportedBitrate)
        val hls = videoTrackSelectionParameters(dash, com.nikhil.yt.innertube.video.CapsuleVideoDelivery.HLS)
        org.junit.Assert.assertFalse(hls.forceHighestSupportedBitrate)
        org.junit.Assert.assertFalse(videoTrackSelectionParameters(dash, null).forceHighestSupportedBitrate)
    }
    @Test fun actualVideoCompanionConstructsStopsAndReleasesWithMedia3() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val music = ExoPlayer.Builder(context).build()
        try {
            val video = CapsuleVideoCompanion(
                context = context,
                factory = DefaultMediaSourceFactory(context),
                audio = music,
                readStudioSound = { CapsuleEqPresets.AUTO.sound },
                scope = backgroundScope,
                onSoundOwned = {}, onReady = {}, onFailure = { _, error -> throw error },
                onEnded = {}, onFrameUnavailable = {}, onBufferingChanged = { _, _ -> },
            )
            try {
                assertEquals(Player.STATE_IDLE, video.player.playbackState)
                video.stop()
                assertEquals(Player.STATE_IDLE, video.player.playbackState)
            } finally { video.release() }
        } finally { music.release() }
    }
}
