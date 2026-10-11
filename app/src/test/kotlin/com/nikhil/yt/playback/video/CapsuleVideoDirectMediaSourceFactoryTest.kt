@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.video

import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.nikhil.yt.innertube.video.CapsuleVideoDelivery
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CapsuleVideoDirectMediaSourceFactoryTest {
    private val videoId = "BX0lKSa_PTk"
    private val item = MediaItem.Builder()
        .setMediaId("music-id")
        .setUri("capsule-video://play/$videoId")
        .build()

    private fun resolved(adaptive: Boolean) = YouTubeVideoResolver.ResolvedVideo(
        sourceMediaId = "music-id",
        videoId = videoId,
        videoStreamUrl = "https://rr.example.test/videoplayback?itag=18",
        videoFormat = YouTubeVideoResolver.StreamFormat(
            itag = 18, width = 640, height = 360, qualityLabel = "360p",
        ),
        audioStreamUrl = if (adaptive) "https://rr.example.test/videoplayback?itag=140" else null,
        audioFormat = if (adaptive) YouTubeVideoResolver.StreamFormat(
            itag = 140, width = null, height = null, qualityLabel = null,
        ) else null,
        expiresAtMs = System.currentTimeMillis() + 600_000L,
    )

    @Test fun muxedUsesOneDirectProgressiveMediaSourceWithNoNetworkResolve() {
        val factory = CapsuleVideoDirectMediaSourceFactory(
            DataSource.Factory { error("No DataSource should be opened in createMediaSource") },
        ) { id ->
            check(id == videoId)
            resolved(false)
        }
        assertTrue(factory.createMediaSource(item) is ProgressiveMediaSource)
    }

    @Test fun adaptiveMergesOnlyTwoPreparedSignedUrls() {
        val factory = CapsuleVideoDirectMediaSourceFactory(
            DataSource.Factory { error("No DataSource should be opened in createMediaSource") },
        ) { id ->
            check(id == videoId)
            resolved(true)
        }
        assertTrue(factory.createMediaSource(item) is MergingMediaSource)
    }

    @Test(expected = IllegalStateException::class)
    fun missingResolvedUrlFailsBeforeStartingVideoTransport() {
        val factory = CapsuleVideoDirectMediaSourceFactory(
            DataSource.Factory { error("Unexpected VIDEO CDN request") },
        ) { null }
        factory.createMediaSource(item)
    }

    @Test fun pinnedSessionDoesNotReReadOrSubstituteLatestQualityCache() {
        val factory = CapsuleVideoDirectMediaSourceFactory(
            DataSource.Factory { error("Unexpected CDN read") },
        ) { error("Pinned VIDEO session must not read a mutable latest-format cache") }
        assertTrue(factory.createMediaSource(item.buildUpon().setTag(resolved(false)).build()) is ProgressiveMediaSource)
    }

    @Test fun hlsUsesNativeSegmentedSourceInsteadOfProgressiveOrManualMerge() {
        val factory = CapsuleVideoDirectMediaSourceFactory(
            upstream = DataSource.Factory { error("Progressive Range transport must not open an HLS manifest") },
            hlsUpstream = DataSource.Factory { error("Source construction must not start network I/O") },
            findResolved = { error("Pinned HLS session must not re-resolve") },
        )
        val stream = resolved(false).copy(
            videoStreamUrl = "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/9999999999/file/index.m3u8",
            delivery = CapsuleVideoDelivery.HLS, maxHeight = 480,
        )
        assertTrue(factory.createMediaSource(item.buildUpon().setTag(stream).build()) is HlsMediaSource)
    }
}
