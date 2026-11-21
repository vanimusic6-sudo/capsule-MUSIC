package com.nikhil.yt.ui

import com.nikhil.yt.ui.component.MenuPresentation
import com.nikhil.yt.ui.component.MenuState
import com.nikhil.yt.ui.component.capsuleMenuElasticX
import com.nikhil.yt.ui.component.capsuleMenuElasticY
import com.nikhil.yt.ui.component.capsuleMenuDismissScale
import com.nikhil.yt.ui.component.capsuleMenuTopFill
import com.nikhil.yt.ui.component.capsuleMenuOpaqueSurface
import com.nikhil.yt.ui.component.capsuleMenuOpaqueTile
import com.nikhil.yt.ui.component.capsuleActionGridColumns
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The original Material3 sheet retains the real anchored gesture owner. */
class CapsuleMenuMotionTest {
    @Test fun newAcrylicIsStaticAndAllLegacyMenusShareReadableRows() {
        val component = listOf(
            java.io.File("src/main/kotlin/com/nikhil/yt/ui/component/NewMenuComponents.kt"),
            java.io.File("app/src/main/kotlin/com/nikhil/yt/ui/component/NewMenuComponents.kt"),
        ).first(java.io.File::isFile).readText()
        assertTrue(component.contains("compact: Boolean = true"))
        assertTrue(component.contains("fun CapsuleMenuListItem("))
        assertTrue(component.contains("containerColor = Color.Transparent"))
        for (name in listOf(
            "SongMenu", "PlaylistMenu", "PlayerMenu", "YouTubePlaylistMenu",
            "YouTubeAlbumMenu", "ArtistMenu", "YouTubeArtistMenu",
            "SelectionSongsMenu", "OfflineDownloadMenu",
        )) {
            val path = "com/nikhil/yt/ui/menu/$name.kt"
            val source = listOf(java.io.File("src/main/kotlin/$path"),
                java.io.File("app/src/main/kotlin/$path"))
                .first(java.io.File::isFile).readText()
            assertTrue("$name must use shared rounded actions",
                source.contains("CapsuleMenuListItem as ListItem"))
        }
        for (name in listOf("AlbumMenu", "YouTubeSongMenu")) {
            val path = "com/nikhil/yt/ui/menu/$name.kt"
            val source = listOf(java.io.File("src/main/kotlin/$path"),
                java.io.File("app/src/main/kotlin/$path"))
                .first(java.io.File::isFile).readText()
            assertTrue("$name uses the common acrylic row",
                source.contains("CapsuleMenuListItem("))
        }
        val sheet = listOf(
            java.io.File("src/main/kotlin/com/nikhil/yt/ui/component/BottomSheetMenu.kt"),
            java.io.File("app/src/main/kotlin/com/nikhil/yt/ui/component/BottomSheetMenu.kt"),
        ).first(java.io.File::isFile).readText()
        assertTrue(sheet.contains("capsuleMenuGrain"))
        assertTrue(sheet.contains(".drawWithCache"))
        assertTrue(sheet.contains("containerColor = sheetColor,"))
        assertFalse(sheet.contains("pageWash"))
        assertFalse(sheet.contains("lipSheen"))
        assertFalse(sheet.contains("topGlow"))
        assertFalse(sheet.contains("sideWave"))
        assertFalse(sheet.contains("openingJelly"))
        assertFalse(sheet.contains("hazeSource("))
        assertFalse(sheet.contains("hazeBlur("))
    }

    @Test fun everyMenuActionHasOneBlackMaterialAndNoNestedGreyIconSquare() {
        assertEquals(Color(0xFF111113),
            com.nikhil.yt.ui.component.CapsuleMenuBlack)
        val actions = listOf(
            java.io.File("src/main/kotlin/com/nikhil/yt/ui/component/NewMenuComponents.kt"),
            java.io.File("app/src/main/kotlin/com/nikhil/yt/ui/component/NewMenuComponents.kt"),
        ).first(java.io.File::isFile).readText()
        assertTrue(actions.contains("heightIn(min = 74.dp)"))
        assertTrue(actions.contains("maxLines = 3"))
        assertFalse(actions.contains("color = CapsuleMenuBlack"))
        assertFalse(actions.contains("primary.copy(alpha = 0.09f)"))
        assertFalse(actions.contains("primary.copy(alpha = 0.075f)"))
    }

