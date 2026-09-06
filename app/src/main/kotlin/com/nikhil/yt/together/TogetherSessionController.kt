package com.nikhil.yt.together

import com.nikhil.yt.extensions.SilentHandler
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun normalizedTogetherDisplayName(
    raw: String,
    fallback: String,
): String = raw.trim().ifBlank { fallback }

/**
 * Owns Together LAN/online host session lifecycle and network jobs.
 *
 * Player snapshots and host control mutations stay in MusicService through
 * narrow callbacks; this class never reaches into ExoPlayer.
 */
internal class TogetherSessionController(
    private val runtime: TogetherSessionRuntime,
    private val mainScopeProvider: () -> CoroutineScope,
    private val ioScopeProvider: () -> CoroutineScope,
    private val hostId: String,
    private val appNameProvider: () -> String,
    private val localIpv4Provider: () -> String?,
    private val onlineBaseUrlProvider: () -> String?,
    private val onlineTokenProvider: () -> String?,
    private val clientIdProvider: suspend () -> String,
    private val roomStateProvider: suspend (sessionId: String, hostId: String) -> TogetherRoomState,
    private val onlineErrorMessage: (Throwable) -> String,
    private val onlineNotConfiguredMessage: () -> String,
    private val tokenMissingMessage: () -> String,
    private val invalidWebSocketMessage: () -> String,
    private val hostEventHandler: suspend (
        event: TogetherServerEvent,
        currentSettings: suspend () -> TogetherRoomSettings,
    ) -> Unit,
    private val stopCurrentSession: suspend () -> Unit,
    private val onOnlineFailure: (Throwable) -> Unit,
) {
    val sessionState = runtime.sessionState

    fun startLanHost(
        port: Int,
        displayName: String,
        settings: TogetherRoomSettings,
    ) {
        mainScopeProvider().launch(SilentHandler) {
            sessionState.value = TogetherSessionState.Idle
        }

        ioScopeProvider().launch(SilentHandler) {
            stopCurrentSession()
            runtime.isOnlineSession = false

            val localIp = localIpv4Provider()
            val sessionId = UUID.randomUUID().toString()
            val sessionKey = UUID.randomUUID().toString()
            val hostName = normalizedTogetherDisplayName(displayName, appNameProvider())
            val joinInfo =
                TogetherJoinInfo(
                    host = localIp ?: "127.0.0.1",
                    port = port,
                    sessionId = sessionId,
                    sessionKey = sessionKey,
                )
            val joinLink = TogetherLink.encode(joinInfo)
            val server =
                TogetherServer(
                    scope = ioScopeProvider(),
                    sessionId = sessionId,
                    sessionKey = sessionKey,
                    hostDisplayName = hostName,
                    initialSettings = settings,
                )

            server.onEvent = { event ->
                ioScopeProvider().launch(SilentHandler) {
                    hostEventHandler(event) { server.currentSettings() }
                }
            }

            server.start(port)
            runtime.server = server

            mainScopeProvider().launch(SilentHandler) {
                sessionState.value =
                    TogetherSessionState.Hosting(
                        sessionId = sessionId,
                        joinLink = joinLink,
                        localAddressHint = localIp,
                        port = port,
                        settings = settings,
                        roomState = null,
                    )
            }

            runtime.broadcastJob =
                ioScopeProvider().launch(SilentHandler) {
                    while (runtime.server === server) {
                        val state = roomStateProvider(sessionId, hostId)
                        server.broadcastRoomState(state)
                        mainScopeProvider().launch(SilentHandler) {
                            val hosting = sessionState.value as? TogetherSessionState.Hosting
                            if (hosting?.sessionId == sessionId) {
                                val currentSettings = server.currentSettings()
                                sessionState.value =
                                    hosting.copy(
                                        settings = currentSettings,
                                        roomState =
                                            state.copy(
                                                participants = server.currentParticipants(),
                                                settings = currentSettings,
                                            ),
                                    )
                            }
                        }
                        delay(HOST_BROADCAST_INTERVAL_MS)
                    }
                }
        }
    }

    fun startOnlineHost(
        displayName: String,
        settings: TogetherRoomSettings,
    ) {
        mainScopeProvider().launch(SilentHandler) {
            sessionState.value = TogetherSessionState.Idle
        }

        ioScopeProvider().launch(SilentHandler) {
            stopCurrentSession()
            runtime.isOnlineSession = true

            val baseUrl = onlineBaseUrlProvider()
            if (baseUrl.isNullOrBlank()) {
                mainScopeProvider().launch(SilentHandler) {
                    sessionState.value =
                        TogetherSessionState.Error(
                            message = onlineNotConfiguredMessage(),
                            recoverable = true,
                        )
                }
                return@launch
            }

            val token = onlineTokenProvider()
            if (token.isNullOrBlank()) {
                mainScopeProvider().launch(SilentHandler) {
                    sessionState.value =
                        TogetherSessionState.Error(
                            message = tokenMissingMessage(),
                            recoverable = true,
                        )
                }
                return@launch
            }

            val api = TogetherOnlineApi(baseUrl = baseUrl, bearerToken = token)
            val hostName = normalizedTogetherDisplayName(displayName, appNameProvider())
            val created =
                try {
                    api.createSession(
                        hostDisplayName = hostName,
                        settings = settings,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    mainScopeProvider().launch(SilentHandler) {
                        sessionState.value =
                            TogetherSessionState.Error(
                                message = onlineErrorMessage(error),
                                recoverable = true,
                            )
                    }
                    onOnlineFailure(error)
                    return@launch
                }

            val onlineHost =
                TogetherOnlineHost(
                    externalScope = ioScopeProvider(),
                    sessionId = created.sessionId,
                    sessionKey = created.hostKey,
                    hostId = hostId,
                    hostDisplayName = hostName,
                    initialSettings = created.settings,
                    clientId = clientIdProvider(),
                    bearerToken = token,
                )

            onlineHost.onEvent = { event ->
                ioScopeProvider().launch(SilentHandler) {
                    hostEventHandler(event) { onlineHost.currentSettings() }
                }
            }
            runtime.onlineHost = onlineHost

            mainScopeProvider().launch(SilentHandler) {
                sessionState.value =
                    TogetherSessionState.HostingOnline(
                        sessionId = created.sessionId,
                        code = created.code,
                        settings = created.settings,
                        roomState = null,
                    )
            }

            val wsUrl =
                TogetherOnlineEndpoint.onlineWebSocketUrlOrNull(
                    rawWsUrl = created.wsUrl,
                    baseUrl = baseUrl,
                )
            if (wsUrl.isNullOrBlank()) {
                mainScopeProvider().launch(SilentHandler) {
                    sessionState.value =
                        TogetherSessionState.Error(
                            message = invalidWebSocketMessage(),
                            recoverable = true,
                        )
                }
                ioScopeProvider().launch(SilentHandler) {
                    stopCurrentSession()
                }
                return@launch
            }

            runtime.onlineConnectJob?.cancel()
            runtime.onlineConnectJob =
                ioScopeProvider().launch(SilentHandler) {
                    onlineHost.connect(wsUrl)
                }

            runtime.broadcastJob =
                ioScopeProvider().launch(SilentHandler) {
                    while (runtime.onlineHost === onlineHost) {
                        val state = roomStateProvider(created.sessionId, hostId)
                        onlineHost.broadcastRoomState(state)
                        mainScopeProvider().launch(SilentHandler) {
                            val hosting = sessionState.value as? TogetherSessionState.HostingOnline
                            if (hosting?.sessionId == created.sessionId) {
                                val currentSettings = onlineHost.currentSettings()
                                sessionState.value =
                                    hosting.copy(
                                        settings = currentSettings,
                                        roomState =
                                            state.copy(
                                                participants = onlineHost.currentParticipants(),
                                                settings = currentSettings,
                                            ),
                                    )
                            }
                        }
                        delay(HOST_BROADCAST_INTERVAL_MS)
                    }
                }
        }
    }

    companion object {
        internal const val HOST_BROADCAST_INTERVAL_MS = 750L
    }
}
