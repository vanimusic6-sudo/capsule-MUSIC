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
    fun `legacy conjunction credit is removed during merge`() {
        val merged =
            mergeArtistCredits(
                primary =
                    listOf(
                        MediaMetadata.Artist("UC_suburban", "Sub Urban"),
                        MediaMetadata.Artist(null, "и"),
                        MediaMetadata.Artist(null, "Bella Poarch"),
                    ),
                secondary = emptyList(),
            )

        assertEquals(
            listOf(
                MediaMetadata.Artist("UC_suburban", "Sub Urban"),
                MediaMetadata.Artist(null, "Bella Poarch"),
            ),
            merged,
        )
    }

    @Test
    fun `artist credit line removes conjunction rows and uses commas`() {
        val line =
            listOf(
                MediaMetadata.Artist("UC_suburban", "Sub Urban"),
                MediaMetadata.Artist(null, "и"),
                MediaMetadata.Artist(null, "&"),
                MediaMetadata.Artist(null, "Bella Poarch"),
            ).artistCreditLine()

        assertEquals("Sub Urban, Bella Poarch", line)
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