    @Test fun everyActionGridSharesTheSameOuterHorizontalEdges() {
        for (name in listOf(
            "SongMenu", "PlaylistMenu", "YouTubeSongMenu",
            "YouTubePlaylistMenu", "AlbumMenu", "YouTubeAlbumMenu",
            "SelectionSongsMenu",
        )) {
            val relative = "com/nikhil/yt/ui/menu/$name.kt"
            val source = listOf(
                java.io.File("src/main/kotlin/$relative"),
                java.io.File("app/src/main/kotlin/$relative"),
            ).first(java.io.File::isFile).readText()
            assertTrue("$name should align its actions with black list rows",
                source.contains("modifier = Modifier.padding(horizontal = 0.dp, vertical = 8.dp)"))
        }
    }

    @Test fun modalSheetNeverReliesOnATransparentBackground() {
        val surface = Color(0xFF101015)
        val highSurface = Color(0xFF303038)
        val resolved = capsuleMenuOpaqueSurface(surface, surface, highSurface)
        assertEquals(highSurface, resolved)
        assertEquals(1f, resolved.alpha, 0.0001f)
        val translucentCustom = Color(0xFF4F435A).copy(alpha = 0.1f)
        val customResolved = capsuleMenuOpaqueSurface(translucentCustom, surface, highSurface)
        assertEquals(1f, customResolved.alpha, 0.0001f)
        assertEquals(translucentCustom.red, customResolved.red, 0.0001f)
    }

    @Test fun acrylicActionTilesHaveBoundedTransparencyAndReadablePress() {
        val normal = Color(0xFF404047).copy(alpha = 0.065f)
        val selected = Color(0xFF515161).copy(alpha = 0.13f)
        val idle = capsuleMenuOpaqueTile(normal, selected, pressed = false)
        val down = capsuleMenuOpaqueTile(normal, selected, pressed = true)
        assertEquals(1f, idle.alpha, 0.0001f)
        assertEquals(1f, down.alpha, 0.0001f)
        assertEquals(normal.red, idle.red, 0.0001f)
        assertEquals(selected.blue, down.blue, 0.0001f)
    }

    @Test fun longSingleWordsWrapAndSectionsNeverFuseActions() {
        assertTrue(com.nikhil.yt.ui.component.capsuleCompactMenuStackedLabel("Воспроизвести"))
        assertFalse(com.nikhil.yt.ui.component.capsuleCompactMenuStackedLabel("Начать радио"))
        val source = listOf(
            java.io.File("src/main/kotlin/com/nikhil/yt/ui/component/NewMenuComponents.kt"),
            java.io.File("app/src/main/kotlin/com/nikhil/yt/ui/component/NewMenuComponents.kt"),
        ).first(java.io.File::isFile).readText()
        assertFalse(source.contains("LocalCapsuleMenuGroupedRow provides true"))
        assertTrue(source.contains("Column(modifier = modifier.fillMaxWidth(), content = content)"))
        assertTrue(source.contains("Modifier.padding(vertical = 5.dp)"))
        assertTrue(source.contains("softWrap = true"))
        assertTrue(source.contains("internal val CapsuleMenuTileShape = RoundedCornerShape(18.dp)"))
        assertTrue(source.contains("highlightBorder: Boolean = false"))
    }

    @Test fun downloadTileIsOutlinedInEveryDownloadMenu() {
        val player = listOf(
            java.io.File("src/main/kotlin/com/nikhil/yt/ui/menu/PlayerMenu.kt"),
            java.io.File("app/src/main/kotlin/com/nikhil/yt/ui/menu/PlayerMenu.kt"),
        ).first(java.io.File::isFile).readText()
        val download = listOf(
            java.io.File("src/main/kotlin/com/nikhil/yt/ui/menu/CapsuleDownloadAction.kt"),
            java.io.File("app/src/main/kotlin/com/nikhil/yt/ui/menu/CapsuleDownloadAction.kt"),
        ).first(java.io.File::isFile).readText()
        val offline = listOf(
            java.io.File("src/main/kotlin/com/nikhil/yt/ui/menu/OfflineDownloadMenu.kt"),
            java.io.File("app/src/main/kotlin/com/nikhil/yt/ui/menu/OfflineDownloadMenu.kt"),
        ).first(java.io.File::isFile).readText()
        assertTrue(player.contains("highlightBorder = true"))
        assertTrue(download.contains("CapsuleMenuListItem("))
        assertTrue(download.contains("highlightBorder = true"))
        assertTrue(offline.contains("highlightBorder = destructive"))
    }

