/** Based on Velune by Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import kotlin.math.PI
import kotlin.math.sin
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
import com.nikhil.yt.constants.SettingsAdvancedWarningSeenKey

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
    val (advancedWarningSeen, onAdvancedWarningSeenChange) =
        rememberPreference(SettingsAdvancedWarningSeenKey, false)
    var showAdvancedWarning by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Preserve scroll restoration on first composition. Only a real mode
    // change repositions the viewport, and it scrolls rather than jumping.
    var previousSettingsMode by remember { mutableStateOf(advancedSettings) }
    LaunchedEffect(advancedSettings) {
        if (previousSettingsMode != advancedSettings) {
            previousSettingsMode = advancedSettings
            if (listState.firstVisibleItemIndex > 0) {
                listState.animateScrollToItem(0)
            }
        }
    }
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
                    // Give the longer label its natural space without shifting
                    // the Settings title. On narrow screens use a short label.
                    val compactHeader = LocalConfiguration.current.screenWidthDp < 350
                    SettingsModeSegmentedToggle(
                        advancedSettings = advancedSettings,
                        onModeChange = { requestedAdvanced ->
                            if (
                                shouldShowAdvancedModeWarning(
                                    wasAdvanced = advancedSettings,
                                    requestedAdvanced = requestedAdvanced,
                                    warningSeen = advancedWarningSeen,
                                )
                            ) {
                                showAdvancedWarning = true
                            }
                            onAdvancedSettingsChange(requestedAdvanced)
                        },
                        compactLabels = compactHeader,
                        modifier = Modifier.padding(end = if (compactHeader) 8.dp else 10.dp),
                        width = if (compactHeader) 154.dp else 174.dp,
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
                Box(
                    modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(
                        fadeInSpec = tween(durationMillis = 260),
                        fadeOutSpec = tween(durationMillis = 160),
                        placementSpec = spring(
                            dampingRatio = 0.88f,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    ),
                ) {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.palette),
                    title = stringResource(R.string.capsule_settings_appearance),
                    onClick = { navController.navigate("settings/appearance") }
                )
            
                }
            }

            item(key = "capsule_customize") {
                Box(
                    modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(
                        fadeInSpec = tween(durationMillis = 260),
                        fadeOutSpec = tween(durationMillis = 160),
                        placementSpec = spring(
                            dampingRatio = 0.88f,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    ),
                ) {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.edit),
                    title = stringResource(R.string.capsule_customize_title),
                    onClick = { navController.navigate("settings/capsule_customize") }
                )
            
                }
            }

            item(key = "account") {
                Box(
                    modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(
                        fadeInSpec = tween(durationMillis = 260),
                        fadeOutSpec = tween(durationMillis = 160),
                        placementSpec = spring(
                            dampingRatio = 0.88f,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    ),
                ) {
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
            }

            item(key = "listen_together") {
                Box(
                    modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(
                        fadeInSpec = tween(durationMillis = 260),
                        fadeOutSpec = tween(durationMillis = 160),
                        placementSpec = spring(
                            dampingRatio = 0.88f,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    ),
                ) {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.multi_user),
                    title = stringResource(R.string.capsule_settings_listen_together),
                    onClick = { navController.navigate("settings/music_together") }
                )
            
                }
            }

            item(key = "player") {
                Box(
                    modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(
                        fadeInSpec = tween(durationMillis = 260),
                        fadeOutSpec = tween(durationMillis = 160),
                        placementSpec = spring(
                            dampingRatio = 0.88f,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    ),
                ) {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.play),
                    title = stringResource(R.string.capsule_settings_player),
                    onClick = { navController.navigate("settings/player") }
                )
            
                }
            }

            if (advancedSettings) {
            item(key = "video") {
                Box(
                    modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(
                        fadeInSpec = tween(durationMillis = 260),
                        fadeOutSpec = tween(durationMillis = 160),
                        placementSpec = spring(
                            dampingRatio = 0.88f,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    ),
                ) {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.play),
                    title = stringResource(R.string.capsule_settings_video),
                    onClick = { navController.navigate("settings/video_playback") }
                )
            
                }
            }
            }

            item(key = "content") {
                Box(
                    modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(
                        fadeInSpec = tween(durationMillis = 260),
                        fadeOutSpec = tween(durationMillis = 160),
                        placementSpec = spring(
                            dampingRatio = 0.88f,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    ),
                ) {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.language),
                    title = stringResource(R.string.capsule_settings_content),
                    onClick = { navController.navigate("settings/content") }
                )
            
                }
            }

            if (advancedSettings) {
            item(key = "discord") {
                Box(
                    modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(
                        fadeInSpec = tween(durationMillis = 260),
                        fadeOutSpec = tween(durationMillis = 160),
                        placementSpec = spring(
                            dampingRatio = 0.88f,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    ),
                ) {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.discord),
                    title = stringResource(R.string.capsule_settings_discord),
                    onClick = { navController.navigate("settings/discord") }
                )
            
                }
            }

            item(key = "integration") {
                Box(
                    modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(
                        fadeInSpec = tween(durationMillis = 260),
                        fadeOutSpec = tween(durationMillis = 160),
                        placementSpec = spring(
                            dampingRatio = 0.88f,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    ),
                ) {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.integration),
                    title = stringResource(R.string.capsule_settings_integration),
                    onClick = { navController.navigate("settings/integration") }
                )
            
                }
            }
            }

            item(key = "privacy") {
                Box(
                    modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(
                        fadeInSpec = tween(durationMillis = 260),
                        fadeOutSpec = tween(durationMillis = 160),
                        placementSpec = spring(
                            dampingRatio = 0.88f,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    ),
                ) {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.security),
                    title = stringResource(R.string.capsule_settings_privacy),
                    onClick = { navController.navigate("settings/privacy") }
                )
            
                }
            }

            item(key = "storage") {
                Box(
                    modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(
                        fadeInSpec = tween(durationMillis = 260),
                        fadeOutSpec = tween(durationMillis = 160),
                        placementSpec = spring(
                            dampingRatio = 0.88f,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    ),
                ) {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.storage),
                    title = stringResource(R.string.capsule_settings_storage),
                    onClick = { navController.navigate("settings/storage") }
                )
            
                }
            }

            item(key = "backup") {
                Box(
                    modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(
                        fadeInSpec = tween(durationMillis = 260),
                        fadeOutSpec = tween(durationMillis = 160),
                        placementSpec = spring(
                            dampingRatio = 0.88f,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    ),
                ) {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.backup),
                    title = stringResource(R.string.capsule_settings_backup),
                    onClick = { navController.navigate("settings/backup_restore") }
                )
            
                }
            }

            /*
             * The debug screen was registered on settings/misc but nothing
             * linked to it, so the log viewer was unreachable from the app.
             */
            if (advancedSettings) {
            item(key = "developer") {
                Box(
                    modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(
                        fadeInSpec = tween(durationMillis = 260),
                        fadeOutSpec = tween(durationMillis = 160),
                        placementSpec = spring(
                            dampingRatio = 0.88f,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    ),
                ) {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.experiment),
                    title = stringResource(R.string.capsule_settings_developer),
                    onClick = { navController.navigate("settings/misc") }
                )
            
                }
            }
            }

            item(key = "about") {
                Box(
                    modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(
                        fadeInSpec = tween(durationMillis = 260),
                        fadeOutSpec = tween(durationMillis = 160),
                        placementSpec = spring(
                            dampingRatio = 0.88f,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    ),
                ) {
                SettingsItemScreenshotStyle(
                    icon = painterResource(R.drawable.info),
                    title = stringResource(R.string.capsule_settings_about),
                    onClick = { navController.navigate("settings/about") }
                )
            
                }
            }

        }

        if (showAdvancedWarning) {
            AlertDialog(
                onDismissRequest = {
                    showAdvancedWarning = false
                    onAdvancedWarningSeenChange(true)
                },
                title = { Text(stringResource(R.string.capsule_advanced_mode_warning_title)) },
                text = { Text(stringResource(R.string.capsule_advanced_mode_warning_body)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showAdvancedWarning = false
                            onAdvancedWarningSeenChange(true)
                        },
                    ) {
                        Text(stringResource(R.string.capsule_advanced_mode_warning_confirm))
                    }
                },
            )
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
 * First entry into developer mode is the only warning trigger. This must be
 * independent of subsequent DataStore-driven recompositions or app restarts.
 */
