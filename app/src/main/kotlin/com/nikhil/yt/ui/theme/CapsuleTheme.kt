/**
 * Velune Project (C) 2026
 * Capsule Theme visual layer
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.nikhil.yt.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.booleanPreferencesKey

/**
 * Enables the Capsule visual language.
 *
 * This setting is intentionally independent from Velune's existing
 * Dynamic Theme / palette / Dark Mode settings.
 *
 * Turning Capsule Theme off restores the previous Velune appearance
 * without rewriting those settings.
 */
val CapsuleThemeEnabledKey =
    booleanPreferencesKey("capsuleThemeEnabled")

/**
 * Reserved for the next Capsule UI stage:
 * Capsule Mini Player + Capsule Dock.
 *
 * It is intentionally not used yet.
 */
val CapsuleBottomBarEnabledKey =
    booleanPreferencesKey("capsuleBottomBarEnabled")

private val CapsuleBackground = Color(0xFF101010)
private val CapsuleSurface = Color(0xFF101010)
private val CapsuleSurfaceDim = Color(0xFF0C0C0C)
private val CapsuleSurfaceBright = Color(0xFF242424)

private val CapsuleContainerLowest = Color(0xFF0B0B0B)
private val CapsuleContainerLow = Color(0xFF151515)
private val CapsuleContainer = Color(0xFF1B1B1B)
private val CapsuleContainerHigh = Color(0xFF202020)
private val CapsuleContainerHighest = Color(0xFF272727)

private val CapsuleText = Color(0xFFF0F0F0)
private val CapsuleTextMuted = Color(0xFF858585)

private val CapsulePrimary = Color(0xFFECECEC)
private val CapsuleOnPrimary = Color(0xFF111111)
private val CapsulePrimaryContainer = Color(0xFF292929)
private val CapsuleOnPrimaryContainer = Color(0xFFF2F2F2)

private val CapsuleSecondary = Color(0xFF9A9A9A)
private val CapsuleOnSecondary = Color(0xFF111111)
private val CapsuleSecondaryContainer = Color(0xFF242424)
private val CapsuleOnSecondaryContainer = Color(0xFFE4E4E4)

private val CapsuleTertiary = Color(0xFFB7B7B7)
private val CapsuleOnTertiary = Color(0xFF111111)
private val CapsuleTertiaryContainer = Color(0xFF303030)
private val CapsuleOnTertiaryContainer = Color(0xFFF1F1F1)

private val CapsuleOutline = Color(0xFF414141)
private val CapsuleOutlineVariant = Color(0xFF2A2A2A)

private val CapsuleError = Color(0xFFFF8A8A)
private val CapsuleOnError = Color(0xFF390000)
private val CapsuleErrorContainer = Color(0xFF5A2020)
private val CapsuleOnErrorContainer = Color(0xFFFFDADA)

/**
 * Capsule Theme is dark-only.
 *
 * Only the Material color scheme is changed here.
 * Playback, queue, database, networking, Innertube,
 * stream resolution, cache and navigation are untouched.
 */
fun ColorScheme.capsule(
    pureBlack: Boolean = false,
): ColorScheme {
    val capsuleBackground =
        if (pureBlack) Color.Black else CapsuleBackground

    val capsuleSurface =
        if (pureBlack) Color.Black else CapsuleSurface

    return copy(
        primary = CapsulePrimary,
        onPrimary = CapsuleOnPrimary,
        primaryContainer = CapsulePrimaryContainer,
        onPrimaryContainer = CapsuleOnPrimaryContainer,
        inversePrimary = Color(0xFF555555),

        secondary = CapsuleSecondary,
        onSecondary = CapsuleOnSecondary,
        secondaryContainer = CapsuleSecondaryContainer,
        onSecondaryContainer = CapsuleOnSecondaryContainer,

        tertiary = CapsuleTertiary,
        onTertiary = CapsuleOnTertiary,
        tertiaryContainer = CapsuleTertiaryContainer,
        onTertiaryContainer = CapsuleOnTertiaryContainer,

        background = capsuleBackground,
        onBackground = CapsuleText,

        surface = capsuleSurface,
        onSurface = CapsuleText,
        surfaceVariant = CapsuleContainerHigh,
        onSurfaceVariant = CapsuleTextMuted,

        surfaceTint = Color.Transparent,

        inverseSurface = Color(0xFFEAEAEA),
        inverseOnSurface = Color(0xFF161616),

        error = CapsuleError,
        onError = CapsuleOnError,
        errorContainer = CapsuleErrorContainer,
        onErrorContainer = CapsuleOnErrorContainer,

        outline = CapsuleOutline,
        outlineVariant = CapsuleOutlineVariant,

        scrim = Color.Black,

        surfaceBright = CapsuleSurfaceBright,
        surfaceDim = CapsuleSurfaceDim,

        surfaceContainer = CapsuleContainer,
        surfaceContainerHigh = CapsuleContainerHigh,
        surfaceContainerHighest = CapsuleContainerHighest,
        surfaceContainerLow = CapsuleContainerLow,
        surfaceContainerLowest = CapsuleContainerLowest,
    )
}
