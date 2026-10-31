package com.nikhil.yt.playback

import android.app.Application
import android.graphics.Bitmap
import com.nikhil.yt.utils.CoilBitmapLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class NativeMediaBitmapLoaderTest {
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
