from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {text.count(old)}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# 1) Put the settle gate at the actual network-open boundary as well as the resolve boundary.
gate = Path("app/src/main/kotlin/com/nikhil/yt/playback/PlaybackStabilityGate.kt")
old = '''    fun onSelectionChanged() {
        val now = nowMs()
        val previous = selectionChangedAtMs
        rapidSelectionStreak =
            if (hasObservedSelectionChange && now - previous in 0..RAPID_SKIP_MAX_GAP_MS) {
                rapidSelectionStreak + 1
            } else {
                1
            }
        hasObservedSelectionChange = true
        selectionChangedAtMs = now
        selectionGeneration.update { it + 1L }
    }

    /**
     * Wait until the current selection has been quiet for the requested delay.
'''
new = '''    fun onSelectionChanged() {
        val now = nowMs()
        val previous = selectionChangedAtMs
        rapidSelectionStreak =
            if (hasObservedSelectionChange && now - previous in 0..RAPID_SKIP_MAX_GAP_MS) {
                rapidSelectionStreak + 1
            } else {
                1
            }
        hasObservedSelectionChange = true
        selectionChangedAtMs = now
        selectionGeneration.update { it + 1L }
    }

    /**
     * Hold the real CDN open at the same selection boundary as the resolver.
     *
     * This matters when prefetch has already cached a signed URL: without this guard the
     * ResolvingDataSource can skip the resolver gate and OkHttp can put a request on the wire
     * for an intermediate track before Media3 cancels it. A normal transition keeps the 250 ms
     * grace window; a rapid skip burst inherits the adaptive 650 ms settle window.
     */
    suspend fun awaitNetworkOpenStable(isRelevant: suspend () -> Boolean) {
        awaitStable(
            requiredDelayMs = { PLAYBACK_RESOLVE_STABILITY_DELAY_MS },
            isRelevant = isRelevant,
        )
    }

    /**
     * Wait until the current selection has been quiet for the requested delay.
'''
replace_once(gate, old, new)


# 2) Add a pre-open hook to the real AUDIO network DataSource. It runs before upstream.open(),
# so a stale selection can be cancelled before OkHttp sends anything.
diag = Path("app/src/main/kotlin/com/nikhil/yt/playback/audio/AudioNetworkDiagnosticDataSource.kt")
old = '''internal class AudioNetworkDiagnosticDataSource(
    private val upstream: DataSource,
) : DataSource {
'''
new = '''internal class AudioNetworkDiagnosticDataSource(
    private val upstream: DataSource,
    private val beforeNetworkOpen: ((DataSpec) -> Unit)? = null,
) : DataSource {
'''
replace_once(diag, old, new)

old = '''    override fun open(dataSpec: DataSpec): Long {
        diagnosticsEnabled = GlobalLog.isEnabled
        if (!diagnosticsEnabled) return upstream.open(dataSpec)
'''
new = '''    override fun open(dataSpec: DataSpec): Long {
        // Selection settling belongs before the physical network open. In particular, a cached
        // pre-resolved URL must not bypass the rapid-skip guard and reach OkHttp before cancellation.
        beforeNetworkOpen?.invoke(dataSpec)

        diagnosticsEnabled = GlobalLog.isEnabled
        if (!diagnosticsEnabled) return upstream.open(dataSpec)
'''
replace_once(diag, old, new)

old = '''    internal class Factory(
        private val upstreamFactory: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            AudioNetworkDiagnosticDataSource(upstreamFactory.createDataSource())
    }
'''
new = '''    internal class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val beforeNetworkOpen: ((DataSpec) -> Unit)? = null,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            AudioNetworkDiagnosticDataSource(
                upstream = upstreamFactory.createDataSource(),
                beforeNetworkOpen = beforeNetworkOpen,
            )
    }
'''
replace_once(diag, old, new)


# 3) Wire MusicService's selection gate to the network-only upstream. Full cache/download hits
# never touch this callback, so offline and fully cached playback remain immediate.
service = Path("app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt")
old = '''import java.net.ConnectException
import java.net.NoRouteToHostException
'''
new = '''import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
'''
replace_once(service, old, new)

