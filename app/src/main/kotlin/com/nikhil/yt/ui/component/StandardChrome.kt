package com.nikhil.yt.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
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

/** Neutral chrome for the standard layout, independent of Capsule Dock artwork. */
object StandardChrome {
    val isDark: Boolean @Composable get() = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val background: Color @Composable get() = if (isDark) Color(0xFF101010) else Color(0xFFFAFAFA)
    val panel: Color @Composable get() = if (isDark) Color(0xFF1C1C1C) else Color(0xFFF0F0F0)
    val selected: Color @Composable get() = if (isDark) Color(0xFF272727) else Color(0xFFDEDEDE)
    val text: Color @Composable get() = if (isDark) Color(0xFFF3F3F3) else Color(0xFF171717)
    val muted: Color @Composable get() = if (isDark) Color(0xFF969696) else Color(0xFF626262)
    val favorite: Color @Composable get() = CapsuleFavoriteColors.selected(text)
}

@Composable
fun StandardHeaderTitle(accountName: String?, accountImageUrl: String?, onAccountClick: () -> Unit) {
    val accountLabel = stringResource(R.string.account)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).clip(CircleShape)
                .clickable(role = Role.Button, onClick = onAccountClick)
                .semantics { contentDescription = accountLabel },
        ) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0xFFBC3606)), contentAlignment = Alignment.Center) {
                Text(accountName?.trim()?.firstOrNull()?.uppercase() ?: "C", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                AsyncImage(model = accountImageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(30.dp).clip(CircleShape))
            }
        }
        Text(
            text = stringResource(R.string.app_name),
            color = StandardChrome.text,
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun <E> StandardHomeChips(chips: List<Pair<E, String>>, currentValue: E, onValueUpdate: (E) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { (value, label) ->
            val selected = value == currentValue
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.heightIn(min = 40.dp).clip(CircleShape)
                    .background(if (selected) StandardChrome.selected else StandardChrome.panel)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onValueUpdate(value) })
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
                Text(label, color = if (selected) StandardChrome.text else StandardChrome.muted, fontSize = 16.sp, maxLines = 1)
            }
        }
    }
}
