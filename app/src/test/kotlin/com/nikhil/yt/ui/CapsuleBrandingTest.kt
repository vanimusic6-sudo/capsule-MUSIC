package com.nikhil.yt.ui

import android.app.Application
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.InsetDrawable
import kotlin.math.hypot
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
            assertTrue((icon.foreground as InsetDrawable).drawable is BitmapDrawable)
            assertTrue((icon.monochrome as InsetDrawable).drawable is VectorDrawable)
        }
    }

    @Test fun launcherOrbitFitsInsideTheSafeCircleBeforeAnyOemMask() {
        for (resource in listOf(R.drawable.ic_capsule_launcher_foreground, R.drawable.ic_capsule_launcher_monochrome)) {
            val drawable = requireNotNull(context.getDrawable(resource))
            val bitmap = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, 108, 108)
            drawable.draw(Canvas(bitmap))
            var orbitPixels = 0
            for (y in 0 until 108) for (x in 0 until 108) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) > 128 && Color.red(pixel) > 180) {
                    orbitPixels++
                    assertTrue("Orbit clipped by a round launcher at ($x, $y)", hypot(x - 53.5, y - 53.5) <= 33.5)
                }
            }
            assertTrue("Orbit must be visible", orbitPixels > 100)
        }
    }

    @Test fun notificationAliasesRemainAlphaOnlyVectors() {
        val metadata = context.packageManager.getApplicationInfo(
            context.packageName, android.content.pm.PackageManager.GET_META_DATA,
        ).metaData
        assertEquals(R.drawable.ic_capsule_monochrome, metadata.getInt("androidx.media3.session.default_notification_icon"))
        for (resource in listOf(R.drawable.small_icon, R.drawable.media3_notification_small_icon, R.drawable.ic_capsule_monochrome)) {
            assertTrue(context.getDrawable(resource) is VectorDrawable)
        }
    }
}