internal fun shouldShowAdvancedModeWarning(
    wasAdvanced: Boolean,
    requestedAdvanced: Boolean,
    warningSeen: Boolean,
): Boolean = !wasAdvanced && requestedAdvanced && !warningSeen

/**
 * Basic / Developer selector. Uses the same low-damping physical roll as the
 * AUDIO / VIDEO player control (CapsuleRollingSelection): 0.73 / 240 spring,
 * slight overshoot and a subtle rotation/stretch through the midpoint.
 *
 * The thumb *position and width* follow the same spring progress; changing
 * width immediately would produce a discontinuity on the first frame.
 * Segment gestures are ripple-free: no extra press backdrop is drawn.
 */
@Composable
private fun SettingsModeSegmentedToggle(
    advancedSettings: Boolean,
    onModeChange: (Boolean) -> Unit,
    width: Dp,
    compactLabels: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val trackShape = RoundedCornerShape(11.dp)
    val thumbShape = RoundedCornerShape(9.dp)
    val position by animateFloatAsState(
        targetValue = if (advancedSettings) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.73f,
            stiffness = 240f,
            visibilityThreshold = 0.001f,
        ),
        label = "settingsModeRoll",
    )

    Box(
        modifier = modifier
            .width(width)
            .height(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .clip(trackShape)
                .background(scheme.onSurface.copy(alpha = 0.085f))
                .border(0.8.dp, scheme.onSurface.copy(alpha = 0.13f), trackShape)
                .padding(2.dp),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val baseWidth = maxWidth * 0.32f
                val developerWidth = maxWidth - baseWidth
                // Clamp only the size; leave limited translation overshoot
                // visible inside the clipped track, like AUDIO / VIDEO.
                val widthProgress = position.coerceIn(0f, 1f)
                val thumbWidth =
                    baseWidth + (developerWidth - baseWidth) * widthProgress
                val travelPx = with(density) { baseWidth.toPx() }
                Box(
                    modifier = Modifier
                        .width(thumbWidth)
                        .fillMaxHeight()
                        .graphicsLayer {
                            translationX = travelPx * position.coerceIn(-0.045f, 1.045f)
                            val movement = sin(widthProgress.toDouble() * PI).toFloat()
                            scaleX = 1f + movement * 0.055f
                            scaleY = 1f - movement * 0.04f
                            rotationZ = (sin(position.toDouble() * PI) * 0.8).toFloat()
                        }
                        .clip(thumbShape)
                        .background(scheme.onSurface.copy(alpha = 0.16f))
                        .border(0.7.dp, scheme.onSurface.copy(alpha = 0.08f), thumbShape),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsModeSegment(
                title = stringResource(R.string.capsule_settings_mode_user),
                selected = !advancedSettings,
                onClick = { onModeChange(false) },
                selectedTextColor = scheme.onSurface,
                unselectedTextColor = scheme.onSurface.copy(alpha = 0.78f),
                modifier = Modifier.weight(0.32f),
            )
            SettingsModeSegment(
                title = if (compactLabels) {
                    stringResource(R.string.capsule_settings_mode_advanced_compact)
                } else {
                    stringResource(R.string.capsule_settings_mode_advanced)
                },
                selected = advancedSettings,
                onClick = { onModeChange(true) },
                selectedTextColor = scheme.onSurface,
                unselectedTextColor = scheme.onSurface.copy(alpha = 0.78f),
                modifier = Modifier.weight(0.68f),
            )
        }
    }
}

@Composable
private fun SettingsModeSegment(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    selectedTextColor: Color,
    unselectedTextColor: Color,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = {
                    if (!selected) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            fontSize = 10.sp,
            letterSpacing = (-0.15).sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) selectedTextColor else unselectedTextColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
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
