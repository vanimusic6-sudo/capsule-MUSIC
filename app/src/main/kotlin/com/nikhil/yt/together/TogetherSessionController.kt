package com.nikhil.yt.together

import android.os.SystemClock
import com.nikhil.yt.extensions.SilentHandler
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber

internal fun normalizedTogetherDisplayName(
    raw: String,
    fallback: String,
): String = raw.trim().ifBlank { fallback }

internal fun initialGuestRoomState(
    sessionId: String,
    hostId: String,
    participantId: String,
    displayName: String,
    isPending: Boolean,
    settings: TogetherRoomSettings,
    sentAtElapsedRealtimeMs: Long,
): TogetherRoomState =
    TogetherRoomState(
        sessionId = sessionId,
        hostId = hostId,
        participants =
            listOf(
                TogetherParticipant(
                    id = participantId,
                    name = displayName,
                    isHost = false,
                    isPending = isPending,
                    isConnected = true,
                ),
            ),
        settings = settings,
        queue = emptyList(),
        queueHash = "",
        currentIndex = 0,
        isPlaying = false,
        positionMs = 0L,
        repeatMode = 0,
        shuffleEnabled = false,
        sentAtElapsedRealtimeMs = sentAtElapsedRealtimeMs,
    )

/**
 * Owns Together transport/session lifecycle for LAN host, online host and guest.
 *
 * ExoPlayer snapshots and player mutations stay in MusicService through narrow
 * callbacks; this class owns network clients, event collection, heartbeat and
 * session-state transitions only.
 */
