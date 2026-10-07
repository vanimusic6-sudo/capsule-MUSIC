/*
 * Capsule MUSIC
 * Personal Capsule customization hub.
 * GPL-3.0
 */

package com.nikhil.yt.ui.screens.settings

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.R
import com.nikhil.yt.constants.CapsuleCustomizeTarget
import com.nikhil.yt.constants.CapsuleCustomizeTargetKey
import com.nikhil.yt.constants.CapsulePlayerDesign
import com.nikhil.yt.constants.CapsulePlayerDesignKey
import com.nikhil.yt.constants.CapsuleLightEditEnabledKey
import com.nikhil.yt.constants.CapsuleLightEditSessionActiveKey
import com.nikhil.yt.constants.CapsuleLightLyricLineKey
import com.nikhil.yt.constants.CapsuleLightLayoutOrderKey
import com.nikhil.yt.constants.CapsuleLightMetadataOrderKey
import com.nikhil.yt.constants.CapsuleLightModeOrderKey
import com.nikhil.yt.constants.CapsuleLightAvOrderKey
import com.nikhil.yt.constants.CapsuleLightTransportOrderKey
import com.nikhil.yt.constants.CapsuleLightArtworkWidthScaleKey
import com.nikhil.yt.constants.CapsuleLightArtworkHeightScaleKey
import com.nikhil.yt.constants.CapsuleLightBlockGapsKey
import com.nikhil.yt.constants.CapsuleLightCanvasPositionsKey
import com.nikhil.yt.constants.CapsuleImmersiveEditEnabledKey
import com.nikhil.yt.constants.CapsuleImmersiveLayoutOrderKey
import com.nikhil.yt.constants.CapsuleImmersiveCanvasPositionsKey
import com.nikhil.yt.constants.CapsuleImmersiveMetadataOrderKey
import com.nikhil.yt.constants.CapsuleImmersiveModeOrderKey
import com.nikhil.yt.constants.CapsuleImmersiveAvOrderKey
import com.nikhil.yt.constants.CapsuleImmersiveTransportOrderKey
import com.nikhil.yt.ui.component.IconButton
import com.nikhil.yt.ui.component.PreferenceEntry
import com.nikhil.yt.ui.component.PreferenceGroupTitle
import com.nikhil.yt.ui.component.SwitchPreference
import com.nikhil.yt.ui.player.CapsuleDesignPreset
import com.nikhil.yt.ui.player.CapsuleDesignPresetCodec
import com.nikhil.yt.ui.player.CapsuleLightBaseOrderEncoded
import com.nikhil.yt.ui.player.CapsuleLightBaseGapsEncoded
import com.nikhil.yt.ui.player.CapsuleLightCanvasPositionsBaseEncoded
import com.nikhil.yt.ui.player.CapsuleLightMetadataBaseOrderEncoded
import com.nikhil.yt.ui.player.CapsuleLightModeBaseOrderEncoded
import com.nikhil.yt.ui.player.CapsuleLightAvBaseOrderEncoded
import com.nikhil.yt.ui.player.CapsuleLightTransportBaseOrderEncoded
import com.nikhil.yt.ui.player.CapsuleImmersiveBaseOrderEncoded
import com.nikhil.yt.ui.player.readCapsuleDesignPresetFile
import com.nikhil.yt.ui.player.writeCapsuleDesignPresetFile
import com.nikhil.yt.ui.utils.backToMain
import com.nikhil.yt.utils.rememberEnumPreference
import com.nikhil.yt.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapsuleCustomizeSettings(
    navController: NavController,
) {
    val (latchedTarget, onLatchedTargetChange) =
        rememberEnumPreference(
            CapsuleCustomizeTargetKey,
            defaultValue = CapsuleCustomizeTarget.LIGHT,
        )
    val (playerDesign, _) =
        rememberEnumPreference(
            CapsulePlayerDesignKey,
            defaultValue = CapsulePlayerDesign.SUPER,
        )
    val context = LocalContext.current

    val (lightEditEnabled, onLightEditEnabledChange) =
        rememberPreference(
            CapsuleLightEditEnabledKey,
            defaultValue = false,
        )
    val (immersiveEditEnabled, onImmersiveEditEnabledChange) =
        rememberPreference(
            CapsuleImmersiveEditEnabledKey,
            defaultValue = false,
        )

    val currentSupportedTarget =
        when (playerDesign) {
            CapsulePlayerDesign.LIGHT -> CapsuleCustomizeTarget.LIGHT
            CapsulePlayerDesign.IMMERSIVE -> CapsuleCustomizeTarget.IMMERSIVE
            CapsulePlayerDesign.SUPER -> null
        }

    // Once editing is enabled, keep editing the design that was active at that exact moment.
    // Changing the player design elsewhere must not silently move an active edit session.
    val activeTarget =
        when {
            lightEditEnabled && immersiveEditEnabled -> latchedTarget
            lightEditEnabled -> CapsuleCustomizeTarget.LIGHT
            immersiveEditEnabled -> CapsuleCustomizeTarget.IMMERSIVE
            else -> null
        }
    val effectiveTarget = activeTarget ?: currentSupportedTarget
    val editEnabled = activeTarget != null
    val editAvailable = activeTarget != null || currentSupportedTarget != null
    val (layoutOrder, onLayoutOrderChange) =
        rememberPreference(
            CapsuleLightLayoutOrderKey,
            defaultValue = CapsuleLightBaseOrderEncoded,
        )
    val (_, onEditSessionActiveChange) =
        rememberPreference(
            CapsuleLightEditSessionActiveKey,
            defaultValue = false,
        )
    val (metadataOrder, onMetadataOrderChange) =
        rememberPreference(
            CapsuleLightMetadataOrderKey,
            defaultValue = CapsuleLightMetadataBaseOrderEncoded,
        )
    val (modeOrder, onModeOrderChange) =
        rememberPreference(
            CapsuleLightModeOrderKey,
            defaultValue = CapsuleLightModeBaseOrderEncoded,
        )
    val (avOrder, onAvOrderChange) =
        rememberPreference(
            CapsuleLightAvOrderKey,
            defaultValue = CapsuleLightAvBaseOrderEncoded,
        )
    val (transportOrder, onTransportOrderChange) =
        rememberPreference(
            CapsuleLightTransportOrderKey,
            defaultValue = CapsuleLightTransportBaseOrderEncoded,
        )
    val (artworkWidthScale, onArtworkWidthScaleChange) =
        rememberPreference(
            CapsuleLightArtworkWidthScaleKey,
            defaultValue = 1f,
        )
    val (artworkHeightScale, onArtworkHeightScaleChange) =
        rememberPreference(
            CapsuleLightArtworkHeightScaleKey,
            defaultValue = 1f,
        )
    val (blockGaps, onBlockGapsChange) =
        rememberPreference(
            CapsuleLightBlockGapsKey,
            defaultValue = CapsuleLightBaseGapsEncoded,
        )
    val (canvasPositions, onCanvasPositionsChange) =
        rememberPreference(
            CapsuleLightCanvasPositionsKey,
            defaultValue = CapsuleLightCanvasPositionsBaseEncoded,
        )

    val (lyricLineEnabled, onLyricLineEnabledChange) =
        rememberPreference(
            CapsuleLightLyricLineKey,
            defaultValue = true,
        )

    val (immersiveLayoutOrder, onImmersiveLayoutOrderChange) =
        rememberPreference(
            CapsuleImmersiveLayoutOrderKey,
            defaultValue = CapsuleImmersiveBaseOrderEncoded,
        )
    val (immersiveCanvasPositions, onImmersiveCanvasPositionsChange) =
        rememberPreference(
            CapsuleImmersiveCanvasPositionsKey,
            defaultValue = CapsuleLightCanvasPositionsBaseEncoded,
        )
    val (immersiveMetadataOrder, onImmersiveMetadataOrderChange) =
        rememberPreference(
            CapsuleImmersiveMetadataOrderKey,
            defaultValue = CapsuleLightMetadataBaseOrderEncoded,
        )
    val (immersiveModeOrder, onImmersiveModeOrderChange) =
        rememberPreference(
            CapsuleImmersiveModeOrderKey,
            defaultValue = CapsuleLightModeBaseOrderEncoded,
        )
    val (immersiveAvOrder, onImmersiveAvOrderChange) =
        rememberPreference(
            CapsuleImmersiveAvOrderKey,
            defaultValue = CapsuleLightAvBaseOrderEncoded,
        )
    val (immersiveTransportOrder, onImmersiveTransportOrderChange) =
        rememberPreference(
            CapsuleImmersiveTransportOrderKey,
            defaultValue = CapsuleLightTransportBaseOrderEncoded,
        )

    fun presetFor(target: CapsuleCustomizeTarget): CapsuleDesignPreset =
        when (target) {
            CapsuleCustomizeTarget.LIGHT ->
                CapsuleDesignPreset(
                    target = target,
                    layoutOrder = layoutOrder,
                    canvasPositions = canvasPositions,
                    metadataOrder = metadataOrder,
                    modeOrder = modeOrder,
                    avOrder = avOrder,
                    transportOrder = transportOrder,
                    artworkWidthScale = artworkWidthScale,
                    artworkHeightScale = artworkHeightScale,
                    blockGaps = blockGaps,
                    lyricLineEnabled = lyricLineEnabled,
                )

            CapsuleCustomizeTarget.IMMERSIVE ->
                CapsuleDesignPreset(
                    target = target,
                    layoutOrder = immersiveLayoutOrder,
                    canvasPositions = immersiveCanvasPositions,
                    metadataOrder = immersiveMetadataOrder,
                    modeOrder = immersiveModeOrder,
                    avOrder = immersiveAvOrder,
                    transportOrder = immersiveTransportOrder,
                )
        }

    fun applyPreset(preset: CapsuleDesignPreset) {
        // Never import into a live drag transaction.
        onLightEditEnabledChange(false)
        onImmersiveEditEnabledChange(false)
        onEditSessionActiveChange(false)
        onLatchedTargetChange(preset.target)

        when (preset.target) {
            CapsuleCustomizeTarget.LIGHT -> {
                onLayoutOrderChange(preset.layoutOrder)
                onCanvasPositionsChange(preset.canvasPositions)
                onMetadataOrderChange(preset.metadataOrder)
                onModeOrderChange(preset.modeOrder)
                onAvOrderChange(preset.avOrder)
                onTransportOrderChange(preset.transportOrder)
                onArtworkWidthScaleChange(preset.artworkWidthScale ?: 1f)
                onArtworkHeightScaleChange(preset.artworkHeightScale ?: 1f)
                onBlockGapsChange(preset.blockGaps ?: CapsuleLightBaseGapsEncoded)
                onLyricLineEnabledChange(preset.lyricLineEnabled ?: true)
            }

            CapsuleCustomizeTarget.IMMERSIVE -> {
                onImmersiveLayoutOrderChange(preset.layoutOrder)
                onImmersiveCanvasPositionsChange(preset.canvasPositions)
                onImmersiveMetadataOrderChange(preset.metadataOrder)
                onImmersiveModeOrderChange(preset.modeOrder)
                onImmersiveAvOrderChange(preset.avOrder)
                onImmersiveTransportOrderChange(preset.transportOrder)
            }
        }
    }

    val shareTarget = effectiveTarget ?: latchedTarget
    val shareChooserTitle = stringResource(R.string.capsule_customize_share_chooser)
    val importPresetLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                val preset =
                    readCapsuleDesignPresetFile(context, uri)
                        ?.let(CapsuleDesignPresetCodec::decode)

                if (preset == null) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.capsule_customize_import_invalid),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    applyPreset(preset)
                    Toast.makeText(
                        context,
                        context.getString(R.string.capsule_customize_import_success),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal),
            )
            .verticalScroll(
                rememberScrollState(),
                flingBehavior = rememberSettingsFlingBehavior(),
            )
            .padding(bottom = settingsBottomContentPadding()),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
            ),
        )

        PreferenceGroupTitle(
            title = stringResource(R.string.capsule_customize_edit_section),
        )

        SwitchPreference(
            title = {
                Text(stringResource(R.string.capsule_customize_edit_toggle))
            },
            description = stringResource(R.string.capsule_customize_edit_description),
            icon = {
                Icon(
                    painterResource(R.drawable.edit),
                    contentDescription = null,
                )
            },
            checked = editEnabled,
            isEnabled = editAvailable,
            onCheckedChange = { enabled ->
                if (!enabled) {
                    // Off means no structural editor is active, regardless of legacy state.
                    onLightEditEnabledChange(false)
                    onImmersiveEditEnabledChange(false)
                    onEditSessionActiveChange(false)
                } else {
                    currentSupportedTarget?.let { target ->
                        onLatchedTargetChange(target)
                        // Exactly one editor may own the gesture system.
                        onLightEditEnabledChange(target == CapsuleCustomizeTarget.LIGHT)
                        onImmersiveEditEnabledChange(target == CapsuleCustomizeTarget.IMMERSIVE)
                        if (target != CapsuleCustomizeTarget.LIGHT) {
                            onEditSessionActiveChange(false)
                        }
                    }
                }
            },
        )

        PreferenceEntry(
            title = {
                Text(stringResource(R.string.reset))
            },
            description = stringResource(R.string.capsule_customize_reset_description),
            icon = {
                Icon(
                    painterResource(R.drawable.restore),
                    contentDescription = null,
                )
            },
            isEnabled = effectiveTarget != null,
            onClick = {
                when (effectiveTarget) {
                    CapsuleCustomizeTarget.LIGHT -> {
                        onLayoutOrderChange(CapsuleLightBaseOrderEncoded)
                        onMetadataOrderChange(CapsuleLightMetadataBaseOrderEncoded)
                        onModeOrderChange(CapsuleLightModeBaseOrderEncoded)
                        onAvOrderChange(CapsuleLightAvBaseOrderEncoded)
                        onTransportOrderChange(CapsuleLightTransportBaseOrderEncoded)
                        onArtworkWidthScaleChange(1f)
                        onArtworkHeightScaleChange(1f)
                        onBlockGapsChange(CapsuleLightBaseGapsEncoded)
                        onCanvasPositionsChange(CapsuleLightCanvasPositionsBaseEncoded)
                        onLightEditEnabledChange(false)
                        onEditSessionActiveChange(false)
                    }
                    CapsuleCustomizeTarget.IMMERSIVE -> {
                        onImmersiveLayoutOrderChange(CapsuleImmersiveBaseOrderEncoded)
                        onImmersiveCanvasPositionsChange(
                            CapsuleLightCanvasPositionsBaseEncoded,
                        )
                        onImmersiveMetadataOrderChange(
                            CapsuleLightMetadataBaseOrderEncoded,
                        )
                        onImmersiveModeOrderChange(CapsuleLightModeBaseOrderEncoded)
                        onImmersiveAvOrderChange(CapsuleLightAvBaseOrderEncoded)
                        onImmersiveTransportOrderChange(
                            CapsuleLightTransportBaseOrderEncoded,
                        )
                        onImmersiveEditEnabledChange(false)
                    }
                    null -> Unit
                }
            },
        )

        PreferenceGroupTitle(
            title = stringResource(R.string.capsule_customize_share_section),
        )

        PreferenceEntry(
            title = {
                Text(stringResource(R.string.capsule_customize_share))
            },
            description = stringResource(R.string.capsule_customize_share_description),
            icon = {
                Icon(
                    painterResource(R.drawable.share),
                    contentDescription = null,
                )
            },
            onClick = {
                runCatching {
                    val payload = CapsuleDesignPresetCodec.encode(presetFor(shareTarget))
                    val (file, uri) =
                        writeCapsuleDesignPresetFile(
                            context = context,
                            target = shareTarget,
                            payload = payload,
                        )
                    val shareIntent =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "application/octet-stream"
                            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri(file.name, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }

                    context.startActivity(
                        Intent.createChooser(
                            shareIntent,
                            shareChooserTitle,
                        ),
                    )
                }.onFailure {
                    Toast.makeText(
                        context,
                        context.getString(R.string.capsule_customize_share_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )

        PreferenceEntry(
            title = {
                Text(stringResource(R.string.capsule_customize_import))
            },
            description = stringResource(R.string.capsule_customize_import_entry_description),
            icon = {
                Icon(
                    painterResource(R.drawable.restore),
                    contentDescription = null,
                )
            },
            onClick = {
                importPresetLauncher.launch(arrayOf("*/*"))
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
