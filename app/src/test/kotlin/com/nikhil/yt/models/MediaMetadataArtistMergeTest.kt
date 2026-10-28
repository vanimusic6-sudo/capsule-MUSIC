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
    fun `previously cached metrics are not shown as artists in the player`() {
        val old = listOf(
            MediaMetadata.Artist("UC_mzlff", "MZLFF"),
            MediaMetadata.Artist("UC_sted", "STED.D"),
            MediaMetadata.Artist(null, "1,3 млн прослушиваний"),
        )
        assertEquals(old.take(2), old.sanitizedArtistCredits())
        assertEquals("MZLFF, STED.D", old.artistCreditLine())
        assertEquals(old.take(2), mergeArtistCredits(old, old))
    }

    @Test
    fun `a numeric artist with a real channel ID remains a legitimate artist`() {
        val real = MediaMetadata.Artist("UC_numeric", "42M Views")
        val junk = MediaMetadata.Artist(null, "42M Views")
        assertEquals(listOf(real), mergeArtistCredits(listOf(real), listOf(junk)))
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
