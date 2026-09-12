from pathlib import Path


scheduler_path = Path("app/src/main/kotlin/com/nikhil/yt/playback/audio/AudioResolveScheduler.kt")
scheduler = scheduler_path.read_text()

old = """    private val waiting = mutableListOf<Ticket>()
    private var active: Ticket? = null
    private var nextDownloadStartMs: Long? = null

    fun promote(mediaId: String) = synchronized(lock) {
        val nowMs = monotonicNowMs()
        (waiting + listOfNotNull(active)).filter {
            it.mediaId == mediaId && it.priority == AudioResolvePriority.PREFETCH
        }.forEach { ticket ->
"""
new = """    private val waiting = mutableListOf<Ticket>()
    private var active: Ticket? = null
    private var nextDownloadStartMs: Long? = null
    private val pendingPlaybackPromotions = mutableMapOf<String, Long>()

    fun promote(mediaId: String) = synchronized(lock) {
        val nowMs = monotonicNowMs()
        val matches = (waiting + listOfNotNull(active)).filter {
            it.mediaId == mediaId && it.priority == AudioResolvePriority.PREFETCH
        }
        if (matches.isEmpty()) {
            // Media3 can ask ResolvingDataSource for a track before the shared
            // prefetch ticket exists. Remember that foreground demand briefly
            // so the next PREFETCH-labelled resolve actually starts as PLAYBACK.
            pendingPlaybackPromotions[mediaId] = nowMs
        }
        matches.forEach { ticket ->
"""
if old not in scheduler:
    raise SystemExit("AudioResolveScheduler promote block not found")
scheduler = scheduler.replace(old, new, 1)

old = """        preemptBackground()
    }

    suspend fun <T> run(mediaId: String, priority: AudioResolvePriority, block: suspend () -> T): T {
        var effectivePriority = priority
"""
new = """        preemptBackground()
    }

    /** Current scheduler priority, including a loader promotion that arrived mid-extraction. */
    fun effectivePriority(
        mediaId: String,
        fallback: AudioResolvePriority,
    ): AudioResolvePriority =
        synchronized(lock) {
            val ticketPriority =
                (waiting + listOfNotNull(active))
                    .asSequence()
                    .filter { it.mediaId == mediaId }
                    .minByOrNull { it.priority.schedulingRank }
                    ?.priority
            if (ticketPriority != null) return@synchronized ticketPriority

            val promotedAt = pendingPlaybackPromotions[mediaId]
            if (
                fallback == AudioResolvePriority.PREFETCH &&
                promotedAt != null &&
                monotonicNowMs() - promotedAt in 0 until PENDING_PLAYBACK_PROMOTION_TTL_MS
            ) {
                AudioResolvePriority.PLAYBACK
            } else {
                fallback
            }
        }

    suspend fun <T> run(mediaId: String, priority: AudioResolvePriority, block: suspend () -> T): T {
        var effectivePriority =
            synchronized(lock) {
                val promotedAt = pendingPlaybackPromotions.remove(mediaId)
                if (
                    priority == AudioResolvePriority.PREFETCH &&
                    promotedAt != null &&
                    monotonicNowMs() - promotedAt in 0 until PENDING_PLAYBACK_PROMOTION_TTL_MS
                ) {
                    AudioResolvePriority.PLAYBACK
                } else {
                    priority
                }
            }
"""
if old not in scheduler:
    raise SystemExit("AudioResolveScheduler run block not found")
scheduler = scheduler.replace(old, new, 1)

old = """    private companion object {
        const val PROMOTED_PREFETCH_RESTART_AFTER_MS = 4_000L
    }
"""
new = """    private companion object {
        const val PROMOTED_PREFETCH_RESTART_AFTER_MS = 4_000L
        const val PENDING_PLAYBACK_PROMOTION_TTL_MS = 10_000L
    }
"""
if old not in scheduler:
    raise SystemExit("AudioResolveScheduler companion block not found")
scheduler = scheduler.replace(old, new, 1)
scheduler_path.write_text(scheduler)


player_path = Path("app/src/main/kotlin/com/nikhil/yt/playback/audio/CapsuleInnerTubeXPlayer.kt")
player = player_path.read_text()

