package com.nikhil.yt.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.nikhil.yt.ui.component.capsuleTopBarRuleAlpha
import com.nikhil.yt.ui.component.detailHeaderSnapTargetPx
import com.nikhil.yt.ui.component.detailTitleGeometry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Small release-polish invariants, independent of screenshot/device density. */
class ReleaseChromePolishRegressionTest {
    private fun source(path: String): String =
        listOf(File("src/main/kotlin/com/nikhil/yt/$path"),
            File("app/src/main/kotlin/com/nikhil/yt/$path"),
        ).first { it.isFile }.readText()

    @Test fun collectionHeaderSettlesOnlyInThePartialHandoffWindow() {
        assertNull(detailHeaderSnapTargetPx(0, 400f, 10f))
        assertNull(detailHeaderSnapTargetPx(40, 400f, 10f)) // small peek
        assertEquals(0, detailHeaderSnapTargetPx(90, 400f, 10f))
        assertEquals(399, detailHeaderSnapTargetPx(300, 400f, 10f))
        assertNull(detailHeaderSnapTargetPx(398, 400f, 10f))
        assertNull(detailHeaderSnapTargetPx(600, 400f))
        assertNull(detailHeaderSnapTargetPx(100, Float.NaN))
        assertNull(detailHeaderSnapTargetPx(100, -1f))
    }

    @Test fun titleBreathGentlyStretchesOnlyInFlightAndReturnsToExactAnchors() {
        val sourceCenter = Offset(200f, 280f)
        val target = Rect(25f, 55f, 315f, 95f)
        val args = listOf(300f, 44f, 0f, 30f, 24f)
        fun at(p: Float) = detailTitleGeometry(
            progress = p, compactScale = 0.88f,
            width = args[0], height = args[1],
            lineLeft = args[2], baseline = args[3], lineHeight = args[4],
            sourceCenter = sourceCenter, scrollOffset = 0f, target = target,
        )
        assertEquals(1f, at(0f).scale, 0.0001f)
        assertEquals(.88f, at(1f).scale, 0.0001f)
        assertTrue(at(.5f).scale > .94f)
        assertTrue(at(.5f).scale < 1.02f)
        assertTrue(at(.5f).clip.width > at(1f).clip.width)
    }

    @Test fun hairlineIsSubtleAndRevealsSmoothlyWithTheArtwork() {
        assertEquals(0f, capsuleTopBarRuleAlpha(0f), 0f)
        assertEquals(0f, capsuleTopBarRuleAlpha(.61f), 0f)
        assertEquals(1f, capsuleTopBarRuleAlpha(1f), 0f)
        val samples = (0..100).map { capsuleTopBarRuleAlpha(it / 100f) }
        assertTrue(samples.zipWithNext().all { (a, b) -> b >= a && b - a < .065f })
    }

    @Test fun everyCollectionPageHasMagneticHandoffButListDragIsExcluded() {
        val pages = listOf(
            "ui/screens/AlbumScreen.kt",
            "ui/screens/playlist/LocalPlaylistScreen.kt",
            "ui/screens/playlist/OnlinePlaylistScreen.kt",
            "ui/screens/playlist/AutoPlaylistScreen.kt",
            "ui/screens/playlist/CachePlaylistScreen.kt",
            "ui/screens/playlist/TopPlaylistScreen.kt",
        )
        pages.forEach { file ->
            assertTrue(file, source(file).contains("DetailHeaderMagneticSnap(titleMotion, lazyListState"))
        }
        assertTrue(source(pages[1]).contains("!reorderableState.isAnyItemDragging"))
        val motion = source("ui/component/DetailToolbarReveal.kt")
        assertTrue(motion.contains("snapshotFlow { !listState.isScrollInProgress }"))
        assertTrue(motion.contains("scroll.animateScrollBy("))
        assertFalse(motion.contains("rememberInfiniteTransition"))
    }

    @Test fun playlistCreateFabIsReachableAtRestInGridAndList() {
        val page = source("ui/screens/library/LibraryPlaylistsScreen.kt")
        assertEquals(2, page.split("ScrollActionButton(").size - 1)
        assertEquals(2, page.split("visible = !canReorderPlaylists").size - 1)
        assertFalse(page.contains("HideOnScrollFAB("))
        assertTrue(page.contains("showCreatePlaylistDialog = true"))
    }

    @Test fun accountIsCircularAndAllMainAndDetailHeadersUseTheSameRule() {
        val chrome = source("ui/component/StandardChrome.kt")
        val title = chrome.substringAfter("fun StandardHeaderTitle(")
        assertTrue(title.contains("Modifier.size(44.dp)"))
        assertTrue(title.contains("Modifier.size(40.dp)"))
        assertTrue(title.contains(".clip(CircleShape)"))
        assertFalse(title.contains(".clip(RoundedCornerShape(19.dp))"))
        val host = source("ui/screens/CapsuleRouteSurface.kt")
        val detail = source("ui/component/DetailToolbarReveal.kt")
        val artist = source("ui/component/ArtistToolbar.kt")
        assertTrue(host.contains(".capsuleTopBarHairline(StandardChrome.muted)"))
        assertTrue(detail.contains(".capsuleTopBarHairline(StandardChrome.muted)"))
        assertTrue(artist.contains(".capsuleTopBarHairline(StandardChrome.muted"))
    }

    @Test fun livingArtistKeepsButtonAnchorsWhileSofteningOnlyThePhotoMatte() {
        val hero = source("ui/component/ArtistHero.kt")
        assertTrue(hero.contains("targetValue = if (showCandle) 0f else 1f"))
        assertTrue(hero.contains("compactPortraitFadeStrength = compactPortraitFade"))
        assertTrue(hero.contains("val actionRailHeight = firstRowHeight + memorialExtraHeight"))
        val fade = source("ui/component/ArtworkSurfaceFade.kt")
        assertTrue(fade.contains("capsulePortraitMatteForArtist(background, compactPortraitFadeStrength)"))
    }
}
