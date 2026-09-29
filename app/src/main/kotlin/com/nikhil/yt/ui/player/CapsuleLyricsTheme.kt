/*
 * Capsule MUSIC
 * Shared policy for translating the visible player backdrop into Lyrics.
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

import com.nikhil.yt.constants.CapsulePlayerDesign
import com.nikhil.yt.constants.PlayerBackgroundStyle

internal sealed interface CapsuleLyricsBackdrop {
    data object Solid : CapsuleLyricsBackdrop

    data object ImmersiveColoring : CapsuleLyricsBackdrop

    data class PlayerTheme(
        val style: PlayerBackgroundStyle,
    ) : CapsuleLyricsBackdrop
}

/**
 * Lyrics follows the exact full-player backdrop for Dense/Light.
 *
 * Immersive is intentionally the only exception: the Immersive player does not use
 * PlayerBackgroundStyle at all; its artwork-driven floor is represented in Lyrics by the dedicated
 * coloring treatment.
 */
internal fun capsuleLyricsBackdrop(
    usePlayerTheme: Boolean,
    playerDesign: CapsulePlayerDesign,
    playerBackground: PlayerBackgroundStyle,
): CapsuleLyricsBackdrop =
    when {
        !usePlayerTheme ->
            CapsuleLyricsBackdrop.Solid

        playerDesign == CapsulePlayerDesign.IMMERSIVE ->
            CapsuleLyricsBackdrop.ImmersiveColoring

        else ->
            CapsuleLyricsBackdrop.PlayerTheme(playerBackground)
    }
