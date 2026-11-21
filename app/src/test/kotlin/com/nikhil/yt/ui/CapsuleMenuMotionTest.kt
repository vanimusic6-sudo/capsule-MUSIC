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
        assertTrue(actions.contains("heightIn(min = CapsuleMenuMinActionHeight, max = CapsuleMenuMinActionHeight)"))
        assertTrue(actions.contains("CapsuleMenuMinActionHeight = 100.dp"))
        assertTrue(actions.contains("CapsuleMenuMinRowHeight = 64.dp"))
        assertTrue(actions.contains("maxLines = 2"))
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
            assertTrue("$name uses the common actions grid", source.contains("NewActionGrid("))
            assertFalse("$name must not introduce its own competing grid padding",
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
        // Long and short labels now use the SAME column orientation and height.
        val source = listOf(
            java.io.File("src/main/kotlin/com/nikhil/yt/ui/component/NewMenuComponents.kt"),
            java.io.File("app/src/main/kotlin/com/nikhil/yt/ui/component/NewMenuComponents.kt"),
        ).first(java.io.File::isFile).readText()
        assertFalse(source.contains("LocalCapsuleMenuGroupedRow provides true"))
        assertTrue(source.contains("Column(modifier = modifier.fillMaxWidth(), content = content)"))
        assertTrue(source.contains("CapsuleMenuRowPadding = 4.dp"))
        assertTrue(source.contains("CapsuleMenuTileGap = 8.dp"))
        assertTrue(source.contains("Box(Modifier.fillMaxWidth().padding(vertical = CapsuleMenuRowPadding))"))
        assertTrue(source.contains("softWrap = true"))
        assertTrue(source.contains("internal val CapsuleMenuTileShape = RoundedCornerShape(18.dp)"))
        assertTrue(source.contains("highlightBorder: Boolean = false"))
    }

    @Test fun allMenuIconsUseGoogleMaterialSymbolsRounded() {
        val names = listOf(
            "album", "artist", "bedtime", "delete", "download", "edit", "fire",
            "info", "library_add", "library_add_check", "link", "music_note",
            "play", "playlist_add", "playlist_play", "queue_music", "radio",
            "select_all", "share", "shuffle", "sync",
        )
        for (name in names) {
            val path = "res/drawable/capsule_soft_${name}.xml"
            val file = listOf(
                java.io.File("src/main/$path"),
                java.io.File("app/src/main/$path"),
            ).first(java.io.File::isFile)
            val xml = file.readText()
            assertTrue("Material icon attribution missing for $name",
                xml.contains("Google Material Symbols Rounded"))
            assertTrue("Apache-2.0 icon license missing for $name",
                xml.contains("Apache-2.0"))
            assertTrue("$name must be a 24dp vector",
                xml.contains("android:viewportWidth=\"960\""))
            assertFalse("$name must not contain hand-drawn stroke paths",
                xml.contains("android:strokeWidth="))
        }
    }

    @Test fun independentMenuRowsAreNotClickableAcrossTheirGaps() {
        val path = "com/nikhil/yt/ui/component/NewMenuComponents.kt"
        val source = listOf(java.io.File("src/main/kotlin/$path"),
            java.io.File("app/src/main/kotlin/$path"))
            .first(java.io.File::isFile).readText()
        val row = source.substringAfter("fun CapsuleMenuListItem(")
            .substringBefore("// All menu rows delegate")
        assertTrue(row.contains("Box(Modifier.fillMaxWidth().padding(vertical = CapsuleMenuRowPadding))"))
        assertTrue(row.contains(".then(modifier)"))
        assertTrue(row.indexOf(".clip(CapsuleMenuTileShape)") < row.indexOf(".then(modifier)"))
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

    @Test fun gridKeepsOddLastItemAtSingleColumnWidth() {
        val source = listOf(
            java.io.File("src/main/kotlin/com/nikhil/yt/ui/component/NewMenuComponents.kt"),
            java.io.File("app/src/main/kotlin/com/nikhil/yt/ui/component/NewMenuComponents.kt"),
        ).first(java.io.File::isFile).readText()
        assertTrue(source.contains("repeat(safeColumns - row.size)"))
        assertFalse(source.contains("repeat(if (compact) 0"))
    }

    @Test fun playerAudioControlsUseOneGridAndPaddedVolumeSurface() {
        val source = listOf(
            java.io.File("src/main/kotlin/com/nikhil/yt/ui/menu/PlayerMenu.kt"),
            java.io.File("app/src/main/kotlin/com/nikhil/yt/ui/menu/PlayerMenu.kt"),
        ).first(java.io.File::isFile).readText()
        assertFalse(source.contains("settings/music_together"))
        assertTrue(source.contains("painterResource(R.drawable.graphic_eq)"))
        assertTrue(source.contains("showEqualizerDialog = true"))
        assertTrue(source.contains("heightIn(min = 112.dp)"))
        assertTrue(source.contains("R.drawable.radio"))
    }

    @Test fun lyricsControlsStayCompactWithoutNestedExpansion() {
        val files = listOf(
            java.io.File("src/main/kotlin/com/nikhil/yt/ui/menu/LyricsTuning.kt"),
            java.io.File("app/src/main/kotlin/com/nikhil/yt/ui/menu/LyricsTuning.kt"),
        )
        val tuning = files.first(java.io.File::isFile).readText()
        assertTrue(tuning.contains("LyricsTuningTileHeight = 52.dp"))
        assertTrue(tuning.contains("onPositionChange(value)"))
        assertTrue(tuning.contains("onOffsetChange(clampOffset("))
        assertFalse(tuning.contains("showOffsetDialog"))
        val path = "com/nikhil/yt/ui/menu/LyricsMenu.kt"
        val lyrics = listOf(java.io.File("src/main/kotlin/$path"),
            java.io.File("app/src/main/kotlin/$path"))
            .first(java.io.File::isFile).readText()
        assertTrue(lyrics.indexOf("item(key = \"lyrics-tuning\"") <
            lyrics.indexOf("item(key = \"lyrics-actions\""))
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
