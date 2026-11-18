/** Capsule MUSIC — compact artist identity row for Light and Immersion. GPL-3.0. */
package com.nikhil.yt.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nikhil.yt.models.MediaMetadata

/**
 * Whole-name fit. Never crop a second artist halfway or replace them with an ellipsis.
 * If the remaining complete names do not fit, count *all* undisplayed artists instead.
 */
internal fun capsuleArtistLabel(
    names: List<String>,
    widthPx: Int,
    measurePx: (String) -> Int,
): String {
    if (names.isEmpty()) return ""
    val limit = (widthPx - 2).coerceAtLeast(0)
    val all = names.joinToString(" • ")
    if (measurePx(all) <= limit) return all
    for (shown in names.size - 1 downTo 0) {
        val remaining = names.size - shown
        val suffix = "ещё $remaining"
        val candidate =
            if (shown > 0) names.take(shown).joinToString(" • ") + " • " + suffix else suffix
        if (measurePx(candidate) <= limit) return candidate
    }
    val compact = "ещё ${names.size}"
    return if (measurePx(compact) <= limit) compact else ""
}

/**
 * Text-only artist credits: no avatar before the nickname, no extra
 * network portrait lookup or bitmap decode inside the player.
 */
@Composable
internal fun CapsuleArtistCredits(
    artists: List<MediaMetadata.Artist>,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val credits = remember(artists) { artists.takeIf { it.isNotEmpty() }.orEmpty() }
    val measurer = rememberTextMeasurer(cacheSize = 12)
    val style = remember {
        TextStyle(
            fontSize = CapsulePlayerArtistFontSize,
            lineHeight = CapsulePlayerArtistLineHeight,
            fontWeight = FontWeight.Normal,
        )
    }
    val interaction = remember { MutableInteractionSource() }
    BoxWithConstraints(
        modifier = modifier.clickable(
            enabled = enabled,
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
        ),
    ) {
        val availableTextPx = with(LocalDensity.current) {
            maxWidth.coerceAtLeast(0.dp).roundToPx()
        }
        val label = remember(credits, availableTextPx, style) {
            capsuleArtistLabel(credits.map { it.name }, availableTextPx) { candidate ->
                measurer.measure(text = candidate, style = style, maxLines = 1, softWrap = false).size.width
            }
        }
        Text(
            text = label,
            color = color,
            fontSize = CapsulePlayerArtistFontSize,
            lineHeight = CapsulePlayerArtistLineHeight,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}
