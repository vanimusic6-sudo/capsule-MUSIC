/** Based on Velune by Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.nikhil.yt.constants.SettingsAdvancedModeKey

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
    val (advancedSettings, onAdvancedSettingsChange) =
        rememberPreference(SettingsAdvancedModeKey, false)
    val listState = rememberLazyListState()
    // Switching modes changes list keys; return to its first visible section.
    LaunchedEffect(advancedSettings) { listState.scrollToItem(0) }
    val playerAwareInsets = LocalPlayerAwareWindowInsets.current
    val playerAwareBottomPadding = playerAwareInsets.asPaddingValues().calculateBottomPadding()


    Scaffold(
        // Keep the viewport edge-to-edge at the bottom so the settings rows remain a real
        // backdrop source for the floating mini-player. The list gets equivalent trailing padding
        // below, so the last setting can still be scrolled fully above player/navigation chrome.
        contentWindowInsets =
            playerAwareInsets
                .only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.capsule_settings_title),
                        fontSize = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = stringResource(R.string.capsule_settings_back),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                actions = {
                    val compactHeader = LocalConfiguration.current.screenWidthDp < 350
                    SettingsModeSegmentedToggle(
                        advancedSettings = advancedSettings,
                        onModeChange = onAdvancedSettingsChange,
                        modifier = Modifier.padding(
                            end = if (compactHeader) 12.dp else 28.dp,
                        ),
                        width = if (compactHeader) 142.dp else 156.dp,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            flingBehavior = rememberSettingsFlingBehavior(),
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = playerAwareBottomPadding + 32.dp),
        ) {
            item(key = "appearance") {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.palette),
                    title = stringResource(R.string.capsule_settings_appearance),
                    onClick = { navController.navigate("settings/appearance") }
                )
            }

            item(key = "capsule_customize") {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.edit),
                    title = stringResource(R.string.capsule_customize_title),
                    onClick = { navController.navigate("settings/capsule_customize") }
                )
            }

            if (advancedSettings) {
            item(key = "account") {
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

            item(key = "listen_together") {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.multi_user),
                    title = stringResource(R.string.capsule_settings_listen_together),
                    onClick = { navController.navigate("settings/music_together") }
                )
            }
            }

            item(key = "player") {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.play),
                    title = stringResource(R.string.capsule_settings_player),
                    onClick = { navController.navigate("settings/player") }
                )
            }

            if (advancedSettings) {
            item(key = "video") {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.play),
                    title = stringResource(R.string.capsule_settings_video),
                    onClick = { navController.navigate("settings/video_playback") }
                )
            }
            }

            item(key = "content") {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.language),
                    title = stringResource(R.string.capsule_settings_content),
                    onClick = { navController.navigate("settings/content") }
                )
            }

            if (advancedSettings) {
            item(key = "discord") {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.discord),
                    title = stringResource(R.string.capsule_settings_discord),
                    onClick = { navController.navigate("settings/discord") }
                )
            }

            item(key = "integration") {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.integration),
                    title = stringResource(R.string.capsule_settings_integration),
                    onClick = { navController.navigate("settings/integration") }
                )
            }
            }

            item(key = "privacy") {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.security),
                    title = stringResource(R.string.capsule_settings_privacy),
                    onClick = { navController.navigate("settings/privacy") }
                )
            }

            item(key = "storage") {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.storage),
                    title = stringResource(R.string.capsule_settings_storage),
                    onClick = { navController.navigate("settings/storage") }
                )
            }

            item(key = "backup") {
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
            if (advancedSettings) {
            item(key = "developer") {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.experiment),
                    title = stringResource(R.string.capsule_settings_developer),
                    onClick = { navController.navigate("settings/misc") }
                )
            }

            item(key = "about") {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.info),
                    title = stringResource(R.string.capsule_settings_about),
                    onClick = { navController.navigate("settings/about") }
                )
            }
            }

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


/**
 * Compact mode selector beside the Settings title, matching the sketch.
 *
 * A 44dp invisible touch region surrounds the 22dp visual capsule so its
 * small proportions do not make the two options hard to tap. The control
 * only writes the existing mode preference; it does not change settings.
 */
@Composable
private fun SettingsModeSegmentedToggle(
    advancedSettings: Boolean,
    onModeChange: (Boolean) -> Unit,
    width: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val backgroundColor = colors.onSurface.copy(alpha = 0.52f)
    val selectedBackground = colors.background.copy(alpha = 0.30f)

    Box(
        modifier = modifier
            .width(width)
            .height(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(backgroundColor),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsModeSegment(
                title = stringResource(R.string.capsule_settings_mode_user),
                selected = !advancedSettings,
                onClick = { onModeChange(false) },
                selectedBackground = selectedBackground,
                selectedTextColor = colors.onSurface,
                unselectedTextColor = colors.onSurface.copy(alpha = 0.85f),
                modifier = Modifier.weight(0.39f),
            )
            SettingsModeSegment(
                title = stringResource(R.string.capsule_settings_mode_advanced),
                selected = advancedSettings,
                onClick = { onModeChange(true) },
                selectedBackground = selectedBackground,
                selectedTextColor = colors.onSurface,
                unselectedTextColor = colors.onSurface.copy(alpha = 0.85f),
                modifier = Modifier.weight(0.61f),
            )
        }
    }
}

@Composable
private fun SettingsModeSegment(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    selectedBackground: Color,
    selectedTextColor: Color,
    unselectedTextColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = { if (!selected) onClick() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .padding(horizontal = 1.dp, vertical = 1.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (selected) selectedBackground else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title,
                fontSize = 9.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) selectedTextColor else unselectedTextColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
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
