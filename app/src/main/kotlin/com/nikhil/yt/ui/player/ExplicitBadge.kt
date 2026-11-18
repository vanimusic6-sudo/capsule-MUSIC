package com.nikhil.yt.ui.player

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The boxed "E" that marks a track as explicit.
 *
 * It existed twice, copied between the mini player and the full player, which is how the third
 * design shipped without one: there was no single thing to reuse, only two blocks of layout to
 * notice and copy again. The two copies differed by a device-independent pixel and a point of
 * type, so both sizes stay callable rather than being averaged into one that matches neither.
 */
@Composable
fun ExplicitBadge(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 15.dp,
    fontSize: TextUnit = 9.sp,
) {
    val corner = RoundedCornerShape(2.dp)
    Box(
        modifier = modifier.size(size).clip(corner).border(1.dp, color, corner),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "E",
            color = color,
            fontSize = fontSize,
            lineHeight = fontSize,
            fontWeight = FontWeight.Bold,
        )
    }
}
