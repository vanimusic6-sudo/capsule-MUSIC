package com.nikhil.yt.playback

import android.app.Application
import android.graphics.Bitmap
import com.nikhil.yt.utils.CoilBitmapLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.media3.session.CacheBitmapLoader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeMediaBitmapLoaderTest {
    private fun image(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val bytes = ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
        bitmap.recycle()
        return bytes.toByteArray()
    }

    @Test fun failedArtworkDoesNotCancelTheServiceOwnerLikesOrSubsequentArtwork() {
        // Production used a plain Job, whereas the old test used SupervisorJob and masked this
        // failure. A failed Future must report its error without cancelling unrelated work.
        val owner = Job()
        val scope = CoroutineScope(owner + Dispatchers.Default)
        try {
            val cache = CacheBitmapLoader(CoilBitmapLoader(RuntimeEnvironment.getApplication(), scope))
            val first = cache.decodeBitmap(image(android.graphics.Color.RED)).get(10, TimeUnit.SECONDS)
            assertEquals(android.graphics.Color.RED, first.getPixel(16,16))
            try {
                cache.decodeBitmap(byteArrayOf(1,2,3)).get(10, TimeUnit.SECONDS)
                fail("invalid artwork must fail its Future")
            } catch (expected: ExecutionException) {
                assertTrue(expected.cause is IllegalArgumentException)
            }
            assertTrue("one artwork failure must not cancel the service owner", owner.isActive)
            val likeWork = CountDownLatch(1)
            scope.launch { likeWork.countDown() }
            assertTrue("subsequent like/background work must still run", likeWork.await(5, TimeUnit.SECONDS))
            val second = cache.decodeBitmap(image(android.graphics.Color.BLUE)).get(10, TimeUnit.SECONDS)
            assertEquals("the next track must publish its own bitmap", android.graphics.Color.BLUE, second.getPixel(16,16))
        } finally { scope.cancel() }
    }

    @Test fun destroyingTheOwnerStillCancelsNewArtworkRequests() {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val loader = CoilBitmapLoader(RuntimeEnvironment.getApplication(), scope)
        scope.cancel()
        val future = loader.decodeBitmap(image(android.graphics.Color.GREEN))
        try {
            future.get(10, TimeUnit.SECONDS)
            fail("a destroyed service must not decode another cover")
        } catch (expected: CancellationException) {
            assertTrue(future.isCancelled)
        }
    }

    @Test fun oversizedDecodedArtworkIsBoundedBeforeEnteringPlatformMediaSession() {
        // A 2048x1024 image should never be forwarded unscaled to the OEM
        // MediaStyle notification or transferred over Android Binder.
        val original = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
        val encoded = ByteArrayOutputStream()
        assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, encoded))
        original.recycle()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val loader = CoilBitmapLoader(RuntimeEnvironment.getApplication(), scope)
            val output = loader.decodeBitmap(encoded.toByteArray()).get(10, TimeUnit.SECONDS)
            assertTrue("Platform artwork must stay under 512px", output.width <= 512)
            assertTrue("Platform artwork must stay under 512px", output.height <= 512)
            assertEquals(Bitmap.Config.ARGB_8888, output.config)
        } finally {
            scope.cancel()
        }
    }
}
