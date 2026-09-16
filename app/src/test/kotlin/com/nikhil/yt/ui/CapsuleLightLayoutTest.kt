package com.nikhil.yt.ui

import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.nikhil.yt.R
import com.nikhil.yt.constants.CapsulePlayerDesign
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.unit.Density
import com.nikhil.yt.playback.video.CapsuleVideoPlaybackState
import com.nikhil.yt.ui.player.CapsuleAudioVideoToggle
import com.nikhil.yt.ui.player.CapsuleLightControls
import com.nikhil.yt.ui.player.CapsuleLightLyricLine
import com.nikhil.yt.ui.player.CapsuleLightLyricLineHeight
import com.nikhil.yt.ui.player.CapsuleLightToggleHeight
import com.nikhil.yt.ui.player.CapsuleLightToggleInset
import com.nikhil.yt.ui.player.CapsulePlayerLayout
import com.nikhil.yt.ui.player.capsuleLightLyricLineAt
import com.nikhil.yt.ui.player.capsuleLightLyricLines
import org.junit.Assert.*
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h851dp-xhdpi")
class CapsuleLightLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun lightIsCoverFirstAndHasNoTopHeaderButtons() {
        var design by mutableStateOf(CapsulePlayerDesign.SUPER)
        var height by mutableStateOf(760.dp)
        compose.setContent {
            MaterialTheme {
                CapsulePlayerLayout(design, Color.White, {}, {}, Modifier.size(320.dp, height).testTag("player"),
                    artwork = { Box(Modifier.fillMaxWidth().aspectRatio(1f).testTag("cover")) },
                    details = { Box(Modifier.fillMaxWidth().height(320.dp).testTag("details")) },
                )
            }
        }
        val collapseLabel = compose.activity.getString(R.string.capsule_collapse_player)
        val menuLabel = compose.activity.getString(R.string.more)
        compose.runOnIdle { design = CapsulePlayerDesign.LIGHT }
        compose.onNodeWithContentDescription(collapseLabel).assertDoesNotExist()
        compose.onNodeWithContentDescription(menuLabel).assertDoesNotExist()
        for (screenHeight in listOf(760.dp, 620.dp)) {
            compose.runOnIdle { height = screenHeight }
            val cover = compose.onNodeWithTag("cover").fetchSemanticsNode().boundsInRoot
            val details = compose.onNodeWithTag("details").fetchSemanticsNode().boundsInRoot
            assertEquals(cover.width, cover.height, 1f)
            assertTrue(cover.width > 0f)
            assertTrue(cover.bottom <= details.top)
        }
    }

    @Test fun lightTransportUsesRepeatPreviousPlayNextMenuAndRespectsPlaybackRestrictions() {
        var enabled by mutableStateOf(true)
        val clicked = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth()) {
                    CapsuleLightControls(
                        textColor = Color.White,
                        shuffleEnabled = false,
                        repeatMode = Player.REPEAT_MODE_ALL,
                        enabled = enabled,
                        canSkipPrevious = true,
                        canSkipNext = true,
                        onShuffle = {},
                        onPrevious = { clicked += "previous" },
                        onNext = { clicked += "next" },
                        onRepeat = { clicked += "repeat" },
                        orbit = { Box(Modifier.size(70.dp).clickable(enabled = enabled) { clicked += "play" }.testTag("orbit")) },
                        onMenuClick = { clicked += "menu" },
                    )
                }
            }
        }
        val repeat = compose.onNodeWithContentDescription(compose.activity.getString(R.string.repeat_mode_all))
        val previous = compose.onNodeWithContentDescription(compose.activity.getString(androidx.media3.ui.R.string.exo_controls_previous_description))
        val orbit = compose.onNodeWithTag("orbit")
        val next = compose.onNodeWithContentDescription(compose.activity.getString(androidx.media3.ui.R.string.exo_controls_next_description))
        val menu = compose.onNodeWithContentDescription(compose.activity.getString(R.string.more))
        val nodes = listOf(repeat, previous, orbit, next, menu)
        val centers = nodes.map { it.fetchSemanticsNode().boundsInRoot.center.x }
        assertEquals(centers.sorted(), centers)
        nodes.forEach { it.performClick() }
        compose.runOnIdle {
            assertEquals(listOf("repeat", "previous", "play", "next", "menu"), clicked)
            enabled = false
        }
        listOf(repeat, previous, orbit, next).forEach { it.assertIsNotEnabled() }
        menu.performClick()
        compose.runOnIdle { assertEquals("menu", clicked.last()) }
    }
    @Test fun shortLightPlayerCanScrollToItsLastControlWithoutLosingActions() {
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                CapsulePlayerLayout(CapsulePlayerDesign.LIGHT, Color.White, {}, {},
                    Modifier.size(320.dp, 420.dp),
                    artwork = { Box(Modifier.fillMaxWidth().aspectRatio(1f).testTag("cover")) },
                    details = {
                        Column(Modifier.fillMaxWidth()) {
                            Box(Modifier.height(500.dp))
                            Box(Modifier.size(48.dp).testTag("last-control").clickable { clicks++ })
                        }
                    },
                )
            }
        }
        compose.onNodeWithTag("last-control").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, clicks)
    }


    @Test fun lightScrollingKeepsTheDownwardQueueGestureAtTheTop() {
        var queueOpens = 0
        compose.setContent {
            MaterialTheme {
                CapsulePlayerLayout(
                    CapsulePlayerDesign.LIGHT, Color.White, {}, {},
                    Modifier.size(320.dp, 420.dp).testTag("player"),
                    onExpandQueue = { queueOpens++ },
                    artwork = { Box(Modifier.fillMaxWidth().aspectRatio(1f).testTag("cover")) },
                    details = {
                        Column(Modifier.fillMaxWidth()) {
                            Box(Modifier.height(700.dp))
                            Box(Modifier.size(48.dp).testTag("last-control"))
                        }
                    },
                )
            }
        }
        compose.onNodeWithTag("last-control").performScrollTo()
        compose.onNodeWithTag("player").performTouchInput {
            swipeDown(startY = height * 0.25f, endY = height * 0.5f, durationMillis = 600)
        }
        compose.runOnIdle { assertEquals("Scrolling the details must not open the queue", 0, queueOpens) }
        compose.onNodeWithTag("cover").performScrollTo()
        compose.onNodeWithTag("player").performTouchInput {
            swipeDown(startY = height * 0.2f, endY = height * 0.7f, durationMillis = 600)
        }
        compose.runOnIdle { assertEquals("A pull down from the top opens the queue once", 1, queueOpens) }
    }

    @Test fun lightArtworkCardIsSquare() {
        compose.setContent {
            MaterialTheme {
                CapsulePlayerLayout(
                    CapsulePlayerDesign.LIGHT, Color.White, {}, {},
                    Modifier.size(360.dp, 860.dp),
                    lyricLine = { Box(Modifier.fillMaxWidth().height(CapsuleLightLyricLineHeight).testTag("lyric")) },
                    artwork = { Box(Modifier.fillMaxSize().testTag("cover")) },
                    details = { Box(Modifier.fillMaxWidth().height(320.dp).testTag("details")) },
                )
            }
        }
        val cover = compose.onNodeWithTag("cover").fetchSemanticsNode().boundsInRoot
        assertTrue("the card must have a size at all", cover.width > 0f)
        assertEquals("the card is square", cover.width, cover.height, 1f)
    }

    @Test fun lightDrawsTheSoundingLineBetweenTheCardAndTheDetails() {
        compose.setContent {
            MaterialTheme {
                CapsulePlayerLayout(
                    CapsulePlayerDesign.LIGHT, Color.White, {}, {},
                    Modifier.size(360.dp, 860.dp),
                    lyricLine = { Box(Modifier.fillMaxWidth().height(CapsuleLightLyricLineHeight).testTag("lyric")) },
                    artwork = { Box(Modifier.fillMaxSize().testTag("cover")) },
                    details = { Box(Modifier.fillMaxWidth().height(320.dp).testTag("details")) },
                )
            }
        }
        val cover = compose.onNodeWithTag("cover").fetchSemanticsNode().boundsInRoot
        val lyric = compose.onNodeWithTag("lyric").fetchSemanticsNode().boundsInRoot
        val details = compose.onNodeWithTag("details").fetchSemanticsNode().boundsInRoot
        assertTrue("the line belongs under the card", lyric.top >= cover.bottom)
        assertTrue("the line belongs above the metadata", lyric.bottom <= details.top)
        val gap = details.top - cover.bottom
        val lineHeight = with(Density(compose.activity)) { CapsuleLightLyricLineHeight.toPx() }
        assertTrue("the details must sit lower than the bare line height", gap > lineHeight)
    }

    @Test fun theSoundingLineFollowsThePlaybackPosition() {
        val lines = capsuleLightLyricLines("[00:10.00]first line\n[00:20.00]second line\n[00:30.00]third line")
        assertEquals(3, lines.size)
        assertNull("nothing is sounding before the first line", capsuleLightLyricLineAt(lines, 0L))
        assertEquals("first line", capsuleLightLyricLineAt(lines, 12_000L))
        assertEquals("second line", capsuleLightLyricLineAt(lines, 25_000L))
        assertEquals("third line", capsuleLightLyricLineAt(lines, 999_000L))
    }

    @Test fun lyricsThatCannotBeFollowedLeaveTheLineEmpty() {
        for (source in listOf(null, "", "   ", "LYRICS_NOT_FOUND", "a plain unsynced verse\nand another")) {
            val lines = capsuleLightLyricLines(source)
            assertEquals("'" + source + "' carries no timing", emptyList<Any>(), lines)
            assertNull(capsuleLightLyricLineAt(lines, 30_000L))
        }
    }

    @Test fun lightModeSwitchSitsConcentricallyInsideItsShell() {
        compose.setContent {
            MaterialTheme {
                CapsuleAudioVideoToggle(
                    state = CapsuleVideoPlaybackState(),
                    textColor = Color.White,
                    enabled = true,
                    onAudioClick = {},
                    onVideoClick = {},
                    modifier = Modifier.width(220.dp).testTag("toggle"),
                    lightStyle = true,
                )
            }
        }
        val density = Density(compose.activity)
        val shell = compose.onNodeWithTag("toggle").fetchSemanticsNode().boundsInRoot
        // AUDIO is the selected segment, so it carries no scale and its bounds are exact.
        val segment = compose.onAllNodes(hasClickAction())[0].fetchSemanticsNode().boundsInRoot
        val inset = with(density) { CapsuleLightToggleInset.toPx() }
        assertEquals(with(density) { CapsuleLightToggleHeight.toPx() }, shell.height, 1f)
        assertEquals("left band", inset, segment.left - shell.left, 1f)
        assertEquals("top band", inset, segment.top - shell.top, 1f)
        assertEquals("bottom band", inset, shell.bottom - segment.bottom, 1f)
    }

    @Test fun theSoundingLineStartsAtTheCardsLeadingEdge() {
        compose.setContent {
            MaterialTheme {
                CapsulePlayerLayout(
                    CapsulePlayerDesign.LIGHT, Color.White, {}, {},
                    Modifier.size(360.dp, 860.dp),
                    lyricLine = { Box(Modifier.fillMaxWidth().height(CapsuleLightLyricLineHeight).testTag("lyric")) },
                    artwork = { Box(Modifier.fillMaxSize().testTag("cover")) },
                    details = { Box(Modifier.fillMaxWidth().height(320.dp).testTag("details")) },
                )
            }
        }
        val cover = compose.onNodeWithTag("cover").fetchSemanticsNode().boundsInRoot
        val lyric = compose.onNodeWithTag("lyric").fetchSemanticsNode().boundsInRoot
        assertEquals("the line starts where the card starts", cover.left, lyric.left, 1f)
        assertEquals("and ends where the card ends", cover.right, lyric.right, 1f)
    }

    @Test fun switchingTheLineOffTakesItsGapsWithIt() {
        var withLine by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                CapsulePlayerLayout(
                    CapsulePlayerDesign.LIGHT, Color.White, {}, {},
                    Modifier.size(360.dp, 860.dp),
                    lyricLine =
                        if (withLine) {
                            { Box(Modifier.fillMaxWidth().height(CapsuleLightLyricLineHeight).testTag("lyric")) }
                        } else {
                            null
                        },
                    artwork = { Box(Modifier.fillMaxSize().testTag("cover")) },
                    details = { Box(Modifier.fillMaxWidth().height(320.dp).testTag("details")) },
                )
            }
        }
        fun gap(): Float {
            val cover = compose.onNodeWithTag("cover").fetchSemanticsNode().boundsInRoot
            val details = compose.onNodeWithTag("details").fetchSemanticsNode().boundsInRoot
            return details.top - cover.bottom
        }
        val on = gap()
        compose.runOnIdle { withLine = false }
        compose.waitForIdle()
        compose.onNodeWithTag("lyric").assertDoesNotExist()
        val off = gap()
        assertTrue("switching the line off must not leave its space behind", off < on)
        assertEquals(with(Density(compose.activity)) { 20.dp.toPx() }, off, 1f)
    }

    /**
     * A line too long for the card wraps downwards instead of being cut off.
     *
     * This is a source check, and it has to be: Robolectric's font stub reports zero-width glyphs,
     * so a thousand-character string measures as fitting on one line and a rendered test would pass
     * whatever maxLines said. What is checkable is the two things that let it wrap at all — the row
     * reserves a minimum rather than a fixed height, and the text is allowed more than one line.
     */
    /**
     * Two lines fit in the reserved row without it growing.
     *
     * The row holds the full wrap up front so a long line never pushes the title, the progress bar
     * and the transport panel down while it is sounding. Robolectric's font stub reports zero-width
     * glyphs, so a long string cannot be made to wrap here — but an explicit newline lays out as
     * two lines regardless of glyph widths, which measures the thing that matters: whether two
     * lines fit in the space that is always reserved.
     */
    @Test fun twoLinesFitTheReservedRowWithoutGrowingIt() {
        var line by mutableStateOf("one")
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(240.dp)) {
                    CapsuleLightLyricLine(line, Color.White, Modifier.testTag("line"))
                }
            }
        }
        val reserved = with(Density(compose.activity)) { CapsuleLightLyricLineHeight.toPx() }
        fun height() = compose.onNodeWithTag("line").fetchSemanticsNode().boundsInRoot.height

        assertEquals("one line uses the reserved row", reserved, height(), 1f)
        compose.runOnIdle { line = "one\ntwo" }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        assertEquals("two lines must fit it too", reserved, height(), 1f)
    }

    /**
     * A line longer than the row wraps rather than being cut off.
     *
     * A source check, and it has to be: Robolectric's font stub reports zero-width glyphs, so a
     * thousand-character string measures as fitting on one line and a rendered assertion would
     * pass whatever maxLines said. That was measured, not assumed.
     */
    @Test fun theSoundingLineIsAllowedToWrapDownwards() {
        val code = lyricLineSource()
        assertFalse(
            "one line means a long line is ellipsised instead of wrapping",
            code.any { it.contains("maxLines = 1") },
        )
        assertTrue(
            "and the wrap has to stop somewhere, or one line could take the screen",
            code.any { it.contains("MAX_LINES = 2") },
        )
    }

    /**
     * The sounding line must not cost an offscreen buffer.
     *
     * `AnimatedContent` composes both lines for the length of every change and animates each one
     * through `alpha`, and a layer with an alpha below 1 is composited through a buffer allocated
     * and blended every frame. For a line that changes every few seconds, all day, on a screen the
     * user leaves open while listening, that is the expensive way to fade one string. The fade
     * belongs in the text colour, which is a paint value, and the rise on a layer that stays opaque.
     */
    @Test fun theSoundingLineFadesWithoutAnOffscreenBuffer() {
        val code = lyricLineSource()
        assertFalse(
            "AnimatedContent brings a second composed line and an alpha layer with it",
            code.any { it.contains("AnimatedContent") },
        )
        assertFalse(
            "an alpha below 1 on the layer is exactly the buffer this avoids",
            code.any { it.contains("alpha =") && it.contains("graphicsLayer") },
        )
        assertTrue(
            "the fade has to live in the text colour instead",
            code.any { it.contains("alpha = LINE_ALPHA * arrived") },
        )
    }

    private fun lyricLineSource(): List<String> {
        val source =
            listOf(
                File("src/main/kotlin/com/nikhil/yt/ui/player/CapsuleLightLyricLine.kt"),
                File("app/src/main/kotlin/com/nikhil/yt/ui/player/CapsuleLightLyricLine.kt"),
            ).firstOrNull { it.isFile }
        assertTrue("Could not find CapsuleLightLyricLine.kt from " + File(".").absolutePath, source != null)
        return source!!.readLines()
            .map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }
    }
}
