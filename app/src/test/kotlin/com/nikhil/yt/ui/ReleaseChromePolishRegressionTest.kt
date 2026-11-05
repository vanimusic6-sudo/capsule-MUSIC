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
        assertEquals(0, detailHeaderSnapTargetPx(40, 400f, 10f)) // small peek settles open
        assertEquals(0, detailHeaderSnapTargetPx(49, 400f, 10f))
        assertEquals(0, detailHeaderSnapTargetPx(90, 400f, 10f))
        assertEquals(399, detailHeaderSnapTargetPx(300, 400f, 10f))
        assertEquals(399, detailHeaderSnapTargetPx(398, 400f, 10f))
        assertEquals(400, detailHeaderSnapTargetPx(300, 400.4f, 10f))
        assertNull(detailHeaderSnapTargetPx(600, 400f))
        assertNull(detailHeaderSnapTargetPx(100, Float.NaN))
        assertNull(detailHeaderSnapTargetPx(100, -1f))
        assertEquals("Positive release scroll velocity must dock without pause",
            399, detailHeaderSnapTargetPx(90, 400f, 1f, 1000f))
        assertEquals("Reverse release velocity must unfold without pause",
            0, detailHeaderSnapTargetPx(300, 400f, 1f, -1000f))
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
        assertTrue(capsuleTopBarRuleAlpha(.61f) > 0f)
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
            assertTrue(file, source(file).contains("rememberDetailHeaderFlingBehavior(titleMotion"))
        }
        assertTrue(source(pages[1]).contains("!reorderableState.isAnyItemDragging"))
        val motion = source("ui/component/DetailToolbarReveal.kt")
        assertFalse("Observer-driven snapping caused competing scroll mutations",
            motion.contains("snapshotFlow { !listState.isScrollInProgress }"))
        assertFalse(motion.contains("scroll.animateScrollBy("))
        assertTrue(motion.contains("override suspend fun ScrollScope.performFling(initialVelocity: Float)"))
        assertTrue(motion.contains("val delta = motion.idleHandoffDeltaPx(initialVelocity)"))
        assertTrue(motion.contains("return with(native) { performFling(initialVelocity) }"))
        assertTrue(motion.contains("initialVelocity = initialVelocity.coerceIn(-1_600f, 1_600f)"))
        assertTrue(motion.contains("val consumed = scrollBy(diff)"))
        assertFalse("Native fling must NOT finish before magnet begins",
            motion.contains("val remaining = with(native) { performFling(initialVelocity) }"))
        assertFalse(motion.contains("rememberInfiniteTransition"))
    }

    @Test fun photoToToolbarRestoresAContinuousFadeWithoutSuddenBlackBand() {
        val helper = source("ui/component/ArtistToolbar.kt")
        assertTrue(helper.contains("val t = ((artworkFade.coerceIn(0f, 1f)"))
        assertFalse(helper.contains("return if (artworkFade >= 1f) 1f else 0f"))
        val fade = source("ui/component/ArtworkSurfaceFade.kt")
        assertTrue(fade.contains("val start = 0.55f"))
        assertTrue(fade.contains("ease.pow(4.6f)"))
        val bar = source("ui/component/DetailToolbarReveal.kt")
        assertTrue(bar.contains("val dividerDocked by remember(motion, forceOpaque)"))
        assertTrue(bar.contains("forceOpaque || motion.compactTitleAlpha() == 1f"))
        assertTrue(bar.contains("durationMillis = 350"))
        assertFalse("Line must not follow raw artwork scroll across moving glyphs",
            bar.contains("val dividerReveal = if (forceOpaque) 1f"))
    }

    @Test fun playlistCreateFabIsReachableAtRestInGridAndList() {
        val page = source("ui/screens/library/LibraryPlaylistsScreen.kt")
        assertEquals(1, page.split("PersistentPlaylistAction(").size - 1)
        assertTrue(page.contains("visible = !reorderableState.isAnyItemDragging && !showCreatePlaylistDialog"))
        assertFalse(page.contains("visible = !canReorderPlaylists && !showCreatePlaylistDialog"))
        assertFalse(page.contains("ScrollActionButton("))
        assertFalse(page.contains("HideOnScrollFAB("))
        assertTrue(page.contains("showCreatePlaylistDialog = true"))
    }

    @Test fun bookmarkFabIsOnOnlinePlaylistNotOnlyCreatePlaylistLibrary() {
        val remote = source("ui/screens/playlist/OnlinePlaylistScreen.kt")
        val save = remote.substringAfter("val toggleLibraryBookmark: () -> Unit =")
            .substringBefore("val surfaceColor =")
        assertTrue("Shared playlist transaction required", save.contains("database.transaction"))
        assertTrue("Both hero and FAB must share the same bookmark action",
            remote.contains("onClick = toggleLibraryBookmark,"))
        assertTrue("The floating save button must stay above nav and mini-player",
            remote.contains("PersistentPlaylistAction("))
        assertTrue(remote.contains("R.drawable.library_add_check else R.drawable.library_add"))
        val container = source("ui/component/AlbumLayout.kt")
        val album = source("ui/screens/AlbumScreen.kt")
        assertTrue("Album vertical LazyColumn owns snap", container.contains("flingBehavior = flingBehavior"))
        assertTrue(album.contains("flingBehavior = rememberDetailHeaderFlingBehavior(titleMotion)"))
        assertTrue("Album horizontal suggestion shelf must retain normal fling",
            album.contains("LazyRow(\n") && album.contains("flingBehavior = rememberCapsuleFlingBehavior()"))
    }

    @Test fun accountIsCircularAndAllMainAndDetailHeadersUseTheSameRule() {
        val chrome = source("ui/component/StandardChrome.kt")
        val title = chrome.substringAfter("fun StandardHeaderTitle(")
        assertTrue(title.contains("Modifier.size(44.dp)"))
        assertTrue(title.contains("Modifier.size(30.dp)"))
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
