/** Based on Velune by Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.R
import com.nikhil.yt.viewmodels.HomeViewModel
import androidx.compose.ui.platform.LocalContext
import com.nikhil.yt.App.Companion.forgetAccount
import com.nikhil.yt.utils.rememberPreference
import com.nikhil.yt.constants.InnerTubeCookieKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VeluneSettingsScreen(
    navController: NavController,
) {
    val context = LocalContext.current
    val viewModel: HomeViewModel = hiltViewModel(context as androidx.activity.ComponentActivity)
    val accountName by viewModel.accountName.collectAsState()
    val accountImageUrl by viewModel.accountImageUrl.collectAsState()
    val isLoggedIn = accountName != "Guest" && !accountName.isNullOrEmpty()
    var showLogoutDialog by remember { mutableStateOf(false) }
    val (innerTubeCookie, onInnerTubeCookieChange) = rememberPreference(InnerTubeCookieKey, "")

    Scaffold(
        // Content stops above the dock and the navigation bar instead of running under them.
        contentWindowInsets =
            LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.capsule_settings_title), fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = stringResource(R.string.capsule_settings_back),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            item {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.palette),
                    title = stringResource(R.string.capsule_settings_appearance),
                    onClick = { navController.navigate("settings/appearance") }
                )
            }

            item {
                if (isLoggedIn) {
                    SettingsItemAccountStyle(
                        model = accountImageUrl,
                        fallbackText = accountName?.firstOrNull()?.uppercase() ?: "",
                        title = stringResource(R.string.capsule_settings_account),
                        onClick = { navController.navigate("settings/account") }
                    )
                } else {
                    SettingsItemScreenshotStyle(
                        icon = painterResource(R.drawable.account),
                        title = stringResource(R.string.capsule_settings_account),
                        onClick = { navController.navigate("settings/account") }
                    )
                }
            }

            item {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.multi_user),
                    title = stringResource(R.string.capsule_settings_listen_together),
                    onClick = { navController.navigate("settings/music_together") }
                )
            }

            item {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.play),
                    title = stringResource(R.string.capsule_settings_player),
                    onClick = { navController.navigate("settings/player") }
                )
            }

            item {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.play),
                    title = stringResource(R.string.capsule_settings_video),
                    onClick = { navController.navigate("settings/video_playback") }
                )
            }

            item {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.language),
                    title = stringResource(R.string.capsule_settings_content),
                    onClick = { navController.navigate("settings/content") }
                )
            }

            item {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.discord),
                    title = stringResource(R.string.capsule_settings_discord),
                    onClick = { navController.navigate("settings/discord") }
                )
            }

            item {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.integration),
                    title = stringResource(R.string.capsule_settings_integration),
                    onClick = { navController.navigate("settings/integration") }
                )
            }

            item {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.security),
                    title = stringResource(R.string.capsule_settings_privacy),
                    onClick = { navController.navigate("settings/privacy") }
                )
            }

            item {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.storage),
                    title = stringResource(R.string.capsule_settings_storage),
                    onClick = { navController.navigate("settings/storage") }
                )
            }

            item {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.backup),
                    title = stringResource(R.string.capsule_settings_backup),
                    onClick = { navController.navigate("settings/backup_restore") }
                )
            }

            /*
             * The debug screen was registered on settings/misc but nothing
             * linked to it, so the log viewer was unreachable from the app.
             */
            item {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.experiment),
                    title = stringResource(R.string.capsule_settings_developer),
                    onClick = { navController.navigate("settings/misc") }
                )
            }

            item {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.info),
                    title = stringResource(R.string.capsule_settings_about),
                    onClick = { navController.navigate("settings/about") }
                )
            }

            item { Spacer(Modifier.height(32.dp)) }
        }

        if (showLogoutDialog) {
            AlertDialog(
                onDismissRequest = { showLogoutDialog = false },
                title = { Text(stringResource(R.string.capsule_settings_sign_out)) },
                text = { Text(stringResource(R.string.capsule_settings_sign_out_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        showLogoutDialog = false
                        onInnerTubeCookieChange("")
                        forgetAccount(context)
                    }) {
                        Text(stringResource(R.string.capsule_settings_sign_out))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLogoutDialog = false }) {
                        Text(stringResource(R.string.capsule_settings_cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun SettingsItemScreenshotStyle(
    icon: Painter,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 30.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            painter = painterResource(R.drawable.navigate_next),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsItemAccountStyle(
    model: String?,
    fallbackText: String,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 22.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = title,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = fallbackText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.width(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            painter = painterResource(R.drawable.navigate_next),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