old = '''    private val audioResolveStability = PlaybackStabilityGate()

    private fun audioResolveJob(
'''
new = '''    private val audioResolveStability = PlaybackStabilityGate()

    /**
     * Final request gate for AUDIO CDN traffic.
     *
     * Resolver debounce alone is insufficient when prefetch has already cached PlaybackData:
     * ResolvingDataSource can return that URL immediately and the network upstream can open before
     * a later media-transition cancellation arrives. Blocking here means stale rapid-skip items are
     * rejected before OkHttp's upstream.open() and therefore before a request can reach YouTube.
     */
    private fun awaitAudioNetworkOpenPermit(dataSpec: androidx.media3.datasource.DataSpec) {
        val mediaId =
            dataSpec.key
                ?.let(AudioCacheIdentity::mediaId)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return

        try {
            runBlocking {
                audioResolveStability.awaitNetworkOpenStable {
                    withContext(Dispatchers.Main.immediate) {
                        mediaId == player.currentMediaItem?.mediaId ||
                            mediaId in upcomingAudioIds()
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw InterruptedIOException("Stale AUDIO CDN open suppressed before request").apply {
                initCause(cancelled)
            }
        } catch (interrupted: InterruptedException) {
            throw InterruptedIOException("AUDIO CDN open interrupted before request").apply {
                initCause(interrupted)
            }
        }
    }

    private fun audioResolveJob(
'''
replace_once(service, old, new)

old = '''        val networkUpstream =
            AudioNetworkDiagnosticDataSource.Factory(
                DefaultDataSource.Factory(this, OkHttpDataSource.Factory(audioHttpClient)),
            )
'''
new = '''        val networkUpstream =
            AudioNetworkDiagnosticDataSource.Factory(
                upstreamFactory = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(audioHttpClient)),
                beforeNetworkOpen = ::awaitAudioNetworkOpenPermit,
            )
'''
replace_once(service, old, new)


# 4) Tests explicitly cover the network-open semantics: single transitions keep 250 ms,
# rapid bursts hold 650 ms, and stale opens are rejected before the caller could proceed.
test = Path("app/src/test/kotlin/com/nikhil/yt/playback/PlaybackStabilityGateTest.kt")
insert_before = '''    @Test
    fun promotedPrefetchAdoptsShortPlaybackDelayImmediately() = runTest {
'''
addition = '''    @Test
    fun networkOpenUsesNormalSelectionGraceWindow() = runTest {
        val gate = PlaybackStabilityGate(nowMs = { currentTime })
        var openedAt: Long? = null

        gate.onSelectionChanged()
        val job = launch {
            gate.awaitNetworkOpenStable { true }
            openedAt = currentTime
        }
        runCurrent()

        advanceTimeBy(PLAYBACK_RESOLVE_STABILITY_DELAY_MS - 1)
        runCurrent()
        assertEquals(null, openedAt)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(PLAYBACK_RESOLVE_STABILITY_DELAY_MS, openedAt)
        assertTrue(job.isCompleted)
    }

    @Test
    fun rapidSkipNetworkOpenWaitsUntilFinalSelectionSettles() = runTest {
        val gate = PlaybackStabilityGate(nowMs = { currentTime })
        var openedAt: Long? = null

        gate.onSelectionChanged()
        advanceTimeBy(150)
        gate.onSelectionChanged()
        advanceTimeBy(150)
        gate.onSelectionChanged()

        val job = launch {
            gate.awaitNetworkOpenStable { true }
            openedAt = currentTime
        }
        runCurrent()

        advanceTimeBy(RAPID_SKIP_PLAYBACK_SETTLE_DELAY_MS - 1)
        runCurrent()
        assertEquals(null, openedAt)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(300L + RAPID_SKIP_PLAYBACK_SETTLE_DELAY_MS, openedAt)
        assertTrue(job.isCompleted)
    }

    @Test
    fun staleNetworkOpenIsCancelledBeforeRelease() = runTest {
        val gate = PlaybackStabilityGate(nowMs = { currentTime })
        gate.onSelectionChanged()
        var released = false

        val job = launch {
            gate.awaitNetworkOpenStable { false }
            released = true
        }
        runCurrent()
        advanceTimeBy(PLAYBACK_RESOLVE_STABILITY_DELAY_MS)
        runCurrent()

        assertTrue(job.isCancelled)
        assertFalse(released)
    }

'''
replace_once(test, insert_before, addition + insert_before)

print("step42 network-open settling patch applied")
