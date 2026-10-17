/**
 * Velune Project (C) 2026
 * Capsule Theme visual layer
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.nikhil.yt.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.staticCompositionLocalOf
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
 * One scoped marker for generic chrome (Home, tabs, list rows).
 * Player designs can retain their own intentional, artwork-led colors.
 */
val LocalCapsuleLightTheme = staticCompositionLocalOf { false }

/**
 * Capsule Light: quiet, warm-neutral surfaces and graphite foreground.
 * Never modifies the existing Capsule dark palette. Never derives colors
 * from track artwork or starts a new animation.
 */
fun ColorScheme.capsuleLight(): ColorScheme = copy(
    primary = Color(0xFF30343A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE5E6E2),
    onPrimaryContainer = Color(0xFF24272C),
    inversePrimary = Color(0xFFDADCE0),

    secondary = Color(0xFF555D62),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E9E8),
    onSecondaryContainer = Color(0xFF263133),

    tertiary = Color(0xFF61625A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEAE8DC),
    onTertiaryContainer = Color(0xFF34352C),

    background = Color(0xFFF7F7F4),
    onBackground = Color(0xFF222529),
    surface = Color(0xFFF7F7F4),
    onSurface = Color(0xFF222529),
    surfaceVariant = Color(0xFFE6E8E5),
    onSurfaceVariant = Color(0xFF585F64),

    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF303439),
    inverseOnSurface = Color(0xFFF7F7F4),

    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DFDA),
    onErrorContainer = Color(0xFF68130E),

    outline = Color(0xFF858D92),
    outlineVariant = Color(0xFFD4D9D6),
    scrim = Color.Black,

    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE0E2DF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F2EE),
    surfaceContainer = Color(0xFFEAEBE7),
    surfaceContainerHigh = Color(0xFFE4E6E2),
    surfaceContainerHighest = Color(0xFFDCE0DC),
)

/**
 * Semantic colors for Capsule's own compact chrome in LIGHT mode.
 *
 * Both the Capsule dock and the THEME mini-player background were previously
 * drawing the dark Capsule material even while the root correctly resolved
 * the new Capsule Light scheme. This is derived from the already-selected
 * ColorScheme, never from the separate full-screen Light player design.
 */
internal data class CapsuleLightCompactTokens(
    val surfaceStart: Color,
    val surfaceEnd: Color,
    val outline: Color,
    val indicator: Color,
    val selectedContent: Color,
    val mutedContent: Color,
)

internal fun capsuleLightCompactTokens(scheme: ColorScheme): CapsuleLightCompactTokens =
    CapsuleLightCompactTokens(
        surfaceStart = scheme.surfaceContainerLow,
        surfaceEnd = scheme.surfaceContainerHigh,
        outline = scheme.outlineVariant,
        indicator = scheme.primaryContainer,
        selectedContent = scheme.onPrimaryContainer,
        mutedContent = scheme.onSurfaceVariant,
    )

private val CapsuleBackground = Color(0xFF141414)
private val CapsuleSurface = Color(0xFF141414)
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
