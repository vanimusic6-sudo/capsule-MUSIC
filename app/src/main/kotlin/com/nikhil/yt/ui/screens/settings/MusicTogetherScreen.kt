/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.ui.screens.settings

import com.nikhil.yt.ui.component.VeluneLoader
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.flow.MutableStateFlow
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.R
import com.nikhil.yt.constants.TogetherAllowGuestsToAddTracksKey
import com.nikhil.yt.constants.TogetherAllowGuestsToControlPlaybackKey
import com.nikhil.yt.constants.TogetherDefaultPortKey
import com.nikhil.yt.constants.TogetherDisplayNameKey
import com.nikhil.yt.constants.TogetherLastJoinLinkKey
import com.nikhil.yt.constants.TogetherRequireHostApprovalToJoinKey
import com.nikhil.yt.constants.TogetherWelcomeShownKey
import com.nikhil.yt.together.TogetherLink
import com.nikhil.yt.together.TogetherRole
import com.nikhil.yt.together.TogetherRoomSettings
import com.nikhil.yt.together.TogetherSessionState
import com.nikhil.yt.ui.component.IconButton as AtIconButton
import com.nikhil.yt.ui.component.TextFieldDialog
import com.nikhil.yt.ui.utils.backToMain
import com.nikhil.yt.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicTogetherScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val playerConnection = LocalPlayerConnection.current

    val (welcomeShown, setWelcomeShown) = rememberPreference(TogetherWelcomeShownKey, false)
    var welcomeDismissedThisSession by rememberSaveable { mutableStateOf(false) }
    val showWelcome = !welcomeShown && !welcomeDismissedThisSession

    if (showWelcome) {
        WelcomeDialog(
            onGotIt = { dontShowAgain ->
                welcomeDismissedThisSession = true
                if (dontShowAgain) setWelcomeShown(true)
            },
            onDismiss = { welcomeDismissedThisSession = true },
        )
    }

    val (displayName, setDisplayName) =
        rememberPreference(
            TogetherDisplayNameKey,
            defaultValue = Build.MODEL?.takeIf { it.isNotBlank() } ?: resources.getString(R.string.app_name),
        )
    val (port, setPort) = rememberPreference(TogetherDefaultPortKey, defaultValue = 42117)
    val (allowAddTracks, setAllowAddTracksRaw) = rememberPreference(TogetherAllowGuestsToAddTracksKey, defaultValue = true)
    val (allowControlPlayback, setAllowControlPlaybackRaw) = rememberPreference(TogetherAllowGuestsToControlPlaybackKey, defaultValue = false)
    val (requireApproval, setRequireApprovalRaw) = rememberPreference(TogetherRequireHostApprovalToJoinKey, defaultValue = false)
    val (lastJoinLink, setLastJoinLink) = rememberPreference(TogetherLastJoinLinkKey, defaultValue = "")

    val sessionStateFlow =
        remember(playerConnection) {
            playerConnection?.service?.togetherSessionState ?: MutableStateFlow(TogetherSessionState.Idle)
        }
    val sessionState by sessionStateFlow.collectAsState()

    val isHosting = sessionState is TogetherSessionState.Hosting
    val isJoining = sessionState is TogetherSessionState.Joining
    val isHostRole =
        when (val state = sessionState) {
            is TogetherSessionState.Hosting -> true
            is TogetherSessionState.Joined -> state.role is TogetherRole.Host
            else -> false
        }
    val isCreatingSessionLoading =
        when (val state = sessionState) {
            is TogetherSessionState.Hosting -> state.roomState == null
            else -> false
        }
    val isJoinedAsGuest =
        when (val state = sessionState) {
            is TogetherSessionState.Joined -> state.role is TogetherRole.Guest
            else -> false
        }
    val isWaitingApproval =
        when (val state = sessionState) {
            is TogetherSessionState.Joined -> {
                state.role is TogetherRole.Guest &&
                    state.roomState.participants
                        .firstOrNull { it.id == state.selfParticipantId }
                        ?.isPending == true
            }
            else -> false
        }
    val isJoinedAsAcceptedGuest = isJoinedAsGuest && !isWaitingApproval
    val disableJoinUi = isHostRole || isCreatingSessionLoading || isJoinedAsGuest

    var showNameDialog by rememberSaveable { mutableStateOf(false) }
    var showPortDialog by rememberSaveable { mutableStateOf(false) }
    var showJoinDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(disableJoinUi, isJoining, isHosting) {
        if (disableJoinUi || isJoining || isHosting) showJoinDialog = false
    }

    fun pushSettingsToActiveSession(
        addTracks: Boolean = allowAddTracks,
        controlPlayback: Boolean = allowControlPlayback,
        approval: Boolean = requireApproval,
    ) {
        if (isHosting) {
            playerConnection?.service?.updateTogetherSettings(
                TogetherRoomSettings(
                    allowGuestsToAddTracks = addTracks,
                    allowGuestsToControlPlayback = controlPlayback,
                    requireHostApprovalToJoin = approval,
                ),
            )
        }
    }

    val setAllowAddTracks: (Boolean) -> Unit = { value ->
        setAllowAddTracksRaw(value)
        pushSettingsToActiveSession(addTracks = value)
    }
    val setAllowControlPlayback: (Boolean) -> Unit = { value ->
        setAllowControlPlaybackRaw(value)
        pushSettingsToActiveSession(controlPlayback = value)
    }
    val setRequireApproval: (Boolean) -> Unit = { value ->
        setRequireApprovalRaw(value)
        pushSettingsToActiveSession(approval = value)
    }

    if (showNameDialog) {
        TextFieldDialog(
            title = { Text(text = stringResource(R.string.together_display_name)) },
            placeholder = { Text(text = stringResource(R.string.together_display_name_placeholder)) },
            isInputValid = { it.trim().isNotBlank() },
            onDone = { setDisplayName(it.trim()) },
            onDismiss = { showNameDialog = false },
        )
    }

    if (showPortDialog) {
        TextFieldDialog(
            title = { Text(text = stringResource(R.string.together_port)) },
            placeholder = { Text(text = "42117") },
            isInputValid = { it.trim().toIntOrNull() in 1..65535 },
            onDone = { setPort(it.trim().toInt()) },
            onDismiss = { showPortDialog = false },
        )
    }

    var joinInput by rememberSaveable { mutableStateOf(lastJoinLink) }
    val canJoin = remember(joinInput) { TogetherLink.decode(joinInput) != null }

    if (showJoinDialog) {
        TextFieldDialog(
            title = { Text(text = stringResource(R.string.join_session)) },
            placeholder = { Text(text = stringResource(R.string.together_join_link_hint)) },
            singleLine = false,
            maxLines = 8,
            isInputValid = { TogetherLink.decode(it) != null },
            onDone = { raw ->
                val trimmed = raw.trim()
                joinInput = trimmed
                setLastJoinLink(trimmed)
                playerConnection?.service?.joinTogether(trimmed, displayName)
            },
            onDismiss = { showJoinDialog = false },
        )
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState(), flingBehavior = rememberSettingsFlingBehavior()),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
            ),
        )

        StatusCard(
            state = sessionState,
            onCopyText = { labelRes, value ->
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText(resources.getString(labelRes), value),
                )
                Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
            },
            onShareLink = { link ->
                val share =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, link)
                    }
                context.startActivity(Intent.createChooser(share, null))
            },
            onLeave = { playerConnection?.service?.leaveTogether() },
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 12.dp),
        )

        if (!isJoinedAsGuest) {
            HostSectionCard(
                displayName = displayName,
                port = port,
                allowAddTracks = allowAddTracks,
                allowControlPlayback = allowControlPlayback,
                requireApproval = requireApproval,
                onShowNameDialog = { showNameDialog = true },
                onShowPortDialog = { showPortDialog = true },
                onAllowAddTracksChange = setAllowAddTracks,
                onAllowControlPlaybackChange = setAllowControlPlayback,
                onRequireApprovalChange = setRequireApproval,
                isStartEnabled = !isCreatingSessionLoading && !isJoining && !isHosting && sessionState !is TogetherSessionState.Joined,
                isLoading = isCreatingSessionLoading,
                onStartSession = {
                    val settings =
                        TogetherRoomSettings(
                            allowGuestsToAddTracks = allowAddTracks,
                            allowGuestsToControlPlayback = allowControlPlayback,
                            requireHostApprovalToJoin = requireApproval,
                        )
                    playerConnection?.service?.startTogetherHost(
                        port = port,
                        displayName = displayName,
                        settings = settings,
                    )
                },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
            )
        }

        JoinSectionCard(
            joinInput = joinInput,
            canJoin = canJoin,
            disableJoinUi = disableJoinUi,
            isJoined = isJoinedAsAcceptedGuest,
            isWaitingApproval = isWaitingApproval,
            isJoining = isJoining,
            onShowJoinDialog = { showJoinDialog = true },
            onJoin = {
                val trimmed = joinInput.trim()
                setLastJoinLink(trimmed)
                playerConnection?.service?.joinTogether(trimmed, displayName)
            },
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.music_together)) },
        navigationIcon = {
            AtIconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(painterResource(R.drawable.arrow_back), null)
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun WelcomeDialog(
    onGotIt: (dontShowAgain: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var dontShowAgain by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f),
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.fire),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.together_welcome_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.together_welcome_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        InstructionRow(
                            icon = R.drawable.fire,
                            accentColor = MaterialTheme.colorScheme.primary,
                            title = stringResource(R.string.together_welcome_host_title),
                            body = stringResource(R.string.together_welcome_host_body),
                        )
                        InstructionRow(
                            icon = R.drawable.link,
                            accentColor = MaterialTheme.colorScheme.tertiary,
                            title = stringResource(R.string.together_welcome_join_title),
                            body = stringResource(R.string.together_welcome_join_body),
                        )
                        InstructionRow(
                            icon = R.drawable.lock,
                            accentColor = MaterialTheme.colorScheme.secondary,
                            title = stringResource(R.string.together_welcome_permissions_title),
                            body = stringResource(R.string.together_welcome_permissions_body),
                        )
                    }
                }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .toggleable(
                                value = dontShowAgain,
                                role = Role.Checkbox,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onValueChange = { dontShowAgain = it },
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Checkbox(
                        checked = dontShowAgain,
                        onCheckedChange = null,
                    )
                    Text(
                        text = stringResource(R.string.together_dont_show_again),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onGotIt(dontShowAgain) },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(
                    painter = painterResource(R.drawable.check),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.got_it),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
    )
}