old_run = """            val resolvedQuality = audioQuality.toInnerTubeX(connectivityManager)
            val stream =
                scheduler.run(videoId, priority) {
                    resolveMutex.withLock {
                        withTimeout(ENGINE_RESOLVE_TIMEOUT_MS) {
                            CapsulePlaybackSafety.blockedExceptionOrNull()?.let { throw it }
                            val extractionBundle = bundle()
                            Timber.tag(TAG).i(
                                \"Resolving audio id=%s priority=%s primaryProfile=%s\",
                                videoId,
                                priority,
                                primaryProfileId,
                            )
                            resolveWithSafeClientFallbacks(
                                extractionBundle = extractionBundle,
                                videoId = videoId,
                                baseHints = baseHints,
                                audioQuality = resolvedQuality,
                                primaryProfileId = primaryProfileId,
                                preferredProfiles = clientOrder,
                                priority = priority,
                            )
                        }
                    }
                }
"""
new_run = """            val resolvedQuality = audioQuality.toInnerTubeX(connectivityManager)
            val effectivePriority = { scheduler.effectivePriority(videoId, priority) }
            val stream =
                scheduler.run(videoId, priority) {
                    resolveMutex.withLock {
                        withTimeout(ENGINE_RESOLVE_TIMEOUT_MS) {
                            CapsulePlaybackSafety.blockedExceptionOrNull()?.let { throw it }
                            val extractionBundle = bundle()
                            Timber.tag(TAG).i(
                                \"Resolving audio id=%s priority=%s primaryProfile=%s\",
                                videoId,
                                effectivePriority(),
                                primaryProfileId,
                            )
                            resolveWithSafeClientFallbacks(
                                extractionBundle = extractionBundle,
                                videoId = videoId,
                                baseHints = baseHints,
                                audioQuality = resolvedQuality,
                                primaryProfileId = primaryProfileId,
                                preferredProfiles = clientOrder,
                                priorityProvider = effectivePriority,
                            )
                        }
                    }
                }
"""
if old_run not in player:
    raise SystemExit("playerResponseForPlayback scheduler block not found")
player = player.replace(old_run, new_run, 1)

start = player.index("    private suspend fun resolveWithSafeClientFallbacks(")
end = player.index("    private suspend fun extractDirectStream(", start)
replacement = '''    private suspend fun resolveWithSafeClientFallbacks(
        extractionBundle: ExtractionBundle,
        videoId: String,
        baseHints: ContentHints,
        audioQuality: InnerTubeXAudioQuality,
        primaryProfileId: String,
        preferredProfiles: List<String>,
        priorityProvider: () -> AudioResolvePriority,
    ): ExtractedStream {
        var lastFailure: Exception? = null
        var botSignalAlreadySeen = CapsulePlaybackSafety.quarantinedProfileIds().isNotEmpty()
        var onlyPostBotProfile: String? = null
        val attempted = linkedSetOf<String>()

        while (true) {
            val priorityBeforeAttempt = priorityProvider()
            val excludedBeforeAttempt =
                CapsulePlaybackSafety.quarantinedProfileIds() + failedStreamClients(videoId)
            val plan =
                CapsuleAudioFallbackPolicy.profilePlan(
                    primaryProfileId = primaryProfileId,
                    priority = priorityBeforeAttempt,
                    authenticated = extractionBundle.innerTube.hasSapCookieAuth(),
                    isUploaded = baseHints.isUploaded == true,
                    excludedProfiles = excludedBeforeAttempt,
                    preferredProfiles = preferredProfiles,
                )

            val profileId =
                onlyPostBotProfile
                    ?.takeIf { it !in attempted && it in plan }
                    ?: plan.firstOrNull { it !in attempted }
                    ?: throw lastFailure
                    ?: IllegalStateException(
                        if (priorityBeforeAttempt == AudioResolvePriority.PLAYBACK) {
                            "No safe AUDIO client profile is currently available"
                        } else {
                            "AUDIO background resolve suppressed while its primary profile is quarantined"
                        },
                    )

            attempted += profileId
            CapsulePlaybackSafety.blockedExceptionOrNull()?.let { throw it }

            val wireGeneration = CapsulePlaybackSafety.wireBotSignalGeneration()
            Timber.tag(TAG).i(
                "AUDIO profile attempt id=%s priority=%s profile=%s",
                videoId,
                priorityBeforeAttempt,
                profileId,
            )

            try {
                val stream =
                    extractDirectStream(
                        extractionBundle = extractionBundle,
                        videoId = videoId,
                        hints = baseHints.copy(playbackClientOverrideId = profileId),
                        audioQuality = audioQuality,
                    )
                if (profileId != primaryProfileId) {
                    Timber.tag(TAG).i(
                        "AUDIO fallback recovered id=%s primary=%s selected=%s",
                        videoId,
                        primaryProfileId,
                        profileId,
                    )
                }
                return stream
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val kind =
                    CapsulePlaybackSafety.classifyFailureSince(
                        error = failure,
                        wireGenerationBeforeAttempt = wireGeneration,
                    )
                val priorityAfterFailure = priorityProvider()

                if (kind == YouTubeFailureKind.RATE_LIMITED) {
                    CapsulePlaybackSafety.observeFailure(failure)
                    throw failure
                }

                if (kind == YouTubeFailureKind.BOT_CHECK) {
                    val foregroundPlan =
                        CapsuleAudioFallbackPolicy.profilePlan(
                            primaryProfileId = primaryProfileId,
                            priority = AudioResolvePriority.PLAYBACK,
                            authenticated = extractionBundle.innerTube.hasSapCookieAuth(),
                            isUploaded = baseHints.isUploaded == true,
                            excludedProfiles = excludedBeforeAttempt,
                            preferredProfiles = preferredProfiles,
                        )

                    CapsulePlaybackSafety.markProfileBotCheck(profileId)
                    markStreamClientFailed(videoId, profileId)
                    Timber.tag(TAG).w(
                        "AUDIO bot-check isolated id=%s priority=%s profile=%s",
                        videoId,
                        priorityAfterFailure,
                        profileId,
                    )

                    if (priorityAfterFailure != AudioResolvePriority.PLAYBACK) throw failure

                    if (botSignalAlreadySeen) {
                        CapsulePlaybackSafety.markBotDetectionFailure(
                            "confirmed across multiple AUDIO client profiles",
                        )
                        throw failure
                    }

                    val crossFamily =
                        CapsuleAudioFallbackPolicy.crossFamilyFallback(foregroundPlan, profileId)
                            ?: throw failure
                    botSignalAlreadySeen = true
                    onlyPostBotProfile = crossFamily
                    lastFailure = failure
                    delay(CapsuleAudioFallbackPolicy.fallbackDelayMs(kind))
                    continue
                }

                val canFallback = CapsuleAudioFallbackPolicy.canFallbackAfter(kind)
                if (canFallback) {
                    markStreamClientFailed(videoId, profileId)
                }

                if (
                    onlyPostBotProfile != null ||
                    !canFallback ||
                    priorityAfterFailure != AudioResolvePriority.PLAYBACK
                ) {
                    throw failure
                }

                val fallbackDelayMs = CapsuleAudioFallbackPolicy.fallbackDelayMs(kind)
                Timber.tag(TAG).w(
                    "AUDIO client-local failure id=%s profile=%s kind=%s; fallbackDelayMs=%d",
                    videoId,
                    profileId,
                    kind,
                    fallbackDelayMs,
                )
                lastFailure = failure
                if (fallbackDelayMs > 0L) delay(fallbackDelayMs)
            }
        }
    }

'''
player = player[:start] + replacement + player[end:]
player_path.write_text(player)


