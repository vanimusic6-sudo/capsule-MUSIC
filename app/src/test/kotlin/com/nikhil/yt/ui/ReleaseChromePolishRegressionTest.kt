package com.nikhil.yt.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.nikhil.yt.ui.component.capsuleTopBarRuleAlpha
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

    @Test fun magneticDockingAndFlyingCaptionAreRemovedEverywhere() {
        val screens = listOf(
            "ui/screens/AlbumScreen.kt",
            "ui/screens/playlist/LocalPlaylistScreen.kt",
            "ui/screens/playlist/OnlinePlaylistScreen.kt",
            "ui/screens/playlist/AutoPlaylistScreen.kt",
            "ui/screens/playlist/CachePlaylistScreen.kt",
            "ui/screens/playlist/TopPlaylistScreen.kt",
        )
        screens.forEach { path ->
            val page = source(path)
            assertTrue("Native list fling is restored: $path",
                page.contains("flingBehavior = rememberCapsuleFlingBehavior()"))
            assertFalse(page.contains("rememberDetailHeaderFlingBehavior"))
            assertFalse(page.contains("rememberDetailHeaderPreFlingConnection"))
        }
        val title = source("ui/component/DetailToolbarReveal.kt")
        assertFalse(title.contains("Animatable"))
        assertFalse(title.contains("class DetailHeaderSingleOwnerFlingBehavior"))
        assertFalse(title.contains("internal fun detailTitleGeometry("))
        assertFalse(title.contains("fun drawTitle("))
        assertTrue(title.contains("internal fun detailTitleOcclusionFraction("))
        val cover = source("ui/component/AlbumArtwork.kt")
        assertFalse(cover.contains("overlayReady()"))
        assertTrue(cover.contains("motion?.coverTitleAlpha()"))
        assertTrue(title.contains("alpha = motion.toolbarTitleAlpha()"))
    }

    @Test fun hairlineIsSubtleAndRevealsSmoothlyWithTheArtwork() {
        assertEquals(0f, capsuleTopBarRuleAlpha(0f), 0f)
        assertTrue(capsuleTopBarRuleAlpha(.61f) > 0f)
        assertEquals(1f, capsuleTopBarRuleAlpha(1f), 0f)
        val samples = (0..100).map { capsuleTopBarRuleAlpha(it / 100f) }
        assertTrue(samples.zipWithNext().all { (a, b) -> b >= a && b - a < .065f })
    }

    @Test fun photoToToolbarRestoresAContinuousFadeWithoutSuddenBlackBand() {
        val helper = source("ui/component/ArtistToolbar.kt")
        assertTrue(helper.contains("val t = ((artworkFade.coerceIn(0f, 1f)"))
        assertFalse(helper.contains("return if (artworkFade >= 1f) 1f else 0f"))
        val fade = source("ui/component/ArtworkSurfaceFade.kt")
        assertTrue(fade.contains("val start = 0.36f"))
        assertTrue(fade.contains("ease.pow(1.65f)"))
        val bar = source("ui/component/DetailToolbarReveal.kt")
        assertTrue(bar.contains("fun hairlineReveal(): Float = photoProgress"))
        assertTrue(bar.contains("if (forceOpaque) 1f else motion.hairlineReveal()"))
        assertFalse("A second delayed hairline animation makes the UI feel stuck",
            bar.contains("durationMillis = 350"))
        assertFalse("Line must not follow an unrelated artwork-opacity curve",
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
        assertTrue("Album vertical LazyColumn owns native scroll", container.contains("flingBehavior = flingBehavior"))
        assertTrue(album.contains("flingBehavior = rememberCapsuleFlingBehavior()"))
        assertTrue("Album horizontal suggestion shelf must retain normal fling",
            album.contains("LazyRow(\n") && album.contains("flingBehavior = rememberCapsuleFlingBehavior()"))
    }

    @Test fun artistTopTracksHairlineDrawsAboveItsOpaqueMaterialBar() {
        val chrome = source("ui/component/StandardChrome.kt")
        val songs = source("ui/screens/artist/ArtistSongsScreen.kt")
        assertTrue(songs.contains("modifier = Modifier.capsuleTopBarHairline(StandardChrome.muted)"))
        assertTrue("Separator must draw over the opaque Material3 surface",
            chrome.contains("Modifier = drawWithContent {"))
        assertTrue(chrome.contains("drawContent()"))
        assertTrue(chrome.contains("color.copy(alpha = 0.24f * opacity)"))
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