internal class TogetherSessionController(
    private val runtime: TogetherSessionRuntime,
    private val mainScopeProvider: () -> CoroutineScope,
    private val ioScopeProvider: () -> CoroutineScope,
    private val hostId: String,
    private val appNameProvider: () -> String,
    private val guestNameProvider: () -> String = { "Guest" },
    private val localIpv4Provider: () -> String?,
    private val onlineBaseUrlProvider: () -> String?,
    private val onlineTokenProvider: () -> String?,
    private val clientIdProvider: suspend () -> String,
    private val roomStateProvider: suspend (sessionId: String, hostId: String) -> TogetherRoomState,
    private val onlineErrorMessage: (Throwable) -> String,
    private val onlineNotConfiguredMessage: () -> String,
    private val tokenMissingMessage: () -> String,
    private val invalidWebSocketMessage: () -> String,
    private val invalidLinkMessage: () -> String = { "Invalid link" },
    private val invalidCodeMessage: () -> String = { "Invalid code" },
    private val notAllowedMessage: () -> String = { "Not allowed" },
    private val hostLeftMessage: () -> String = { "Host left the session" },
    private val networkUnavailableMessage: () -> String = { "Network unavailable" },
    private val hostEventHandler: suspend (
        event: TogetherServerEvent,
        currentSettings: suspend () -> TogetherRoomSettings,
    ) -> Unit,
    private val remoteStateApplier: suspend (TogetherRoomState) -> Unit = {},
    private val guestControlReset: () -> Unit = {},
    private val guestNotice: (message: String, key: String) -> Unit = { _, _ -> },
    private val stopCurrentSession: suspend () -> Unit,
    private val onOnlineFailure: (Throwable) -> Unit,
) {
    private enum class GuestJoinMode {
        LAN,
        ONLINE,
    }

    val sessionState = runtime.sessionState

    @Volatile
    private var guestTerminationRequested = false

    fun startLanHost(
        port: Int,
        displayName: String,
        settings: TogetherRoomSettings,
    ) {
        guestTerminationRequested = true
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
        guestTerminationRequested = true
        mainScopeProvider().launch(SilentHandler) {
            sessionState.value = TogetherSessionState.Idle
        }

        ioScopeProvider().launch(SilentHandler) {
            stopCurrentSession()
            runtime.isOnlineSession = true

            val baseUrl = onlineBaseUrlProvider()
            if (baseUrl.isNullOrBlank()) {
                publishError(onlineNotConfiguredMessage())
                return@launch
            }

            val token = onlineTokenProvider()
            if (token.isNullOrBlank()) {
                publishError(tokenMissingMessage())
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
                    publishError(onlineErrorMessage(error))
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
                publishError(invalidWebSocketMessage())
                ioScopeProvider().launch(SilentHandler) { stopCurrentSession() }
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

    fun joinLan(
        rawLink: String,
        displayName: String,
    ) {
        val joinInfo = TogetherLink.decode(rawLink)
        if (joinInfo == null) {
            publishError(invalidLinkMessage())
            return
        }

        guestTerminationRequested = true
        mainScopeProvider().launch(SilentHandler) {
            sessionState.value = TogetherSessionState.Joining(joinInfo.toDeepLink())
        }

        ioScopeProvider().launch(SilentHandler) {
            stopCurrentSession()
            runtime.isOnlineSession = false

            val guestName = normalizedTogetherDisplayName(displayName, guestNameProvider())
            val client =
                TogetherClient(
                    externalScope = ioScopeProvider(),
                    clientId = clientIdProvider(),
                )
            prepareGuestClient(
                client = client,
                sessionId = joinInfo.sessionId,
                displayName = guestName,
                mode = GuestJoinMode.LAN,
            )
            client.connect(joinInfo, guestName)
        }
    }

    fun joinOnline(
        code: String,
        displayName: String,
    ) {
        val trimmedCode = code.trim()
        if (trimmedCode.isBlank()) {
            publishError(invalidCodeMessage())
            return
        }

        guestTerminationRequested = true
        mainScopeProvider().launch(SilentHandler) {
            sessionState.value = TogetherSessionState.JoiningOnline(trimmedCode)
        }

        ioScopeProvider().launch(SilentHandler) {
            stopCurrentSession()
            runtime.isOnlineSession = true

            val baseUrl = onlineBaseUrlProvider()
            if (baseUrl.isNullOrBlank()) {
                publishError(onlineNotConfiguredMessage())
                return@launch
            }

            val token = onlineTokenProvider()
            if (token.isNullOrBlank()) {
                publishError(tokenMissingMessage())
                return@launch
            }

            val api = TogetherOnlineApi(baseUrl = baseUrl, bearerToken = token)
            val resolved =
                try {
                    api.resolveCode(trimmedCode)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    publishError(onlineErrorMessage(error))
                    onOnlineFailure(error)
                    return@launch
                }

            val guestName = normalizedTogetherDisplayName(displayName, guestNameProvider())
            val client =
                TogetherClient(
                    externalScope = ioScopeProvider(),
                    clientId = clientIdProvider(),
                    bearerToken = token,
                )
            prepareGuestClient(
                client = client,
                sessionId = resolved.sessionId,
                displayName = guestName,
                mode = GuestJoinMode.ONLINE,
            )

            val wsUrl =
                TogetherOnlineEndpoint.onlineWebSocketUrlOrNull(
                    rawWsUrl = resolved.wsUrl,
                    baseUrl = baseUrl,
                )
            if (wsUrl.isNullOrBlank()) {
                requestGuestTermination(invalidWebSocketMessage())
                return@launch
            }

            client.connect(
                wsUrl = wsUrl,
                sessionId = resolved.sessionId,
                sessionKey = resolved.guestKey,
                displayName = guestName,
            )
        }
    }

    fun leave() {
        guestTerminationRequested = true
        mainScopeProvider().launch(SilentHandler) {
            sessionState.value = TogetherSessionState.Idle
        }
        ioScopeProvider().launch(SilentHandler) {
            stopCurrentSession()
        }
    }

    private fun prepareGuestClient(
        client: TogetherClient,
        sessionId: String,
        displayName: String,
        mode: GuestJoinMode,
    ) {
        runtime.client = client
        runtime.clock = TogetherClock()
        runtime.selfParticipantId = null
        runtime.lastAppliedQueueHash = null
        guestTerminationRequested = false

        runtime.clientEventsJob?.cancel()
        runtime.clientEventsJob =
            ioScopeProvider().launch(
                context = SilentHandler,
                start = CoroutineStart.UNDISPATCHED,
            ) {
                client.events.collect { event ->
                    handleGuestEvent(
                        event = event,
                        sessionId = sessionId,
                        displayName = displayName,
                        mode = mode,
                        client = client,
                    )
                }
            }
    }

    private suspend fun handleGuestEvent(
        event: TogetherClientEvent,
        sessionId: String,
        displayName: String,
        mode: GuestJoinMode,
        client: TogetherClient,
    ) {
        when (event) {
            is TogetherClientEvent.Welcome -> {
                runtime.selfParticipantId = event.welcome.participantId
                mainScopeProvider().launch(SilentHandler) {
                    val current = sessionState.value
                    val stillJoining =
                        when (mode) {
                            GuestJoinMode.LAN -> current is TogetherSessionState.Joining
                            GuestJoinMode.ONLINE -> current is TogetherSessionState.JoiningOnline
                        }
                    if (stillJoining) {
                        sessionState.value =
                            TogetherSessionState.Joined(
                                role = TogetherRole.Guest,
                                sessionId = sessionId,
                                selfParticipantId = event.welcome.participantId,
                                roomState =
                                    initialGuestRoomState(
                                        sessionId = sessionId,
                                        hostId = hostId,
                                        participantId = event.welcome.participantId,
                                        displayName = displayName,
                                        isPending = event.welcome.isPending,
                                        settings = event.welcome.settings,
                                        sentAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                                    ),
                            )
                    }
                }
                startHeartbeat(sessionId, client)
            }

            is TogetherClientEvent.RoomState -> {
                remoteStateApplier(event.state)
            }

            is TogetherClientEvent.JoinDecision -> {
                if (!event.decision.approved) {
                    requestGuestTermination(notAllowedMessage())
                }
            }

            is TogetherClientEvent.ServerIssue -> {
                Timber.tag("Together").w(
                    "server issue (${mode.name.lowercase()}) code=${event.code.orEmpty()} message=${event.message}",
                )
                when (event.code) {
                    "GUEST_CONTROL_DISABLED" -> {
                        guestNotice(event.message, "GUEST_CONTROL_DISABLED")
                        val joined = sessionState.value as? TogetherSessionState.Joined
                        if (joined?.role is TogetherRole.Guest) {
                            guestControlReset()
                            remoteStateApplier(joined.roomState)
                        }
                    }

                    "GUEST_ADD_DISABLED" -> {
                        guestNotice(event.message, "GUEST_ADD_DISABLED")
                    }

                    "HOST_OFFLINE" -> {
                        guestNotice(event.message, "HOST_OFFLINE")
                    }

                    else -> requestGuestTermination(event.message)
                }
            }

            is TogetherClientEvent.HeartbeatPong -> {
                runtime.clock?.onPong(
                    sentAtElapsedMs = event.pong.clientElapsedRealtimeMs,
                    receivedAtElapsedMs = event.receivedAtElapsedRealtimeMs,
                    serverElapsedMs = event.pong.serverElapsedRealtimeMs,
                )
            }

            is TogetherClientEvent.Error -> {
                requestGuestTermination(event.message)
            }

            TogetherClientEvent.Disconnected -> {
                if (guestTerminationRequested) return
                val current = sessionState.value
                if (current is TogetherSessionState.Idle) return
                val message =
                    if (current is TogetherSessionState.Joined && current.role is TogetherRole.Guest) {
                        hostLeftMessage()
                    } else {
                        networkUnavailableMessage()
                    }
                requestGuestTermination(message)
            }
        }
    }

    private fun requestGuestTermination(message: String) {
        if (guestTerminationRequested) return
        guestTerminationRequested = true
        publishError(message)
        // Never stop from the collector coroutine itself: stopConnections()
        // cancels clientEventsJob and may otherwise cancel its own cleanup.
        ioScopeProvider().launch(SilentHandler) {
            stopCurrentSession()
        }
    }

    private fun publishError(message: String) {
        mainScopeProvider().launch(SilentHandler) {
            sessionState.value =
                TogetherSessionState.Error(
                    message = message,
                    recoverable = true,
                )
        }
    }

    private fun startHeartbeat(
        sessionId: String,
        client: TogetherClient,
    ) {
        runtime.heartbeatJob?.cancel()
        runtime.heartbeatJob =
            ioScopeProvider().launch(SilentHandler) {
                var pingId = 0L
                while (runtime.client === client) {
                    val now = SystemClock.elapsedRealtime()
                    client.sendHeartbeat(
                        sessionId = sessionId,
                        pingId = pingId++,
                        clientElapsedRealtimeMs = now,
                    )
                    delay(GUEST_HEARTBEAT_INTERVAL_MS)
                }
            }
    }

    companion object {
        internal const val HOST_BROADCAST_INTERVAL_MS = 750L
        internal const val GUEST_HEARTBEAT_INTERVAL_MS = 2_000L
    }
}
