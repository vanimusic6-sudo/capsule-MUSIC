package com.nikhil.yt.playback.audio

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CachedAudioDownloaderTest {
    @get:Rule val temp = TemporaryFolder()

    private fun write(cache: Cache, key: String, bytes: ByteArray, length: Long = bytes.size.toLong()) {
        val hole = cache.startReadWrite(key, 0, bytes.size.toLong())
        try {
            val file = cache.startFile(key, 0, bytes.size.toLong())
            file.writeBytes(bytes)
            cache.commitFile(file, bytes.size.toLong())
            AudioCacheIdentity.setLength(cache, key, length)
        } finally { cache.releaseHoleSpan(hole) }
    }

    private fun withCaches(block: (Cache, Cache) -> Unit) {
        val database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        val source = SimpleCache(temp.newFolder(), NoOpCacheEvictor(), database)
        val target = SimpleCache(temp.newFolder(), NoOpCacheEvictor(), database)
        try { block(source, target) } finally {
            source.release(); target.release(); database.close()
        }
    }

    @Test fun completePlaybackCacheIsSavedWithoutResolverAdmissionOrNetwork() = withCaches { source, target ->
        val bytes = ByteArray(200_000) { (it % 127).toByte() }
        val key = "capsule:audio:track:251:${bytes.size}"
        write(source, key, bytes)
        var cover = false
        var complete = false
        val downloader = ResolvingAudioDownloader(
            "track", target, { error("Must not resolve a fully cached song") }, { error("Must not create network") },
            saveArtwork = { cover = true }, awaitAdmission = { error("Must not wait for network admission") },
            createCachedDownloader = { createCachedAudioDownloader("track", source, target) },
        )
        downloader.download { _, _, percent -> if (percent == 100f) { assertTrue(cover); complete = true } }
        assertTrue(complete)
        assertTrue(AudioCacheIdentity.isComplete(target, "track"))
        val reader = CacheDataSource.Factory().setCache(target).setUpstreamDataSourceFactory(null).createDataSource()
        val actual = java.io.ByteArrayOutputStream()
        try {
            reader.open(DataSpec.Builder().setUri(Uri.parse("capsule-cache:track")).setKey("track").build())
            val buffer = ByteArray(8_192)
            while (true) {
                val count = reader.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                actual.write(buffer, 0, count)
            }
        } finally { reader.close() }
        assertArrayEquals(bytes, actual.toByteArray())
    }

    @Test fun partialPlaybackCacheCannotMasqueradeAsOfflineDownload() = withCaches { source, target ->
        write(source, "capsule:audio:track:140:1000", ByteArray(100), length = 1_000)
        assertNull(createCachedAudioDownloader("track", source, target))
        assertFalse(AudioCacheIdentity.isComplete(target, "track"))
    }

    @Test fun differentPartialDownloadEncodingIsDiscardedBeforeCopy() = withCaches { source, target ->
        val bytes = ByteArray(1_000) { 7 }
        AudioCacheIdentity.prepareDownload(target, "track", "capsule:audio:track:140:1000", 1_000)
        write(target, "track", ByteArray(100) { 2 }, length = 1_000)
        write(source, "capsule:audio:track:251:1000", bytes)
        val downloader = createCachedAudioDownloader("track", source, target)!!
        downloader.download(null)
        assertTrue(AudioCacheIdentity.isComplete(target, "track"))
        // The cached downloader's upstream is cache-only: a format mismatch
        // cannot fall through to a new signed URL or concatenate encodings.
        assertEquals(1_000L, target.getCacheSpace())
    }

    @Test fun cancelledCopyCannotStartResolverOrReportCompletion() = withCaches { source, target ->
        write(source, "capsule:audio:track:251:1000", ByteArray(1_000))
        val downloader = ResolvingAudioDownloader(
            "track", target, { error("cancelled") }, { error("cancelled") },
            createCachedDownloader = { error("Cancellation must be checked before opening cached data") },
        )
        downloader.cancel()
        assertThrows(InterruptedException::class.java) { downloader.download(null) }
        assertFalse(AudioCacheIdentity.isComplete(target, "track"))
    }
}
