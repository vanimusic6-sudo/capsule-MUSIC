package com.nikhil.yt.ui

import androidx.compose.ui.graphics.Color
import com.nikhil.yt.ui.player.ArtworkPaletteCache
import com.nikhil.yt.ui.player.capsuleArtworkPaletteKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ArtworkPaletteCacheTest {
    private val palette = listOf(Color.Red, Color.Green, Color.Blue)

    @Test fun simultaneousPlayerAndMiniPlayerDecodeTheSameTrackOnlyOnce() = runTest {
        val cache = ArtworkPaletteCache()
        val release = CompletableDeferred<Unit>()
        var decodes = 0
        val key = capsuleArtworkPaletteKey("song")
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrExtract(key) { decodes++; release.await(); palette }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrExtract(key) { decodes++; listOf(Color.Black) }
        }
        release.complete(Unit)
        assertEquals(palette, first.await())
        assertEquals(palette, second.await())
        assertEquals(1, decodes)
        assertEquals(palette, cache.getOrExtract(key) { error("A resolved track must keep its palette") })
    }

    @Test fun cancellationAndDecodeFailureDoNotPoisonTheTrackOrHideUnexpectedErrors() = runTest {
        val cache = ArtworkPaletteCache()
        val cancellation = CancellationException("leaving player")
        try {
            cache.getOrExtract("track") { throw cancellation }
            fail("Cancellation must escape")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
        assertNull(cache.get("track"))
        assertNull(cache.getOrExtract("track") { null })
        val failure = IllegalStateException("bad decoder")
        try {
            cache.getOrExtract("track") { throw failure }
            fail("Unexpected failure must escape the cache")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
        }
        assertNull(cache.get("track"))
        assertEquals(palette, cache.getOrExtract("track") { palette })
    }

    @Test fun cacheIsBoundedAndRecentlyUsedPalettesSurviveBrowsing() {
        val cache = ArtworkPaletteCache(maxEntries = 2)
        cache.put("playing", palette)
        cache.put("album", listOf(Color.Gray))
        cache.get("playing")
        cache.put("next", listOf(Color.White))
        assertNull(cache.get("album"))
        assertEquals(palette, cache.get("playing"))
        assertNotNull(cache.get("next"))
    }
}
