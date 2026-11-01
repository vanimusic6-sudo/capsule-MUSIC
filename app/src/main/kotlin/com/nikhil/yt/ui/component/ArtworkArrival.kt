package com.nikhil.yt.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/** One finite reveal per visible source, including a memory-cache hit.
 * Read the returned state only in a draw/layer lambda. A late loading/error
 * callback cannot hide pixels that have already arrived for this identity.
 */
@Composable
internal fun rememberArtworkArrivalAlpha(identity: Any?, decoded: Boolean): State<Float> {
    val alpha = remember(identity) { Animatable(0f) }
    val revealed = remember(identity) { mutableStateOf(false) }
    SideEffect { if (decoded) revealed.value = true }
    LaunchedEffect(alpha, revealed.value) {
        if (revealed.value && alpha.value < 1f) alpha.animateTo(1f, tween(180))
    }
    return alpha.asState()
}
