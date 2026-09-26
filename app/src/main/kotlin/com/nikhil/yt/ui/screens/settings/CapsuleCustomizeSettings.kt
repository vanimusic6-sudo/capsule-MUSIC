/*
 * Capsule MUSIC
 * Personal Capsule customization hub.
 * GPL-3.0
 */

package com.nikhil.yt.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.R
import com.nikhil.yt.constants.CapsuleCustomizeTarget
import com.nikhil.yt.constants.CapsuleCustomizeTargetKey
import com.nikhil.yt.constants.CapsuleLightEditEnabledKey
import com.nikhil.yt.constants.CapsuleLightEditSessionActiveKey
import com.nikhil.yt.constants.CapsuleLightLayoutOrderKey
import com.nikhil.yt.constants.CapsuleLightMetadataOrderKey
import com.nikhil.yt.constants.CapsuleLightModeOrderKey
import com.nikhil.yt.constants.CapsuleLightAvOrderKey
import com.nikhil.yt.constants.CapsuleLightTransportOrderKey
import com.nikhil.yt.constants.CapsuleLightArtworkWidthScaleKey
import com.nikhil.yt.constants.CapsuleLightArtworkHeightScaleKey
import com.nikhil.yt.ui.component.EnumListPreference
import com.nikhil.yt.ui.component.IconButton
import com.nikhil.yt.ui.component.PreferenceEntry
import com.nikhil.yt.ui.component.PreferenceGroupTitle
import com.nikhil.yt.ui.component.SwitchPreference
import com.nikhil.yt.ui.player.CapsuleLightBaseOrderEncoded
import com.nikhil.yt.ui.player.CapsuleLightMetadataBaseOrderEncoded
import com.nikhil.yt.ui.player.CapsuleLightModeBaseOrderEncoded
import com.nikhil.yt.ui.player.CapsuleLightAvBaseOrderEncoded
import com.nikhil.yt.ui.player.CapsuleLightTransportBaseOrderEncoded
import com.nikhil.yt.ui.utils.backToMain
import com.nikhil.yt.utils.rememberEnumPreference
import com.nikhil.yt.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapsuleCustomizeSettings(
    navController: NavController,
) {
    val (target, onTargetChange) =
        rememberEnumPreference(
            CapsuleCustomizeTargetKey,
            defaultValue = CapsuleCustomizeTarget.LIGHT,
        )
    val (editEnabled, onEditEnabledChange) =
        rememberPreference(
            CapsuleLightEditEnabledKey,
            defaultValue = false,
        )
    val (_, onLayoutOrderChange) =
        rememberPreference(
            CapsuleLightLayoutOrderKey,
            defaultValue = CapsuleLightBaseOrderEncoded,
        )
    val (_, onEditSessionActiveChange) =
        rememberPreference(
            CapsuleLightEditSessionActiveKey,
            defaultValue = false,
        )
    val (_, onMetadataOrderChange) =
        rememberPreference(
            CapsuleLightMetadataOrderKey,
            defaultValue = CapsuleLightMetadataBaseOrderEncoded,
        )
    val (_, onModeOrderChange) =
        rememberPreference(
            CapsuleLightModeOrderKey,
            defaultValue = CapsuleLightModeBaseOrderEncoded,
        )
    val (_, onAvOrderChange) =
        rememberPreference(
            CapsuleLightAvOrderKey,
            defaultValue = CapsuleLightAvBaseOrderEncoded,
        )
    val (_, onTransportOrderChange) =
        rememberPreference(
            CapsuleLightTransportOrderKey,
            defaultValue = CapsuleLightTransportBaseOrderEncoded,
        )
    val (_, onArtworkWidthScaleChange) =
        rememberPreference(
            CapsuleLightArtworkWidthScaleKey,
            defaultValue = 1f,
        )
    val (_, onArtworkHeightScaleChange) =
        rememberPreference(
            CapsuleLightArtworkHeightScaleKey,
            defaultValue = 1f,
        )

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ),
            )
            .verticalScroll(
                rememberScrollState(),
                flingBehavior = rememberSettingsFlingBehavior(),
            ),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
            ),
        )

        PreferenceGroupTitle(
            title = stringResource(R.string.capsule_customize_screen_section),
        )

        EnumListPreference(
            title = { Text(stringResource(R.string.capsule_customize_screen)) },
            icon = {
                Icon(
                    painterResource(R.drawable.grid_view),
                    contentDescription = null,
                )
            },
            selectedValue = target,
            onValueSelected = onTargetChange,
            valueText = {
                when (it) {
                    CapsuleCustomizeTarget.LIGHT ->
                        stringResource(R.string.capsule_player_light)
                }
            },
        )

        PreferenceGroupTitle(
            title = stringResource(R.string.capsule_customize_edit_section),
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.capsule_light_edit_screen)) },
            description = stringResource(R.string.capsule_customize_edit_description),
            icon = {
                Icon(
                    painterResource(R.drawable.edit),
                    contentDescription = null,
                )
            },
            checked = editEnabled,
            onCheckedChange = { enabled ->
                onEditEnabledChange(enabled)
                if (!enabled) {
                    onEditSessionActiveChange(false)
                }
            },
        )

        PreferenceEntry(
            title = { Text(stringResource(R.string.capsule_light_reset_screen)) },
            description = stringResource(R.string.capsule_customize_reset_description),
            icon = {
                Icon(
                    painterResource(R.drawable.restore),
                    contentDescription = null,
                )
            },
            onClick = {
                onLayoutOrderChange(CapsuleLightBaseOrderEncoded)
                onMetadataOrderChange(CapsuleLightMetadataBaseOrderEncoded)
                onModeOrderChange(CapsuleLightModeBaseOrderEncoded)
                onAvOrderChange(CapsuleLightAvBaseOrderEncoded)
                onTransportOrderChange(CapsuleLightTransportBaseOrderEncoded)
                onArtworkWidthScaleChange(1f)
                onArtworkHeightScaleChange(1f)
                onEditEnabledChange(false)
                onEditSessionActiveChange(false)
            },
        )

        Text(
            text = stringResource(R.string.capsule_customize_hint),
            modifier = Modifier
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal),
                ),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(96.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.capsule_customize_title)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}