@Composable
private fun InstructionRow(
    icon: Int,
    accentColor: androidx.compose.ui.graphics.Color,
    title: String,
    body: String,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accentColor.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostSectionCard(
    displayName: String,
    port: Int,
    allowAddTracks: Boolean,
    allowControlPlayback: Boolean,
    requireApproval: Boolean,
    onShowNameDialog: () -> Unit,
    onShowPortDialog: () -> Unit,
    onAllowAddTracksChange: (Boolean) -> Unit,
    onAllowControlPlaybackChange: (Boolean) -> Unit,
    onRequireApprovalChange: (Boolean) -> Unit,
    isStartEnabled: Boolean,
    isLoading: Boolean,
    onStartSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.fire),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.together_host_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.together_display_name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsItemRow(
            icon = R.drawable.person,
            title = stringResource(R.string.together_display_name),
            subtitle = displayName,
            onClick = onShowNameDialog,
        )

        SettingsItemRow(
            icon = R.drawable.link,
            title = stringResource(R.string.together_port),
            subtitle = port.toString(),
            onClick = onShowPortDialog,
        )

        ToggleRow(
            icon = R.drawable.playlist_add,
            title = stringResource(R.string.together_allow_guests_add),
            checked = allowAddTracks,
            onCheckedChange = onAllowAddTracksChange,
        )

        ToggleRow(
            icon = R.drawable.play,
            title = stringResource(R.string.together_allow_guests_control),
            checked = allowControlPlayback,
            onCheckedChange = onAllowControlPlaybackChange,
        )

        ToggleRow(
            icon = R.drawable.lock,
            title = stringResource(R.string.together_require_approval),
            checked = requireApproval,
            onCheckedChange = onRequireApprovalChange,
        )

        Spacer(Modifier.height(8.dp))

        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (isPressed) 0.97f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label = "host_btn_scale",
        )

        Button(
            enabled = isStartEnabled,
            onClick = onStartSession,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .padding(bottom = 10.dp)
                .scale(scale),
            shape = RoundedCornerShape(18.dp),
            interactionSource = interactionSource,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            if (isLoading) {
                VeluneLoader(size = 18.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.loading),
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.fire),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.start_session),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JoinSectionCard(
    joinInput: String,
    canJoin: Boolean,
    disableJoinUi: Boolean,
    isJoined: Boolean,
    isWaitingApproval: Boolean,
    isJoining: Boolean,
    onShowJoinDialog: () -> Unit,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.multi_user),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.together_join_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.join_session),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsItemRow(
            icon = R.drawable.input,
            title = stringResource(R.string.join_session),
            subtitle = joinInput.trim().ifBlank { stringResource(R.string.together_join_link_hint) },
            subtitleMaxLines = 2,
            onClick = if (!disableJoinUi && !isJoining && !isJoined && !isWaitingApproval) onShowJoinDialog else null,
        )

        Spacer(Modifier.height(8.dp))

        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (isPressed) 0.97f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label = "join_btn_scale",
        )

        FilledTonalButton(
            enabled = canJoin && !disableJoinUi && !isJoining && !isJoined && !isWaitingApproval,
            onClick = onJoin,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .padding(bottom = 10.dp)
                .scale(scale),
            shape = RoundedCornerShape(18.dp),
            interactionSource = interactionSource,
        ) {
            if (isJoining) {
                VeluneLoader(size = 18.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.connecting),
                    fontWeight = FontWeight.SemiBold,
                )
            } else if (isWaitingApproval) {
                VeluneLoader(size = 18.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.together_waiting_approval),
                    fontWeight = FontWeight.SemiBold,
                )
            } else if (isJoined) {
                Icon(
                    painter = painterResource(R.drawable.check),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.joined),
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.join),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.join),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun SettingsItemRow(
    icon: Int,
    title: String,
    subtitle: String,
    subtitleMaxLines: Int = 1,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )

        Spacer(Modifier.width(20.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = subtitleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.navigate_next),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(
    icon: Int,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )

        Spacer(Modifier.width(20.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            thumbContent = {
                Icon(
                    painter = painterResource(
                        id = if (checked) R.drawable.check else R.drawable.close,
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize),
                )
            },
        )
    }
}

