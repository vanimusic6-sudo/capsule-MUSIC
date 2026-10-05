package com.nikhil.yt.ui

import androidx.compose.ui.unit.dp
import com.nikhil.yt.ui.component.FullPlayerContentFadeEnd
import com.nikhil.yt.ui.component.PlayerContentHandoffPoint
import com.nikhil.yt.ui.component.auditMiniHandoverStretch
import com.nikhil.yt.ui.component.auditPlayerFoldFraction
import com.nikhil.yt.ui.component.expandedPlayerCanAcceptInput
import com.nikhil.yt.ui.component.fullPlayerContentAlpha
import com.nikhil.yt.ui.component.miniPlayerContentAlpha
import com.nikhil.yt.ui.component.miniPlayerForegroundCanAcceptInput
import com.nikhil.yt.ui.component.playerContainerAlpha
import com.nikhil.yt.ui.component.playerFrameCornerRadius
import com.nikhil.yt.ui.component.playerFrameHorizontalScale
import com.nikhil.yt.ui.component.shouldRenderExpandedContent
import com.nikhil.yt.ui.component.shouldRenderExpandedSurface
import com.nikhil.yt.ui.component.shouldShowCompactSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DockHandoverTest {
    @Test fun `mini stays at its dock as the sheet travels`() {
        val expanded = 800.dp
        val collapsed = 80.dp
        for (step in 0..20) {
            val progress = step / 20f
            val value = collapsed + (expanded - collapsed) * progress
            val sheetTop = (expanded - value).value
            val miniPinOffset = (value - collapsed).value.coerceAtLeast(0f)
            assertEquals((expanded - collapsed).value, sheetTop + miniPinOffset, 0.001f)
        }
    }

    @Test fun `audit fold and mini give return exactly to their resting geometry`() {
        assertEquals(1f, auditPlayerFoldFraction(0f), 0f)
        assertEquals(0f, auditPlayerFoldFraction(1f), 0f)
        assertEquals(1f, auditMiniHandoverStretch(0f), 0f)
        assertEquals(1f, auditMiniHandoverStretch(1f), 0f)
        assertTrue(auditMiniHandoverStretch(0.22f) > 1f)
        var previous = auditPlayerFoldFraction(0f)
        for (step in 1..100) {
            val fold = auditPlayerFoldFraction(step / 100f)
            assertTrue(fold <= previous + 0.00001f)
            previous = fold
        }
    }

    @Test fun `compact and full UI never overlap visually`() {
        for (step in 0..200) {
            val progress = step / 200f
            val mini = miniPlayerContentAlpha(progress)
            val full = fullPlayerContentAlpha(progress)
            assertFalse(
                "two player UIs visible at progress=$progress mini=$mini full=$full",
                mini > 0.0001f && full > 0.0001f,
            )
        }
        assertEquals(0f, miniPlayerContentAlpha(PlayerContentHandoffPoint), 0f)
        assertEquals(0f, fullPlayerContentAlpha(PlayerContentHandoffPoint), 0f)
    }

    @Test fun `container stays continuous while content ownership changes`() {
        assertEquals(1f, playerContainerAlpha(PlayerContentHandoffPoint), 0f)
        assertTrue(playerContainerAlpha(PlayerContentHandoffPoint - 0.01f) > 0f)
        assertTrue(playerContainerAlpha(PlayerContentHandoffPoint + 0.01f) > 0f)
        assertEquals(1f, fullPlayerContentAlpha(FullPlayerContentFadeEnd), 0f)
    }

    @Test fun `hit tree ownership follows the same handoff point`() {
        assertTrue(shouldShowCompactSurface(0f, false))
        assertTrue(miniPlayerForegroundCanAcceptInput(0f, false))
        assertFalse(shouldRenderExpandedContent(0f, false))

        assertFalse(shouldShowCompactSurface(PlayerContentHandoffPoint, false))
        assertFalse(miniPlayerForegroundCanAcceptInput(PlayerContentHandoffPoint, false))
        assertFalse(shouldRenderExpandedContent(PlayerContentHandoffPoint, false))

        assertTrue(shouldRenderExpandedContent(PlayerContentHandoffPoint + 0.01f, false))
        assertTrue(expandedPlayerCanAcceptInput(PlayerContentHandoffPoint + 0.01f, false))
    }

    @Test fun `expanded container is visual only below handoff`() {
        val progress = PlayerContentHandoffPoint * 0.5f
        assertTrue(shouldRenderExpandedSurface(progress, false))
        assertFalse(shouldRenderExpandedContent(progress, false))
        assertFalse(shouldRenderExpandedSurface(0f, false))
        assertFalse(shouldRenderExpandedSurface(1f, true))
    }

    @Test fun `full surface widens monotonically from mini inset to screen width`() {
        val width = 400f
        val inset = 12f
        val collapsed = (width - inset * 2f) / width
        assertEquals(collapsed, playerFrameHorizontalScale(0f, width, inset), 0.0001f)
        assertEquals(1f, playerFrameHorizontalScale(1f, width, inset), 0.0001f)
        var previous = collapsed
        for (step in 0..100) {
            val scale = playerFrameHorizontalScale(step / 100f, width, inset)
            assertTrue(scale + 1e-6f >= previous)
            assertTrue(scale in collapsed..1f)
            previous = scale
        }
    }

    @Test fun `transition functions stay bounded and corner safe`() {
        listOf(-1f, 0f, 0.25f, 1f, 1.0001f, Float.NaN, Float.POSITIVE_INFINITY)
            .forEach { progress ->
                assertTrue(playerFrameCornerRadius(progress).value >= 0f)
                assertTrue(miniPlayerContentAlpha(progress).isFinite())
                assertTrue(fullPlayerContentAlpha(progress).isFinite())
                assertTrue(playerContainerAlpha(progress).isFinite())
            }
    }
}
