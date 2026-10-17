package com.nikhil.yt.ui

import androidx.compose.ui.unit.dp
import com.nikhil.yt.ui.component.ANCHOR_EPSILON_DP
import com.nikhil.yt.ui.component.BOTTOM_SHEET_POSITIONAL_THRESHOLD_FRACTION
import com.nikhil.yt.ui.component.COLLAPSED_ANCHOR
import com.nikhil.yt.ui.component.DISMISSED_ANCHOR
import com.nikhil.yt.ui.component.EXPANDED_ANCHOR
import com.nikhil.yt.ui.component.canStartCompactDismissGesture
import com.nikhil.yt.ui.component.isAtSheetAnchor
import com.nikhil.yt.ui.component.resolveBottomSheetTarget
import com.nikhil.yt.ui.component.shouldRestoreCompactPlayerAfterPlayback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomSheetAnchorTest {
    @Test fun `sub pixel rests count as anchors but visible offsets do not`() {
        assertTrue(isAtSheetAnchor(63.98.dp, 64.dp))
        assertTrue(isAtSheetAnchor(64.02.dp, 64.dp))
        assertTrue(isAtSheetAnchor(64.dp, 64.dp))
        assertFalse(isAtSheetAnchor(64.5.dp, 64.dp))
        assertFalse(isAtSheetAnchor(72.dp, 64.dp))
        assertTrue(ANCHOR_EPSILON_DP in 0f..0.1f)
    }

    @Test fun `dismiss arms from compact interaction ownership not a magic dock epsilon`() {
        assertTrue(
            canStartCompactDismissGesture(
                rawProgress = 0f,
                isDismissed = false,
                targetAnchor = COLLAPSED_ANCHOR,
            ),
        )
        // A visually docked Mini can sit slightly above zero while bounds/insets settle.
        assertTrue(
            canStartCompactDismissGesture(
                rawProgress = 0.10f,
                isDismissed = false,
                targetAnchor = COLLAPSED_ANCHOR,
            ),
        )
        assertFalse(
            canStartCompactDismissGesture(
                rawProgress = 0.10f,
                isDismissed = false,
                targetAnchor = EXPANDED_ANCHOR,
            ),
        )
        assertFalse(
            canStartCompactDismissGesture(
                rawProgress = 0.40f,
                isDismissed = false,
                targetAnchor = COLLAPSED_ANCHOR,
            ),
        )
        assertFalse(
            canStartCompactDismissGesture(
                rawProgress = 0f,
                isDismissed = true,
                targetAnchor = COLLAPSED_ANCHOR,
            ),
        )
    }

    @Test fun `vertical velocity uses one target resolver`() {
        assertEquals(
            EXPANDED_ANCHOR,
            resolveBottomSheetTarget(
                offsetPx = 120f,
                dismissedPx = 0f,
                collapsedPx = 100f,
                expandedPx = 900f,
                velocityPxPerSecond = 300f,
                velocityThresholdPxPerSecond = 250f,
                allowDismiss = false,
            ),
        )
        assertEquals(
            COLLAPSED_ANCHOR,
            resolveBottomSheetTarget(
                offsetPx = 800f,
                dismissedPx = 0f,
                collapsedPx = 100f,
                expandedPx = 900f,
                velocityPxPerSecond = -300f,
                velocityThresholdPxPerSecond = 250f,
                allowDismiss = false,
            ),
        )
    }

    @Test fun `slow release uses one positional rule between collapsed and expanded`() {
        assertEquals(0.5f, BOTTOM_SHEET_POSITIONAL_THRESHOLD_FRACTION, 0f)
        assertEquals(
            COLLAPSED_ANCHOR,
            resolveBottomSheetTarget(
                offsetPx = 499f,
                dismissedPx = 0f,
                collapsedPx = 100f,
                expandedPx = 900f,
                velocityPxPerSecond = 0f,
                velocityThresholdPxPerSecond = 250f,
                allowDismiss = false,
            ),
        )
        assertEquals(
            EXPANDED_ANCHOR,
            resolveBottomSheetTarget(
                offsetPx = 500f,
                dismissedPx = 0f,
                collapsedPx = 100f,
                expandedPx = 900f,
                velocityPxPerSecond = 0f,
                velocityThresholdPxPerSecond = 250f,
                allowDismiss = false,
            ),
        )
    }

    @Test fun `destructive dismissal is a separate below dock decision`() {
        assertEquals(
            DISMISSED_ANCHOR,
            resolveBottomSheetTarget(
                offsetPx = 20f,
                dismissedPx = 0f,
                collapsedPx = 100f,
                expandedPx = 900f,
                velocityPxPerSecond = 0f,
                velocityThresholdPxPerSecond = 250f,
                allowDismiss = true,
            ),
        )
        assertEquals(
            COLLAPSED_ANCHOR,
            resolveBottomSheetTarget(
                offsetPx = 20f,
                dismissedPx = 0f,
                collapsedPx = 100f,
                expandedPx = 900f,
                velocityPxPerSecond = 0f,
                velocityThresholdPxPerSecond = 250f,
                allowDismiss = false,
            ),
        )
        assertEquals(
            COLLAPSED_ANCHOR,
            resolveBottomSheetTarget(
                offsetPx = 80f,
                dismissedPx = 0f,
                collapsedPx = 100f,
                expandedPx = 900f,
                velocityPxPerSecond = 0f,
                velocityThresholdPxPerSecond = 250f,
                allowDismiss = true,
            ),
        )
    }
    @Test fun `first Light open is not overridden by late playback restoration`() {
        // The spring is still at Dismissed physically, but a tap has already
        // requested Expanded. The late queue/track callback must not collapse it.
        assertFalse(shouldRestoreCompactPlayerAfterPlayback(
            hasMediaItem = true,
            isYearInMusicScreen = false,
            physicalDismissed = true,
            requestedAnchor = EXPANDED_ANCHOR,
        ))
        assertFalse(shouldRestoreCompactPlayerAfterPlayback(
            hasMediaItem = true,
            isYearInMusicScreen = false,
            physicalDismissed = true,
            requestedAnchor = COLLAPSED_ANCHOR,
        ))
        assertTrue(shouldRestoreCompactPlayerAfterPlayback(
            hasMediaItem = true,
            isYearInMusicScreen = false,
            physicalDismissed = true,
            requestedAnchor = DISMISSED_ANCHOR,
        ))
    }

    @Test fun `playback restoration never opens missing media or Year in Music`() {
        assertFalse(shouldRestoreCompactPlayerAfterPlayback(
            hasMediaItem = false,
            isYearInMusicScreen = false,
            physicalDismissed = true,
            requestedAnchor = DISMISSED_ANCHOR,
        ))
        assertFalse(shouldRestoreCompactPlayerAfterPlayback(
            hasMediaItem = true,
            isYearInMusicScreen = true,
            physicalDismissed = true,
            requestedAnchor = DISMISSED_ANCHOR,
        ))
        assertFalse(shouldRestoreCompactPlayerAfterPlayback(
            hasMediaItem = true,
            isYearInMusicScreen = false,
            physicalDismissed = false,
            requestedAnchor = DISMISSED_ANCHOR,
        ))
    }
}