@Composable
private fun StatusCard(
    state: TogetherSessionState,
    onCopyText: (Int, String) -> Unit,
    onShareLink: (String) -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActive = state !is TogetherSessionState.Idle
    val isError = state is TogetherSessionState.Error
    val isWaitingApproval =
        when (state) {
            is TogetherSessionState.Joined -> {
                state.role is TogetherRole.Guest &&
                    state.roomState.participants
                        .firstOrNull { it.id == state.selfParticipantId }
                        ?.isPending == true
            }
            else -> false
        }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                Icon(
                    painter = painterResource(
                        if (isError) R.drawable.error else R.drawable.fire,
                    ),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.together_status),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when (state) {
                            TogetherSessionState.Idle -> stringResource(R.string.together_idle)
                            is TogetherSessionState.Hosting -> stringResource(R.string.together_hosting)
                            is TogetherSessionState.HostingOnline -> stringResource(R.string.together_hosting)
                            is TogetherSessionState.Joining -> stringResource(R.string.together_joining)
                            is TogetherSessionState.JoiningOnline -> stringResource(R.string.together_joining)
                            is TogetherSessionState.Joined ->
                                if (isWaitingApproval) {
                                    stringResource(R.string.together_waiting_approval)
                                } else {
                                    stringResource(R.string.together_connected)
                                }
                            is TogetherSessionState.Error ->
                                if (state.message == stringResource(R.string.together_host_left_session)) {
                                    state.message
                                } else {
                                    stringResource(R.string.together_error_state)
                                }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isActive) {
                    FilledTonalButton(
                        onClick = onLeave,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.leave),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.leave),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            when (state) {
                is TogetherSessionState.Hosting -> {
                    SessionInfoCard(
                        label = stringResource(R.string.session_link),
                        value = state.joinLink,
                        maxLines = 3,
                        onCopy = { onCopyText(R.string.session_link, state.joinLink) },
                        onShare = { onShareLink(state.joinLink) },
                    )
                }

                is TogetherSessionState.HostingOnline -> {
                    SessionInfoCard(
                        label = stringResource(R.string.session_code),
                        value = state.code,
                        maxLines = 2,
                        onCopy = { onCopyText(R.string.session_code, state.code) },
                        onShare = { onShareLink(state.code) },
                    )
                }

                is TogetherSessionState.Joined -> {
                    if (!isWaitingApproval) {
                        ParticipantsCard(participants = state.roomState.participants.map { it.name })
                    }
                }

                is TogetherSessionState.Error -> {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun SessionInfoCard(
    label: String,
    value: String,
    maxLines: Int,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = onCopy,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.copy),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(text = stringResource(R.string.copy_link))
                }
                TextButton(
                    onClick = onShare,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.share),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(text = stringResource(R.string.share))
                }
            }
        }
    }
}

@Composable
private fun ParticipantsCard(
    participants: List<String>,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.person),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.participants),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = participants.joinToString(separator = " · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
