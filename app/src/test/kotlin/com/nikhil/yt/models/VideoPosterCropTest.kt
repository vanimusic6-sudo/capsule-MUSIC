package com.nikhil.yt.models

import com.nikhil.yt.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPosterCropTest {
    @Test fun `original video poster keeps first displayed thumbnail URL`() {
        val original = "https://i.ytimg.com/vi/abc123/hqdefault.jpg"
        val item = SongItem(
            id = "abc123", title = "Video track",
            artists = emptyList(), thumbnail = original,
            selectedFromVideoResults = true,
        )
        val metadata = item.toMediaMetadata()
        assertTrue(metadata.isOriginalVideo)
        assertEquals(original, metadata.thumbnailUrl)
    }
}
