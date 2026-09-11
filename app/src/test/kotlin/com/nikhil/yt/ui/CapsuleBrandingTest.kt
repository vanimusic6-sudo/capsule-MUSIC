package com.nikhil.yt.ui

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.VectorDrawable
import com.nikhil.yt.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CapsuleBrandingTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun composeBrandAliasesResolveToTheOriginalRaster() {
        for (resource in listOf(R.drawable.ic_velune_concept, R.drawable.app_icon_small, R.drawable.about_splash)) {
            val bitmap = (context.getDrawable(resource) as BitmapDrawable).bitmap
            assertEquals(1024, bitmap.width)
            assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
            // The source contains a bright centre and a graphite body, not a flat tint mask.
            assertTrue(Color.red(bitmap.getPixel(512, 512)) > 180)
            assertTrue(Color.red(bitmap.getPixel(512, 180)) in 15..70)
        }
    }

    @Test fun bothAdaptiveLaunchersUseTheNewArtwork() {
        for (resource in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
            val icon = context.getDrawable(resource) as AdaptiveIconDrawable
            assertTrue(icon.foreground is BitmapDrawable)
            assertTrue(icon.monochrome is VectorDrawable)
        }
    }

    @Test fun notificationAliasesRemainAlphaOnlyVectors() {
        for (resource in listOf(R.drawable.small_icon, R.drawable.media3_notification_small_icon, R.drawable.ic_capsule_monochrome)) {
            assertTrue(context.getDrawable(resource) is VectorDrawable)
        }
    }
}
