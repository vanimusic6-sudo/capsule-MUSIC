/*
 * Capsule MUSIC
 * Lightweight background renderer shared by Capsule Player.
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.nikhil.yt.constants.PlayerBackgroundStyle

@Composable
fun PlayerBackground(
    playerBackground: PlayerBackgroundStyle,
    gradientColors: List<Color>,
) {
    val modifier = Modifier.fillMaxSize()

    when (playerBackground) {
        PlayerBackgroundStyle.DEFAULT -> Unit

        PlayerBackgroundStyle.GRADIENT ->
            CapsuleProceduralBackground(
                effect = CapsuleBackgroundEffect.MATTE_GRADIENT,
                colors = gradientColors,
                modifier = modifier,
                animated = false,
            )

        PlayerBackgroundStyle.COLORING ->
            CapsuleProceduralBackground(
                effect = CapsuleBackgroundEffect.TONAL_WASH,
                colors = gradientColors,
                modifier = modifier,
                animated = false,
            )

        PlayerBackgroundStyle.GLOW ->
            CapsuleProceduralBackground(
                effect = CapsuleBackgroundEffect.AMBIENT_GLOW,
                colors = gradientColors,
                modifier = modifier,
                animated = false,
            )

        PlayerBackgroundStyle.GLOW_ANIMATED ->
            CapsuleProceduralBackground(
                effect = CapsuleBackgroundEffect.COLOR_FLOW,
                colors = gradientColors,
                modifier = modifier,
            )

        PlayerBackgroundStyle.CAPSULE_STAR ->
            CapsuleProceduralBackground(
                effect = CapsuleBackgroundEffect.CAPSULE_STAR,
                colors = gradientColors,
                modifier = modifier,
            )

        PlayerBackgroundStyle.NEBULA ->
            CapsuleProceduralBackground(
                effect = CapsuleBackgroundEffect.NEBULA,
                colors = gradientColors,
                modifier = modifier,
            )
    }
}
