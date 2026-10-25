package com.nikhil.yt.playback

import android.app.Application
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.imageDecoderEnabled
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.CachePolicy
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
            val failures = mutableListOf<Throwable>()
            // Robolectric's native FileSource ImageDecoder reports "Only supported on Android".
            // Exercise real JPEG bytes with Coil's Android BitmapFactory decoder, not a fake result.
            val offline = ImageLoader.Builder(context).diskCache(null).memoryCache(null)
                .imageDecoderEnabled(false)
                .components {
                    add(DownloadedArtworkInterceptor(context))
                    add(Interceptor { chain ->
                        chain.proceed().also { result ->
                            if (result is coil3.request.ErrorResult) failures += result.throwable
                        }
                    })
                }.build()
            try {
                val result = offline.execute(ImageRequest.Builder(context)
                    .data("https://lh3.googleusercontent.com/offline-cover=w128-h128-p-l90-rj")
                    .networkCachePolicy(CachePolicy.DISABLED).build())
                assertTrue(failures.joinToString("\n") { it.stackTraceToString() }, result is SuccessResult)
                assertEquals(DataSource.DISK, (result as SuccessResult).dataSource)
            } finally { offline.shutdown() }
            DownloadedArtwork.remove(context, "first")
            assertTrue("Shared cover belongs to the other saved song", DownloadedArtwork.file(context, url).isFile)
            DownloadedArtwork.remove(context, "second")
            assertFalse(DownloadedArtwork.file(context, url).exists())
        } finally { loader.shutdown() }
    }

    @Test fun upgradesLegacyTinyGoogleCoverToCompact640pxAndAvoidsRepeatedFetch() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val url = "https://lh3.googleusercontent.com/artwork-upgrade-test=w120-h120-p-l90-rj"
        val target = DownloadedArtwork.file(context, url)
        target.parentFile!!.mkdirs()
        val tiny = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        target.outputStream().use { out -> tiny.compress(Bitmap.CompressFormat.JPEG, 85, out) }
        tiny.recycle()
        assertTrue(DownloadedArtwork.valid(target))
        assertTrue(DownloadedArtwork.needsUpgrade(target, url))
        val calls = AtomicInteger()
        val seen = mutableListOf<String>()
        val loader = ImageLoader.Builder(context).diskCache(null).components {
            add(Interceptor { chain ->
                calls.incrementAndGet()
                seen += chain.request.data.toString()
                SuccessResult(Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).asImage(),
                    chain.request, DataSource.NETWORK)
            })
        }.build()
        try {
            assertTrue(DownloadedArtwork.save(context, "upgrade-test", url, loader))
            assertEquals(1, calls.get())
            assertTrue("The actual CDN URL must be resized, not just Coil's decoder",
                seen.single().contains("=w640-h640"))
            assertFalse(DownloadedArtwork.needsUpgrade(target, url))
            assertTrue(target.length() in 1L..320_000L)
            assertEquals(640, android.graphics.BitmapFactory.Options().run {
                inJustDecodeBounds = true
                android.graphics.BitmapFactory.decodeFile(target.path, this)
                outWidth
            })
            assertTrue(DownloadedArtwork.save(context, "upgrade-test", url, loader))
            assertEquals(1, calls.get())
            DownloadedArtwork.remove(context, "upgrade-test")
        } finally { loader.shutdown(); target.delete() }
    }

    @Test fun onlyApprovedGoogleArtworkUrlsAreResized() {
        val google = "https://lh3.googleusercontent.com/a=s120"
        assertEquals("https://lh3.googleusercontent.com/a=s640", DownloadedArtwork.optimizedUrl(google))
        val external = "https://example.com/picture?w=120&token=x"
        assertEquals(external, DownloadedArtwork.optimizedUrl(external))
    }

    @Test fun corruptAndPartialCoversAreNeverAcceptedAndFilenamesCannotEscapeStorage(): Unit = runBlocking {
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
