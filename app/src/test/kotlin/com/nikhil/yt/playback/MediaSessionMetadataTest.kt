package com.nikhil.yt.playback

import androidx.media3.common.MediaMetadata as PlatformMediaMetadata
import com.nikhil.yt.extensions.toMediaItem
import com.nikhil.yt.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ArchiveTune parity contract: the item handed to MediaLibrarySession must
 * contain complete, playable platform metadata, not just an in-app tag.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MediaSessionMetadataTest {
    @Test
    fun standardTrackPublishesPlayableTitleArtistAndArtworkForSystemUI() {
        val item = MediaMetadata(
            id = "a1b2c3d4e5f",
            title = "Android media card",
            artists = listOf(MediaMetadata.Artist(id = "UCexample", name = "Example Artist")),
            duration = 212,
            thumbnailUrl = "https://i.ytimg.com/vi/a1b2c3d4e5f/hqdefault.jpg",
        ).toMediaItem()

        assertTrue("SystemUI must see this as a playable media item", item.mediaMetadata.isPlayable == true)
        assertEquals(PlatformMediaMetadata.MEDIA_TYPE_MUSIC, item.mediaMetadata.mediaType)
        assertEquals("Android media card", item.mediaMetadata.title?.toString())
        assertEquals("Example Artist", item.mediaMetadata.artist?.toString())
        assertEquals("Example Artist", item.mediaMetadata.subtitle?.toString())
        assertTrue(item.mediaMetadata.artworkUri?.scheme == "https")
        assertEquals("a1b2c3d4e5f", item.mediaId)
    }
}
