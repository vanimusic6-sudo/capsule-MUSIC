/** Capsule MUSIC — compact artist identity row for Light and Immersion. GPL-3.0. */
package com.nikhil.yt.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.LocalDatabase
import com.nikhil.yt.ui.component.ArtistPortraits
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.nikhil.yt.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

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
 * At most two *tiny cached* circular avatars, two artist photos maximum, no extra full-size
 * artwork decode, no shimmer or continuous avatar animation. The label is one measured line,
 * uses • instead of commas, and reserves a real layout width for the complete "ещё N" suffix.
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
    val context = LocalContext.current
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
        val portraits = credits.take(2)
        val avatarSize = 21.dp
        val overlap = 5.dp
        val avatarBlock = if (portraits.isEmpty()) 0.dp
            else avatarSize + (avatarSize - overlap) * (portraits.size - 1) + 8.dp
        val availableTextPx = with(LocalDensity.current) {
            (maxWidth - avatarBlock).coerceAtLeast(0.dp).roundToPx()
        }
        val label = remember(credits, availableTextPx, style) {
            capsuleArtistLabel(credits.map { it.name }, availableTextPx) { candidate ->
                measurer.measure(text = candidate, style = style, maxLines = 1, softWrap = false).size.width
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (portraits.isNotEmpty()) {
                Box(modifier = Modifier.width(avatarBlock - 8.dp).height(avatarSize)) {
                    portraits.forEachIndexed { index, artist ->
                        // SongItem conversion often has NO artist thumbnail at all, especially
                        // for single-artist tracks. Resolve via existing portrait cache / Room /
                        // artist-page API (shared with the artist picker), not a fake initial.
                        val database = LocalDatabase.current
                        val photoUrl by produceState<String?>(
                            initialValue = artist.thumbnailUrl?.takeIf { it.isNotBlank() }
                                ?: ArtistPortraits.peek(artist.id),
                            key1 = artist.id, key2 = artist.thumbnailUrl,
                        ) {
                            value = artist.thumbnailUrl?.takeIf { it.isNotBlank() }
                                ?: ArtistPortraits.peek(artist.id)
                            val artistId = artist.id?.takeIf { it.isNotBlank() }
                            if (value == null && artistId != null) {
                                try {
                                    value = ArtistPortraits.resolve(
                                        id = artistId,
                                        hintedUrl = artist.thumbnailUrl,
                                        allowNetwork = true,
                                        localLookup = {
                                            database.artist(artistId).first()?.thumbnailUrl
                                        },
                                    )
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    // Missing/unreachable artist pages must never affect audio.
                                }
                            }
                        }
                        val photoRequest = remember(context, photoUrl) {
                            photoUrl?.let { url ->
                                ImageRequest.Builder(context).data(url).size(72, 72).crossfade(false).build()
                            }
                        }
                        val frame = Modifier
                            .offset(x = (avatarSize - overlap) * index)
                            .size(avatarSize)
                            .clip(CircleShape)
                            .background(color.copy(alpha = 0.12f))
                            .border(0.6.dp, color.copy(alpha = 0.25f), CircleShape)
                        if (photoRequest != null) {
                            AsyncImage(
                                model = photoRequest,
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = frame,
                            )
                        } else {
                            // Placeholder is a quiet person silhouette, not a fake photo or
                            // a large letter. It disappears when the real portrait resolves.
                            Box(frame, contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(R.drawable.artist),
                                    contentDescription = null,
                                    tint = color.copy(alpha = 0.42f),
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
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
}
