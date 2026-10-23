package com.nikhil.yt.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
    sourceIcon: Int? = null,
    sourceContentDescription: String? = null,
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).clip(CircleShape)
                .clickable(role = Role.Button, onClick = onAccountClick)
                .semantics { contentDescription = sourceContentDescription ?: accountLabel },
        ) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0xFFBC3606)), contentAlignment = Alignment.Center) {
                if (sourceIcon != null) {
                    Icon(
                        painter = painterResource(sourceIcon),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                } else if (guestIcon != null) {
                    Icon(
                        painter = painterResource(guestIcon),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
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
                            modifier = Modifier.size(19.dp),
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
