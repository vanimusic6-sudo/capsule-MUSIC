package com.nikhil.yt.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nikhil.yt.R
import com.nikhil.yt.constants.AudioClientOrder
import com.nikhil.yt.constants.AudioStreamPolicy

/**
 * The audio client ranking. All of the behaviour lives in [PriorityOrderDialog]; this is the
 * labelling and the reset rule, which are the only parts specific to stream clients.
 */
@Composable
internal fun AudioClientPriorityDialog(
    currentOrder: List<String>,
    resetOrder: List<String>,
    onDismiss: () -> Unit,
    onOrderChange: (List<String>) -> Unit,
) {
    PriorityOrderDialog(
        title = stringResource(R.string.audio_client_priority_title),
        description = stringResource(R.string.audio_client_priority_description),
        note = stringResource(R.string.audio_client_priority_safety_note),
        currentOrder = currentOrder,
        onReset = {
            AudioClientOrder.resolve(
                raw = resetOrder.joinToString(","),
                legacyPolicy = AudioStreamPolicy.VISIONOS,
            )
        },
        onDismiss = onDismiss,
        onOrderChange = onOrderChange,
        label = { audioClientTitle(it) },
        sublabel = { audioClientDescription(it) },
    )
}

@Composable
private fun audioClientTitle(profileId: String): String =
    when (profileId) {
        AudioClientOrder.VISIONOS -> stringResource(R.string.audio_client_visionos)
        AudioClientOrder.VISIONOS_0_1 -> stringResource(R.string.audio_client_visionos_compat)
        AudioClientOrder.WEB_REMIX -> stringResource(R.string.audio_client_web_remix)
        AudioClientOrder.WEB_EMBEDDED -> stringResource(R.string.audio_client_web_embedded)
        AudioClientOrder.WEB_CREATOR -> stringResource(R.string.audio_client_web_creator)
        AudioClientOrder.TVHTML5_SIMPLY -> stringResource(R.string.audio_client_tv)
        else -> profileId
    }

@Composable
private fun audioClientDescription(profileId: String): String =
    when (profileId) {
        AudioClientOrder.VISIONOS -> stringResource(R.string.audio_client_visionos_description)
        AudioClientOrder.VISIONOS_0_1 -> stringResource(R.string.audio_client_visionos_compat_description)
        AudioClientOrder.WEB_REMIX -> stringResource(R.string.audio_client_web_remix_description)
        AudioClientOrder.WEB_EMBEDDED -> stringResource(R.string.audio_client_web_embedded_description)
        AudioClientOrder.WEB_CREATOR -> stringResource(R.string.audio_client_web_creator_description)
        AudioClientOrder.TVHTML5_SIMPLY -> stringResource(R.string.audio_client_tv_description)
        else -> profileId
    }
