package com.nikhil.yt.ui.screens.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistMemorialLookupTest {
    private fun artist(name: String, type: String, ended: Boolean, end: String, id: String) = """
        {"name":"$name","type":"$type","score":100,
         "id":"$id","life-span":{"ended":$ended,"end":"$end"}}
    """.trimIndent()

    @Test
    fun verifiedDeceasedIndividualShowsCandle() {
        val json = """{"artists":[${artist("Oliver Tree", "Person", true, "2026-06-14", "1fa3bded-908f-47be-9784-3c9ef850682b")}]}"""
        val result = ArtistMemorialLookup.parseDeath(json, "Oliver Tree")
        assertEquals("2026-06-14", result?.deathDate)
        assertEquals(
            "https://musicbrainz.org/artist/1fa3bded-908f-47be-9784-3c9ef850682b",
            result?.sourceUrl,
        )
    }

    @Test
    fun livingArtistHasNoCandle() {
        val json = """{"artists":[${artist("Example", "Person", false, "", "12345678-1234-1234-1234-123456789012")}]}"""
        assertNull(ArtistMemorialLookup.parseDeath(json, "Example"))
    }

    @Test
    fun disbandedBandIsNotMistakenForDeceasedPerson() {
        val json = """{"artists":[${artist("Example", "Group", true, "2002", "12345678-1234-1234-1234-123456789012")}]}"""
        assertNull(ArtistMemorialLookup.parseDeath(json, "Example"))
    }

    @Test
    fun ambiguousSameNameDoesNotShowCandle() {
        val item1 = artist("Example", "Person", true, "1998", "12345678-1234-1234-1234-123456789012")
        val item2 = artist("Example", "Person", false, "", "12345678-1234-1234-1234-123456789013")
        assertNull(ArtistMemorialLookup.parseDeath("""{"artists":[$item1,$item2]}""", "Example"))
    }

    @Test
    fun partialOrLowConfidenceResultsCannotClaimDeath() {
        val json = """{"artists":[{"name":"Example Tribute","type":"Person","score":99,
            "id":"12345678-1234-1234-1234-123456789012",
            "life-span":{"ended":true,"end":"2000-01-01"}}]}"""
        assertNull(ArtistMemorialLookup.parseDeath(json, "Example"))
        assertNull(ArtistMemorialLookup.parseDeath("{bad json", "Example"))
    }
}
