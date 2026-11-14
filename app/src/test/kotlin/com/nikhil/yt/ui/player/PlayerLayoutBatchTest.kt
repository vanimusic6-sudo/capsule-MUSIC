package com.nikhil.yt.ui.player

import android.app.Application
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.nikhil.yt.ui.component.LyricsScreenAwake
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.media3.common.PlaybackException
import com.nikhil.yt.R
import com.nikhil.yt.constants.CapsulePlayerDesign
import com.nikhil.yt.constants.CapsulePlayerDesignKey
import com.nikhil.yt.constants.migrateRetiredPlayerDesign
import com.nikhil.yt.lyrics.LyricsEntry
import com.nikhil.yt.lyrics.withInstrumentalBreaks
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlayerLayoutBatchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun retiredDesignMigratesWithoutChangingExistingLightOrImmersion() {
        assertEquals(listOf("LIGHT", "IMMERSIVE"), CapsulePlayerDesign.entries.map { it.name })
        val preferences = mutablePreferencesOf(CapsulePlayerDesignKey to "SUPER")
        migrateRetiredPlayerDesign(preferences)
        assertEquals("LIGHT", preferences[CapsulePlayerDesignKey])
        for (design in CapsulePlayerDesign.entries) {
            preferences[CapsulePlayerDesignKey] = design.name
            migrateRetiredPlayerDesign(preferences)
            assertEquals(design.name, preferences[CapsulePlayerDesignKey])
        }
    }

    @Test fun compactLyricsUseTheSameHalfOpenPauseIntervalsAsFullLyrics() {
        val lines = withInstrumentalBreaks(listOf(
            LyricsEntry(10_000L, "First", durationMs = 2_000L),
            LyricsEntry(22_000L, "Second", durationMs = 2_000L),
        ), 30_000L)
        assertNull(capsuleLightLyricEntryAt(lines, 999L))
        assertTrue(capsuleLightLyricEntryAt(lines, 1_000L)!!.isInstrumental)
        assertTrue(capsuleLightLyricEntryAt(lines, 9_999L)!!.isInstrumental)
        assertEquals("First", capsuleLightLyricLineAt(lines, 10_000L))
        assertTrue(capsuleLightLyricEntryAt(lines, 12_000L)!!.isInstrumental)
        assertTrue(capsuleLightLyricEntryAt(lines, 21_999L)!!.isInstrumental)
        assertEquals("Second", capsuleLightLyricLineAt(lines, 22_000L))
        assertNull(capsuleLightLyricEntryAt(lines, 30_000L))
    }

    @Test fun noteTextAndEmptyRowKeepIdenticalGeometryIncludingLargeFontsAndResume() {
        val line = mutableStateOf<String?>("A long lyric that needs two lines to fit inside this row")
        val note = mutableStateOf<LyricsEntry?>(null)
        val active = mutableStateOf(true)
        val fontScale = mutableStateOf(1f)
        val position = mutableLongStateOf(5_000L)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale.value)) {
                CapsuleLightLyricLine(
                    line = line.value, textColor = Color.White,
                    modifier = Modifier.width(280.dp).testTag("row"),
                    instrumental = note.value, playbackPosition = position, animate = active.value,
                )
            }
        }
        for (scale in listOf(1f, 1.6f)) {
            compose.runOnIdle { fontScale.value = scale }
            val baseline = compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle { line.value = null; note.value = LyricsEntry(1_000L, "", durationMs = 9_000L, isInstrumental = true) }
            val pause = RuntimeEnvironment.getApplication().getString(R.string.instrumental_pause)
            compose.onNodeWithContentDescription(pause).assertExists()
            compose.runOnIdle { active.value = false; position.longValue = 8_000L }
            compose.runOnIdle { active.value = true }
            assertEquals(baseline, compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot)
            compose.runOnIdle { note.value = null; line.value = "Back to the lyric" }
            assertEquals(baseline, compose.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test fun metadataActionKeepsItsTouchWidthWhenEditingOrChangingScreenWidth() {
        val editable = mutableStateOf(false)
        val width = mutableStateOf(320.dp)
        compose.setContent {
            CapsuleLightReorderRow(
                order = CapsuleLightMetadataBaseOrder, editable = editable.value,
                weightFor = { 1f },
                widthFor = { if (it == CapsuleLightMetadataItem.FAVORITE) 48.dp else null },
                onOrderChange = {}, onOrderSettled = {}, onEditStarted = {},
                modifier = Modifier.size(width.value, 60.dp),
            ) { item ->
                Box(Modifier.fillMaxWidth().height(48.dp).testTag(item.name))
            }
        }
        for (w in listOf(320.dp, 393.dp, 600.dp)) {
            compose.runOnIdle { width.value = w }
            val baseline = compose.onNodeWithTag("FAVORITE").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle { editable.value = true }
            assertEquals(baseline, compose.onNodeWithTag("FAVORITE").fetchSemanticsNode().boundsInRoot)
            compose.runOnIdle { editable.value = false }
        }
    }

    @Test fun hiddenCanvasDoesNotRewriteSavedGeometryAndResumesWithoutRemounting() {
        val active = mutableStateOf(false)
        var writes = 0
        var compositionsOfArtwork = 0
        compose.setContent {
            CapsuleLightCanvasV2(
                order = CapsuleLightBaseOrder, positionsDp = emptyMap(), editable = false,
                viewportHeight = 650.dp, runtimeActive = active.value,
                onLayoutSettled = { _, _ -> writes++ }, onEditStarted = {},
                onArtworkSelectionDismiss = {}, modifier = Modifier.size(320.dp, 650.dp),
            ) { block, _ ->
                if (block == CapsuleLightBlock.ARTWORK) {
                    androidx.compose.runtime.DisposableEffect(Unit) {
                        compositionsOfArtwork++
                        onDispose { compositionsOfArtwork-- }
                    }
                }
                Box(Modifier.fillMaxWidth().height(60.dp).testTag(block.name))
            }
        }
        compose.runOnIdle { assertEquals(0, writes); assertEquals(1, compositionsOfArtwork) }
        val before = compose.onNodeWithTag("METADATA").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { active.value = true }
        compose.runOnIdle { assertTrue(writes > 0); assertEquals(1, compositionsOfArtwork) }
        assertEquals(before, compose.onNodeWithTag("METADATA").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { active.value = false }
        val settledWrites = writes
        compose.mainClock.advanceTimeBy(1_000L)
        compose.runOnIdle { assertEquals(settledWrites, writes); assertEquals(1, compositionsOfArtwork) }
    }

    @Test fun onlyVisiblePlayingLyricsMayKeepTheDisplayAwake() {
        val playing = mutableStateOf(true)
        val visible = mutableStateOf(true)
        val onScreen = mutableStateOf(true)
        val mounted = mutableStateOf(true)
        compose.setContent {
            if (mounted.value) LyricsScreenAwake(playing.value, visible.value, onScreen.value)
        }
        fun assertAwake(expected: Boolean) = compose.runOnIdle {
            assertEquals(expected, compose.activity.window.attributes.flags and
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        }
        assertAwake(true)
        compose.runOnIdle { visible.value = false }; assertAwake(false)
        compose.runOnIdle { visible.value = true }; assertAwake(true)
        compose.runOnIdle { onScreen.value = false }; assertAwake(false)
        compose.runOnIdle { onScreen.value = true }; assertAwake(true)
        compose.runOnIdle { playing.value = false }; assertAwake(false)
        compose.runOnIdle { playing.value = true }; assertAwake(true)
        compose.runOnIdle { mounted.value = false }; assertAwake(false)
    }

    @Test fun playbackFailureIsAnOverlayAndDismissalBelongsOnlyToThatError() {
        val error = mutableStateOf<PlaybackException?>(PlaybackException("test", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        val visible = mutableStateOf(true)
        var retries = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(300.dp).testTag("artwork"))
                PlaybackErrorOverlay("track", error.value, visible.value, retry = { retries++ })
            }
        }
        val context = RuntimeEnvironment.getApplication()
        compose.onNodeWithTag("artwork").assertExists()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText(context.getString(R.string.retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText(context.getString(R.string.dismiss)).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { visible.value = true }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { error.value = PlaybackException("next", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) }
        compose.onNode(isDialog()).assertExists()
        compose.runOnIdle { error.value = null }
        compose.onNode(isDialog()).assertDoesNotExist()
    }
}