    @Test fun narrowPlaylistActionsNeverBreakLongRussianWordsIntoThreeTiles() {
        assertEquals(2, capsuleActionGridColumns(
            3, 360, listOf("Воспроизвести", "Перемешать", "Поделиться"),
        ))
        assertEquals(3, capsuleActionGridColumns(
            3, 360, listOf("Изменить", "Обновить", "Поиск"),
        ))
        assertEquals(3, capsuleActionGridColumns(
            3, 620, listOf("Воспроизвести", "Перемешать", "Поделиться"),
        ))
        assertEquals(1, capsuleActionGridColumns(
            0, 360, listOf("Воспроизвести"),
        ))
        // A compact player menu must stay at two columns even on tablets:
        // the 5-item layout uses two short rows and one full-width action.
        assertEquals(2, capsuleActionGridColumns(
            3, 700, listOf("Начать радио", "Добавить в плейлист"), compact = true,
        ))
        assertEquals(1, capsuleActionGridColumns(
            1, 350, listOf("Радио"), compact = true,
        ))
    }

    @Test fun nativePlayerMenuIsKeptUntilMaterialHideCompletes() {
        val state = MenuState()
        state.show(presentation = MenuPresentation.CAPSULE_PLAYER) {}
        assertTrue(state.isVisible)
        state.dismiss()
        assertTrue("closing should not abruptly tear down the original sheet", state.isVisible)
        assertTrue(state.dismissRequested)
        state.completeAnimatedDismiss()
        assertFalse(state.isVisible)
        assertFalse(state.dismissRequested)
    }

    @Test fun reopeningCancelsStaleHideAndPreservesPresentation() {
        val state = MenuState()
        state.show(presentation = MenuPresentation.CAPSULE_PLAYER) {}
        state.dismiss()
        state.show(presentation = MenuPresentation.CAPSULE_PLAYER) {}
        assertFalse(state.dismissRequested)
        state.completeAnimatedDismiss()
        assertTrue("stale close completion cannot dismiss a reopened menu", state.isVisible)
    }

    @Test fun oldCloseCannotCompleteANewerCloseAfterRapidReopen() {
        val state = MenuState()
        state.show {}
        val old = state.generation
        state.dismiss()
        state.show {}
        state.dismiss()
        state.completeAnimatedDismiss(old)
        assertTrue(state.isVisible)
        assertTrue(state.dismissRequested)
        state.completeAnimatedDismiss(state.generation)
        assertFalse(state.isVisible)
    }

    @Test fun allMenusUseThePlayersCommittedCloseLifecycle() {
        val state = MenuState()
        state.show {}
        state.dismiss()
        assertTrue(state.isVisible)
        assertTrue(state.dismissRequested)
        state.completeAnimatedDismiss()
        assertFalse(state.isVisible)
    }

    @Test fun elasticEffectIsZeroAtRestAndBoundedAtAllFrames() {
        assertEquals(1f, capsuleMenuElasticX(0f), 0.00001f)
        assertEquals(1f, capsuleMenuElasticY(0f), 0.00001f)
        for (sample in -120..120) {
            val deform = sample / 1000f
            assertTrue(capsuleMenuElasticX(deform) in 0.99f..1.01f)
            assertTrue(capsuleMenuElasticY(deform) in 0.95f..1.05f)
        }
    }
    @Test fun appleClosePullStartsAtFullSizeAndShrinksSmoothlyWithoutJump() {
        assertEquals(1f, capsuleMenuDismissScale(0f), 0.00001f)
        assertEquals(0.945f, capsuleMenuDismissScale(1f), 0.00001f)
        var previous = 1f
        for (frame in 1..60) {
            val now = capsuleMenuDismissScale(frame / 60f)
            assertTrue(now <= previous + 0.00001f)
            assertTrue("frame gap must be small on 60Hz", previous - now < 0.003f)
            previous = now
        }
    }

    @Test fun expandingTopHazeFillsWithoutFlashingOrJumps() {
        assertEquals(0f, capsuleMenuTopFill(Float.NaN, 1000f), 0.0001f)
        assertEquals(0f, capsuleMenuTopFill(1000f, 1000f), 0.0001f)
        var previous = 0f
        for (i in 0..100) {
            val offset = 1000f * (1f - i / 100f)
            val alpha = capsuleMenuTopFill(offset, 1000f)
            assertTrue(alpha + 0.00001f >= previous)
            assertTrue(alpha in 0f..0.30f)
            assertTrue(alpha - previous < 0.02f)
            previous = alpha
        }
        assertEquals(0.30f, previous, 0.0001f)
    }

}
