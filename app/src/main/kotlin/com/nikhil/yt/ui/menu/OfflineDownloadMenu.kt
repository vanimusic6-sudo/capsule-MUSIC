package com.nikhil.yt.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R
import com.nikhil.yt.discovery.ExperimentalDownload
import com.nikhil.yt.ui.component.MenuSurfaceSection

/** Native Capsule menu sheet for MediaStore downloads. Does not call YouTube DownloadService. */
@Composable
fun OfflineDownloadMenu(
    file: ExperimentalDownload.SavedAudio,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = file.title,
            modifier = Modifier.padding(horizontal = 24.dp),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = file.artist + " · Audius",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        MenuSurfaceSection(modifier = Modifier.padding(vertical = 12.dp)) {
            ListItem(
                headlineContent = {
                    Text(
                        text = stringResource(R.string.remove_download),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingContent = {
                    Icon(
                        painter = painterResource(R.drawable.offline),
                        tint = MaterialTheme.colorScheme.error,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable {
                    onRemove()
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
