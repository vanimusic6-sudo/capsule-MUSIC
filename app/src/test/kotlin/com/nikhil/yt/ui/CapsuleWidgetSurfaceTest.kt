package com.nikhil.yt.ui

import android.app.Application
import android.graphics.Color
import com.nikhil.yt.ui.widget.CAPSULE_WIDGET_INK
import com.nikhil.yt.ui.widget.capsuleWidgetSurface
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A widget's panel is always a dark tint of the artwork, never the artwork's loudest colour.
 *
 * The old rule used the dominant swatch raw, so a widget came out as a large saturated block — a
 * lime panel for one album, hot pink for the next — each of them the brightest thing on the home
 * screen and each burning an OLED panel all day to be there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CapsuleWidgetSurfaceTest {
    private fun brightness(argb: Int): Float {
        val red = Color.red(argb) / 255f
        val green = Color.green(argb) / 255f
        val blue = Color.blue(argb) / 255f
        return maxOf(red, maxOf(green, blue))
    }

    @Test fun everyCoverColourComesOutDarkEnoughToPutLightTextOn() {
        val loud =
            listOf(
                Color.WHITE,
                Color.RED,
                Color.rgb(0, 255, 80),
                Color.rgb(255, 0, 200),
                Color.rgb(255, 235, 59),
            )
        loud.forEach { cover ->
            val panel = capsuleWidgetSurface(cover)
            assertTrue(
                "a panel derived from #${Integer.toHexString(cover)} came out at " +
                    "${brightness(panel)} brightness, which is not a dark tint",
                brightness(panel) <= 0.21f,
            )
            assertTrue(
                "and the ink on it has to stay readable",
                brightness(CAPSULE_WIDGET_INK) - brightness(panel) > 0.5f,
            )
        }
    }

    @Test fun aCoverThatIsAlreadyDarkIsNotLiftedToTheCeiling() {
        val nearBlack = Color.rgb(10, 12, 16)
        val panel = capsuleWidgetSurface(nearBlack)
        assertTrue(
            "the ceiling is a limit, not a target: ${brightness(panel)}",
            brightness(panel) <= brightness(nearBlack) + 0.01f,
        )
    }

    @Test fun theHueOfTheCoverSurvives() {
        val blue = capsuleWidgetSurface(Color.rgb(40, 90, 220))
        assertTrue(
            "a blue cover must still give a blue panel, got #${Integer.toHexString(blue)}",
            Color.blue(blue) > Color.red(blue) && Color.blue(blue) > Color.green(blue),
        )
    }
}
