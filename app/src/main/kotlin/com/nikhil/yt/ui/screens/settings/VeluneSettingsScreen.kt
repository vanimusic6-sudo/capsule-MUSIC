/** Based on Velune by Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.nikhil.yt.BuildConfig
import com.nikhil.yt.R
import com.nikhil.yt.viewmodels.HomeViewModel
import androidx.compose.ui.platform.LocalContext
import com.nikhil.yt.App.Companion.forgetAccount
import com.nikhil.yt.utils.rememberPreference
import com.nikhil.yt.constants.InnerTubeCookieKey
import com.nikhil.yt.ui.component.capsuleSceneItem
import com.nikhil.yt.ui.component.rememberCapsuleSceneMotionState

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
    val sceneMotion = rememberCapsuleSceneMotionState(key = "settings")

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.capsuleSceneItem(
                    state = sceneMotion,
                    order = 0,
                    lift = 3.dp,
                    depth = 0.0012f,
                ),
                title = { Text("Settings", fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = "Back",
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .capsuleSceneItem(
                            state = sceneMotion,
                            order = 1,
                            lift = 5.dp,
                            depth = 0.0016f,
                        )
                        .padding(vertical = 16.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_velune_concept),
                            contentDescription = "capsule Logo",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "capsule",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Normal
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "v${BuildConfig.VERSION_NAME.trim()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.palette),
                    title = "Appearance",
                    onClick = { navController.navigate("settings/appearance") },
                    modifier = Modifier.capsuleSceneItem(sceneMotion, order = 2),
                )
            }

            item {
                if (isLoggedIn) {
                    SettingsItemAccountStyle(
                        model = accountImageUrl,
                        fallbackText = accountName?.firstOrNull()?.uppercase() ?: "",
                        title = "Account",
                        onClick = { navController.navigate("settings/account") },
                        modifier = Modifier.capsuleSceneItem(sceneMotion, order = 3),
                    )
                } else {
                    SettingsItemScreenshotStyle(
                        icon = painterResource(R.drawable.account),
                        title = "Account",
                        onClick = { navController.navigate("settings/account") },
                        modifier = Modifier.capsuleSceneItem(sceneMotion, order = 3),
                    )
                }
            }

            item {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.multi_user),
                    title = "Listen Together",
                    onClick = { navController.navigate("settings/music_together") },
                    modifier = Modifier.capsuleSceneItem(sceneMotion, order = 4),
                )
            }

            item {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.play),
                    title = "Player and audio",
                    onClick = { navController.navigate("settings/player") },
                    modifier = Modifier.capsuleSceneItem(sceneMotion, order = 5),
                )
            }

            item {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.play),
                    title = "Video playback",
                    onClick = { navController.navigate("settings/video_playback") },
                    modifier = Modifier.capsuleSceneItem(sceneMotion, order = 6),
                )
            }

            item {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.language),
                    title = "Content",
                    onClick = { navController.navigate("settings/content") },
                    modifier = Modifier.capsuleSceneItem(sceneMotion, order = 7),
                )
            }

            item {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.discord),
                    title = "Discord",
                    onClick = { navController.navigate("settings/discord") },
                    modifier = Modifier.capsuleSceneItem(sceneMotion, order = 8),
                )
            }

            item {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.integration),
                    title = "Integration",
                    onClick = { navController.navigate("settings/integration") },
                    modifier = Modifier.capsuleSceneItem(sceneMotion, order = 9),
                )
            }

            item {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.security),
                    title = "Privacy",
                    onClick = { navController.navigate("settings/privacy") },
                    modifier = Modifier.capsuleSceneItem(sceneMotion, order = 10),
                )
            }

            item {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.storage),
                    title = "Storage",
                    onClick = { navController.navigate("settings/storage") },
                    modifier = Modifier.capsuleSceneItem(sceneMotion, order = 11),
                )
            }

            item {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.backup),
                    title = "Backup and restore",
                    onClick = { navController.navigate("settings/backup_restore") },
                    modifier = Modifier.capsuleSceneItem(sceneMotion, order = 12),
                )
            }

            item {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.experiment),
                    title = "Developer",
                    onClick = { navController.navigate("settings/misc") },
                    modifier = Modifier.capsuleSceneItem(sceneMotion, order = 13),
                )
            }

            item {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.info),
                    title = "About",
                    onClick = { navController.navigate("settings/about") },
                    modifier = Modifier.capsuleSceneItem(sceneMotion, order = 14),
                )
            }

            item { Spacer(Modifier.height(32.dp)) }
        }

        if (showLogoutDialog) {
            AlertDialog(
                onDismissRequest = { showLogoutDialog = false },
                title = { Text("Sign Out") },
                text = { Text("Are you sure you want to sign out of your YouTube Music account?") },
                confirmButton = {
                    TextButton(onClick = {
                        showLogoutDialog = false
                        onInnerTubeCookieChange("")
                        forgetAccount(context)
                    }) {
                        Text("Sign Out")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLogoutDialog = false }) {
                        Text("Cancel")
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
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
