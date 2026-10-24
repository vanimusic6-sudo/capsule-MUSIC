package com.nikhil.yt.playback

import android.app.Application
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadedArtworkTest {
    @Test fun coverIsPersistedAndSharedByResizedRequestsEvenWithNoNetworkOrCoilDiskCache() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val url = "https://lh3.googleusercontent.com/offline-cover=w544-h544-p-l90-rj"
        val calls = AtomicInteger()
        val loader = ImageLoader.Builder(context).diskCache(null).components {
            add(Interceptor { chain ->
                calls.incrementAndGet()
                SuccessResult(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).asImage(), chain.request, DataSource.MEMORY_CACHE)
            })
        }.build()
        try {
            assertTrue(DownloadedArtwork.save(context, "first", url, loader))
            assertTrue(DownloadedArtwork.save(context, "second", url, loader))
            assertEquals(1, calls.get())
            assertTrue(DownloadedArtwork.valid(DownloadedArtwork.file(context, url)))
            val offline = ImageLoader.Builder(context).diskCache(null)
                .components { add(DownloadedArtworkInterceptor(context)) }.build()
            try {
                val result = offline.execute(ImageRequest.Builder(context)
                    .data("https://lh3.googleusercontent.com/offline-cover=w128-h128-p-l90-rj").build())
                assertTrue(result is SuccessResult)
            } finally { offline.shutdown() }
            DownloadedArtwork.remove(context, "first")
            assertTrue("Shared cover belongs to the other saved song", DownloadedArtwork.file(context, url).isFile)
            DownloadedArtwork.remove(context, "second")
            assertFalse(DownloadedArtwork.file(context, url).exists())
        } finally { loader.shutdown() }
    }

    @Test fun corruptAndPartialCoversAreNeverAcceptedAndFilenamesCannotEscapeStorage() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val url = "https://example.com/../../cover.jpg?secret=private"
        val target = DownloadedArtwork.file(context, url)
        assertTrue(target.name.matches(Regex("[0-9a-f]{64}\\.jpg")))
        target.parentFile!!.mkdirs(); target.writeText("not an image")
        assertFalse(DownloadedArtwork.valid(target))
        assertFalse(DownloadedArtwork.save(context, "../bad", "file:///etc/passwd"))
        target.delete()
    }
}