service_path = Path("app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt")
service = service_path.read_text()
old_mapping = '''                        is TimeoutCancellationException,
                        is java.net.SocketTimeoutException,
                        -> {
                            throw PlaybackException(
                                getString(R.string.error_timeout),
                                throwable,
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                            )
                        }

                        else -> throw PlaybackException(
'''
new_mapping = '''                        is TimeoutCancellationException,
                        is java.net.SocketTimeoutException,
                        -> {
                            throw PlaybackException(
                                getString(R.string.error_timeout),
                                throwable,
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                            )
                        }

                        // A stale prefetch/selection cancellation is control flow,
                        // not a playback failure. Do not turn it into error_unknown.
                        is kotlinx.coroutines.CancellationException -> throw throwable

                        else -> throw PlaybackException(
'''
if old_mapping not in service:
    raise SystemExit("MusicService resolve failure mapping not found")
service = service.replace(old_mapping, new_mapping, 1)
service_path.write_text(service)


test_path = Path("app/src/test/kotlin/com/nikhil/yt/playback/audio/AudioResolveSchedulerTest.kt")
tests = test_path.read_text()
insertion = r'''

    @Test fun loaderPromotionBeforeTicketPromotesTheNextPrefetchRun() = runTest {
        val scheduler = AudioResolveScheduler(monotonicNowMs = { testScheduler.currentTime })
        scheduler.promote("next")

        var observed = AudioResolvePriority.PREFETCH
        val value =
            scheduler.run("next", AudioResolvePriority.PREFETCH) {
                observed = scheduler.effectivePriority("next", AudioResolvePriority.PREFETCH)
                7
            }

        assertEquals(7, value)
        assertEquals(AudioResolvePriority.PLAYBACK, observed)
    }

    @Test fun loaderPromotionIsVisibleInsideYoungSharedPrefetch() = runTest {
        val scheduler = AudioResolveScheduler(monotonicNowMs = { testScheduler.currentTime })
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val observed = mutableListOf<AudioResolvePriority>()

        val work = async {
            scheduler.run("next", AudioResolvePriority.PREFETCH) {
                observed += scheduler.effectivePriority("next", AudioResolvePriority.PREFETCH)
                started.complete(Unit)
                release.await()
                observed += scheduler.effectivePriority("next", AudioResolvePriority.PREFETCH)
            }
        }

        started.await()
        scheduler.promote("next")
        release.complete(Unit)
        work.await()

        assertEquals(
            listOf(AudioResolvePriority.PREFETCH, AudioResolvePriority.PLAYBACK),
            observed,
        )
    }
'''
pos = tests.rfind("\n}\n")
if pos < 0:
    raise SystemExit("AudioResolveSchedulerTest class end not found")
tests = tests[:pos] + insertion + tests[pos:]
test_path.write_text(tests)
