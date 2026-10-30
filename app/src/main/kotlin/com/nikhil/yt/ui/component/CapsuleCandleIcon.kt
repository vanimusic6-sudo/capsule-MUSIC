package com.nikhil.yt.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R

/** Theme-aware candle body with a separate, softly amber flame. No animation. */
internal val CapsuleFlame = Color(0xFFFFB65C)
internal val CapsuleFlameCore = Color(0xFFFFDA97)

@Composable
internal fun CapsuleCandleIcon(
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(R.drawable.capsule_candle),
            contentDescription = null,
            tint = StandardChrome.text,
            modifier = Modifier.size(size),
        )
        Icon(
            painter = painterResource(R.drawable.capsule_candle_flame),
            contentDescription = null,
            tint = CapsuleFlame,
            modifier = Modifier.size(size),
        )
    }
}
