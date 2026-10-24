package com.nikhil.yt.ui.screens.artist

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R
import com.nikhil.yt.ui.component.StandardChrome
import com.nikhil.yt.ui.motion.CapsuleStandardEasing

/** The description alone owns its reversible height; LazyColumn follows that measured height. */
@Composable
internal fun ArtistDescription(artistId: String, description: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(artistId) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Box(Modifier.fillMaxWidth().animateContentSize(tween(320, easing = CapsuleStandardEasing))) {
            Text(description, style = MaterialTheme.typography.bodyMedium, color = StandardChrome.muted,
                maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.show_less else R.string.show_more), color = StandardChrome.text)
        }
    }
}
