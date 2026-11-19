package com.nikhil.yt.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SimpMusicSourceRegressionTest {
    @Test fun onlyActualSyncedTextWinsOverOtherCatalogs() {
        assertTrue(SimpMusicLyricsProvider.isRenderableSync("[00:14.52]Some words"))
        assertTrue(SimpMusicLyricsProvider.isRenderableSync("<tt xmlns=\"http://www.w3.org/ns/ttml\">"))
        assertFalse(SimpMusicLyricsProvider.isRenderableSync("[{\"text\":\"hello\",\"start\":12}]"))
        assertFalse(SimpMusicLyricsProvider.isRenderableSync("[]"))
    }

    @Test fun manualPickerDoesNotWaitForOtherProvidersOrPersistStalePartialCache() {
        val path = "src/main/kotlin/com/nikhil/yt/lyrics/LyricsHelper.kt"
        val file = listOf(File(path), File("app/$path")).firstOrNull(File::isFile)
            ?: error("LyricsHelper source unavailable")
        val source = file.readText()
        assertTrue(source.contains("supervisorScope {"))
        assertTrue(source.contains("async(Dispatchers.IO)"))
        assertTrue(source.contains("withTimeoutOrNull(MANUAL_PROVIDER_TIMEOUT_MS)"))
        assertFalse(source.contains("cache.get(cacheKey)?.let"))
    }
}
