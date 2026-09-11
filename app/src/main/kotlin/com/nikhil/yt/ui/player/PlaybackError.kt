/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackException
import android.widget.Toast
import com.nikhil.yt.R
import com.nikhil.yt.constants.InnerTubeCookieKey
import com.nikhil.yt.innertube.utils.parseCookieString
import com.nikhil.yt.playback.PlaybackFailureClassifier
import com.nikhil.yt.playback.PlaybackFailureKind
import com.nikhil.yt.utils.httpFailureStatus
import com.nikhil.yt.utils.rememberPreference

@Composable
fun PlaybackError(
    error: PlaybackException,
    retry: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val fallbackUnknown = stringResource(R.string.error_unknown)
    val fallbackNoStream = stringResource(R.string.error_no_stream)
    val retryText = stringResource(R.string.retry)
    val copyText = stringResource(R.string.copy)
    val copiedText = stringResource(R.string.copied)
    val restrictedTitle =
        stringResource(R.string.error_youtube_network_restricted_title)
    val restrictedDescription =
        stringResource(R.string.error_youtube_network_restricted_description)
    val recommendationTitle =
        stringResource(R.string.error_youtube_network_restricted_recommendation_title)
    val recommendation =
        stringResource(R.string.error_youtube_network_restricted_recommendation)
    val restrictedTechnical =
        stringResource(R.string.error_youtube_network_restricted_technical)
    val (cookie) = rememberPreference(InnerTubeCookieKey, "")
    val authenticated = remember(cookie) { "SAPISID" in parseCookieString(cookie) }
    val failureKind = remember(error, authenticated) {
        PlaybackFailureClassifier.classify(error, authenticated)
    }
    val httpCode = error.httpFailureStatus()
    val isYouTubeBotCheck = failureKind == PlaybackFailureKind.BOT_CHECK
    val title = when (failureKind) {
        PlaybackFailureKind.AUTH_REQUIRED -> stringResource(R.string.error_auth_required_title)
        PlaybackFailureKind.AGE_RESTRICTED -> stringResource(R.string.error_age_restricted_title)
        PlaybackFailureKind.ACCESS_RESTRICTED -> stringResource(R.string.error_access_restricted_title)
        PlaybackFailureKind.NETWORK -> stringResource(R.string.error_network_problem_title)
        PlaybackFailureKind.BOT_CHECK -> restrictedTitle
        PlaybackFailureKind.GENERIC -> fallbackUnknown
    }
    val reason =
        when {
            isYouTubeBotCheck -> restrictedDescription
            failureKind == PlaybackFailureKind.AUTH_REQUIRED -> stringResource(R.string.error_auth_required_description)
            failureKind == PlaybackFailureKind.AGE_RESTRICTED -> stringResource(R.string.error_age_restricted_description)
            failureKind == PlaybackFailureKind.ACCESS_RESTRICTED -> stringResource(R.string.error_access_restricted_description)
            failureKind == PlaybackFailureKind.NETWORK -> stringResource(R.string.error_network_problem_description)
            httpCode in setOf(403, 404, 410, 416) -> fallbackNoStream
            error.errorCode in setOf(
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            ) -> "$fallbackUnknown (code ${error.errorCode})"
            httpCode != null -> "$fallbackUnknown (HTTP $httpCode)"
            else -> error.cause?.message?.takeIf { it.isNotBlank() }
                ?: error.message?.takeIf { it.isNotBlank() }
                ?: fallbackUnknown
        }

    val rawDetails =
        remember(error, reason, httpCode) {
            buildString {
                appendLine(reason)
                appendLine("Code: ${error.errorCode}")
                if (httpCode != null) appendLine("HTTP: $httpCode")

                val rootMessage = error.message?.trim().orEmpty()
                if (rootMessage.isNotBlank() && rootMessage != reason) {
                    appendLine()
                    appendLine("Message: $rootMessage")
                }

                var t: Throwable? = error.cause
                var depth = 0
                while (t != null && depth < 6) {
                    val name = t.javaClass.simpleName.ifBlank { t.javaClass.name }
                    val msg = t.message?.trim().orEmpty()
                    appendLine()
                    appendLine("Cause: $name${if (msg.isNotBlank()) ": $msg" else ""}")
                    t = t.cause
                    depth++
                }
            }.trim()
        }
    val visibleDetails =
        if (isYouTubeBotCheck) {
            "$restrictedTechnical\nCode: ${error.errorCode}"
        } else {
            rawDetails
        }

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.86f),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    painter = painterResource(R.drawable.info),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 6,
                        overflow = TextOverflow.Clip,
                    )
                }
            }

            if (isYouTubeBotCheck) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.09f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = recommendationTitle,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = recommendation,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.92f),
                        )
                    }
                }
            }

            if (failureKind == PlaybackFailureKind.GENERIC || isYouTubeBotCheck) Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.06f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = visibleDetails,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.92f),
                    modifier = Modifier.padding(12.dp),
                    maxLines = if (isYouTubeBotCheck) 3 else 12,
                    overflow = TextOverflow.Clip,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = retry,
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                ) {
                    Text(text = retryText)
                }

                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(rawDetails))
                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onErrorContainer,
                            contentColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.select_all),
                        contentDescription = null,
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                    Text(text = copyText)
                }
            }
        }
    }
}
