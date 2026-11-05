package com.nikhil.yt.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import com.nikhil.yt.constants.InnerTubeCookieKey
import com.nikhil.yt.innertube.utils.parseCookieString
import com.nikhil.yt.utils.rememberPreference
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nikhil.yt.R
import com.nikhil.yt.ui.theme.LocalCapsuleLightTheme

/**
 * One thin, low-contrast rule under pinned navigation across the app.
 * Reveal follows the existing photograph-to-toolbar progress in detail pages;
 * no new animation clock, layout passes, or extra overlay surfaces.
 */
internal fun capsuleTopBarRuleAlpha(progress: Float): Float {
    // The arrival animation is clocked once, on the pin/unpin event,
    // not fed raw photo scroll pixels. Ease its alpha without a width wipe.
    val p = progress.coerceIn(0f, 1f)
    return p * p * (3f - 2f * p)
}

internal fun Modifier.capsuleTopBarHairline(
    color: Color,
    reveal: () -> Float = { 1f },
): Modifier = drawBehind {
    val opacity = capsuleTopBarRuleAlpha(reveal())
    if (opacity > 0f) {
        // Full-width hairline: 16dp side margins made this look like a
        // short underline rather than a structural divider. Place it on
        // the bottom raster edge, never through the bar's title baseline.
        val lineWidth = 0.75.dp.toPx()
        val y = size.height - lineWidth / 2f
        if (size.width > 0f && y > 0f) {
            drawLine(
                color = color.copy(alpha = 0.18f * opacity),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = lineWidth,
            )
        }
    }
}

/** Neutral chrome for the standard layout, independent of Capsule Dock artwork. */
object StandardChrome {
    val isDark: Boolean @Composable get() = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val background: Color @Composable get() =
        if (LocalCapsuleLightTheme.current) MaterialTheme.colorScheme.background
        else if (isDark) Color(0xFF141414) else Color(0xFFFAFAFA)
    val panel: Color @Composable get() =
        if (LocalCapsuleLightTheme.current) MaterialTheme.colorScheme.surfaceContainer
        else if (isDark) Color(0xFF1C1C1C) else Color(0xFFF0F0F0)
    val selected: Color @Composable get() =
        if (LocalCapsuleLightTheme.current) MaterialTheme.colorScheme.surfaceContainerHighest
        else if (isDark) Color(0xFF272727) else Color(0xFFDEDEDE)
    val text: Color @Composable get() =
        if (LocalCapsuleLightTheme.current) MaterialTheme.colorScheme.onSurface
        else if (isDark) Color(0xFFF3F3F3) else Color(0xFF171717)
    val muted: Color @Composable get() =
        if (LocalCapsuleLightTheme.current) MaterialTheme.colorScheme.onSurfaceVariant
        else if (isDark) Color(0xFF969696) else Color(0xFF626262)
    val favorite: Color @Composable get() = CapsuleFavoriteColors.selected(text)
}

@Composable
fun StandardHeaderTitle(
    accountName: String?,
    accountImageUrl: String?,
    onAccountClick: () -> Unit,
) {
    val accountLabel = stringResource(R.string.account)
    // The preference snapshot is primed before Compose. A returning signed-in
    // user keeps their account placeholder until the real portrait loads;
    // they never flash a guest sun/moon while accountInfo() is in flight.
    val cookie by rememberPreference(InnerTubeCookieKey, defaultValue = "")
    val signedIn = remember(cookie) {
        runCatching { "SAPISID" in parseCookieString(cookie) }.getOrDefault(false)
    }
    // The greeting is the ONLY app chrome title, on Home and all tabs.
    // The former route-gated title branch exposed the retired wordmark
    // and a plain "C" avatar during Library/Search/Settings navigation.
    // One receiver schedules the next period boundary, not a polling loop.
    val dayPeriod = rememberHomeDayPeriod()
    val guestIcon = homeGuestIcon(signedIn = signedIn, period = dayPeriod)
    val title = stringResource(dayPeriod.greetingId)
    val sourceInteraction = remember { MutableInteractionSource() }
    val sourcePressed by sourceInteraction.collectIsPressedAsState()
    val sourceScale by animateFloatAsState(
        targetValue = if (sourcePressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.74f, stiffness = 640f),
        label = "homeSourcePress",
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(44.dp)
                .clip(CircleShape)
                .clickable(interactionSource = sourceInteraction, indication = null,
                    role = Role.Button, onClick = onAccountClick)
                .graphicsLayer { scaleX = sourceScale; scaleY = sourceScale }
                .semantics { contentDescription = accountLabel },
        ) {
            Box(
                // 44dp accessible touch target; 30dp visual avatar.
                Modifier.size(30.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFBC3606)),
                contentAlignment = Alignment.Center,
            ) {
                if (guestIcon != null) {
                    Icon(
                        painter = painterResource(guestIcon),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    val initial = accountName?.takeUnless { it == "Guest" }
                        ?.trim()?.firstOrNull()?.uppercase()
                    if (initial != null) {
                        Text(
                            text = initial,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        // No legacy "C" placeholder while the account loads.
                        Icon(
                            painter = painterResource(R.drawable.person),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    AsyncImage(
                        model = if (signedIn) accountImageUrl else null,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(30.dp).clip(CircleShape),
                    )
                }
            }
        }
        Text(
            text = title,
            color = StandardChrome.text,
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
