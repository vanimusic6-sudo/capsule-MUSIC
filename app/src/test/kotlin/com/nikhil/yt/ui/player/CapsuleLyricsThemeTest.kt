package com.nikhil.yt.ui.player

import com.nikhil.yt.constants.CapsulePlayerDesign
import com.nikhil.yt.constants.PlayerBackgroundStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleLyricsThemeTest {
    @Test
    fun denseAndLightSupportEveryPlayerBackgroundStyle() {
        listOf(
            CapsulePlayerDesign.SUPER,
            CapsulePlayerDesign.LIGHT,
        ).forEach { design ->
            PlayerBackgroundStyle.entries.forEach { style ->
                assertEquals(
                    CapsuleLyricsBackdrop.PlayerTheme(style),
                    capsuleLyricsBackdrop(
                        usePlayerTheme = true,
                        playerDesign = design,
                        playerBackground = style,
                    ),
                )
            }
        }
    }

    @Test
    fun immersiveKeepsItsDedicatedArtworkColoring() {
        PlayerBackgroundStyle.entries.forEach { style ->
            assertEquals(
                CapsuleLyricsBackdrop.ImmersiveColoring,
                capsuleLyricsBackdrop(
                    usePlayerTheme = true,
                    playerDesign = CapsulePlayerDesign.IMMERSIVE,
                    playerBackground = style,
                ),
            )
        }
    }

    @Test
    fun disabledPlayerThemeUsesSolidLyricsSurface() {
        PlayerBackgroundStyle.entries.forEach { style ->
            assertTrue(
                capsuleLyricsBackdrop(
                    usePlayerTheme = false,
                    playerDesign = CapsulePlayerDesign.LIGHT,
                    playerBackground = style,
                ) is CapsuleLyricsBackdrop.Solid,
            )
        }
    }
}
