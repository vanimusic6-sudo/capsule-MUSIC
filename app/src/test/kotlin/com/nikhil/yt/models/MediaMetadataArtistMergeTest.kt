package com.nikhil.yt.models

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaMetadataArtistMergeTest {
    @Test
    fun `Room credits enrich a partial live metadata list`() {
        val live =
            listOf(
                MediaMetadata.Artist(
                    id = "UC_primary",
                    name = "Primary",
                ),
            )
        val room =
            listOf(
                MediaMetadata.Artist(
                    id = "UC_primary",
                    name = "Primary",
                    thumbnailUrl = "portrait",
                ),
                MediaMetadata.Artist(
                    id = null,
                    name = "Featured Guest",
                ),
            )

        assertEquals(
            listOf(
                MediaMetadata.Artist(
                    id = "UC_primary",
                    name = "Primary",
                    thumbnailUrl = "portrait",
                ),
                MediaMetadata.Artist(
                    id = null,
                    name = "Featured Guest",
                ),
            ),
            mergeArtistCredits(live, room),
        )
    }

    @Test
    fun `real browse id upgrades a name-only credit`() {
        val merged =
            mergeArtistCredits(
                primary = listOf(MediaMetadata.Artist(id = null, name = "Artist")),
                secondary = listOf(MediaMetadata.Artist(id = "UC_artist", name = "Artist")),
            )

        assertEquals("UC_artist", merged.single().id)
    }
}
