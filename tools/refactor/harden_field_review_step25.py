#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

MODEL = Path('innertube/src/main/kotlin/com/nikhil/yt/innertube/models/MusicResponsiveHeaderRenderer.kt')
PLAYER_TEST = Path('innertube/src/test/kotlin/com/nikhil/yt/innertube/PlayerCompatibilityTest.kt')
PREWARM = Path('app/src/main/kotlin/com/nikhil/yt/playback/audio/SharedPrewarm.kt')
PREWARM_TEST = Path('app/src/test/kotlin/com/nikhil/yt/playback/audio/SharedPrewarmTest.kt')
LOGIN = Path('app/src/main/kotlin/com/nikhil/yt/ui/screens/LoginScreen.kt')
SERVICE = Path('app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt')
INNER = Path('app/src/main/kotlin/com/nikhil/yt/playback/audio/CapsuleInnerTubeXPlayer.kt')

MODEL_OLD = '    val buttons: List<Button>,\n'
MODEL_NEW = '    val buttons: List<Button> = emptyList(),\n'

PLAYER_IMPORT_ANCHOR = 'import com.nikhil.yt.innertube.models.YouTubeLocale\n'
PLAYER_IMPORT_NEW = PLAYER_IMPORT_ANCHOR + 'import com.nikhil.yt.innertube.models.MusicResponsiveHeaderRenderer\n'
PLAYER_TEST_MARKER = '    fun responsiveHeaderToleratesMissingButtons()'
PLAYER_TEST_APPEND = '''
    @Test
    fun responsiveHeaderToleratesMissingButtons() {
        val header =
            Json.decodeFromString<MusicResponsiveHeaderRenderer>(
                """
                {
                  "thumbnail": null,
                  "title": { "runs": [] },
                  "subtitle": { "runs": [] },
                  "secondSubtitle": null,
                  "straplineTextOne": null
                }
                """.trimIndent(),
            )

        assertTrue(header.buttons.isEmpty())
    }
'''

PREWARM_IMPORT_OLD = 'import kotlinx.coroutines.CoroutineScope\n'
PREWARM_IMPORT_NEW = PREWARM_IMPORT_OLD + 'import kotlinx.coroutines.CoroutineStart\n'
PREWARM_TIMEOUT_OLD = '    private val timeoutMs: Long = 8_000L,\n'
PREWARM_TIMEOUT_NEW = '    private val timeoutMs: Long = 15_000L,\n'
PREWARM_FIELD_OLD = '    private var job: Deferred<Result<Unit>>? = null\n\n'
PREWARM_FIELD_NEW = '    private var job: Deferred<Result<Unit>>? = null\n    private var generation: Long = 0L\n\n'
PREWARM_START_OLD = '''    @Synchronized
    fun start(): Deferred<Result<Unit>> =
        job ?: scope.async {
            try {
                val completed = withTimeoutOrNull(timeoutMs) { prepare(); true } ?: false
                if (completed) Result.success(Unit)
                else Result.failure(SocketTimeoutException("Prewarm exceeded $timeoutMs ms"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
        }.also { job = it }
'''
PREWARM_START_NEW = '''    @Synchronized
    fun start(): Deferred<Result<Unit>> {
        job?.let { return it }

        val attemptGeneration = ++generation
        val created =
            scope.async(start = CoroutineStart.LAZY) {
                val result =
                    try {
                        val completed = withTimeoutOrNull(timeoutMs) { prepare(); true } ?: false
                        if (completed) Result.success(Unit)
                        else Result.failure(SocketTimeoutException("Prewarm exceeded $timeoutMs ms"))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Result.failure(failure)
                    }

                if (result.isFailure) {
                    synchronized(this@SharedPrewarm) {
                        if (generation == attemptGeneration) {
                            job = null
                        }
                    }
                }
                result
            }

        job = created
        created.start()
        return created
    }
'''

PREWARM_TEST_OLD = '''    @Test
    fun timeoutIsBoundedAndDoesNotStartAnotherWarmupForEveryTrack() = runTest {
        var initializations = 0
        val warmup = SharedPrewarm(backgroundScope, timeoutMs = 800) {
            initializations++
            awaitCancellation()
        }
        assertTrue(warmup.start().await().exceptionOrNull() is SocketTimeoutException)
        assertEquals(800L, currentTime)
        assertTrue(warmup.start().await().isFailure)
        assertEquals(1, initializations)
    }
'''
PREWARM_TEST_NEW = '''    @Test
    fun timeoutIsBoundedAndNextRequestCanRetryWarmup() = runTest {
        var initializations = 0
        val warmup = SharedPrewarm(backgroundScope, timeoutMs = 800) {
            initializations++
            awaitCancellation()
        }
        val first = warmup.start()
        assertTrue(first.await().exceptionOrNull() is SocketTimeoutException)
        assertEquals(800L, currentTime)

        val second = warmup.start()
        assertFalse(first === second)
        assertTrue(second.await().exceptionOrNull() is SocketTimeoutException)
        assertEquals(1_600L, currentTime)
        assertEquals(2, initializations)
    }

    @Test
    fun ordinaryFailureIsNotCachedAfterItCompletes() = runTest {
        var initializations = 0
        val warmup = SharedPrewarm(backgroundScope) {
            initializations++
            if (initializations == 1) error("first attempt failed")
        }

        assertTrue(warmup.start().await().isFailure)
        assertTrue(warmup.start().await().isSuccess)
        assertEquals(2, initializations)
    }
'''

LOGIN_IMPORT_ANCHOR = 'import androidx.navigation.NavController\n'
LOGIN_IMPORT_NEW = LOGIN_IMPORT_ANCHOR + 'import androidx.datastore.preferences.core.edit\n'
LOGIN_COROUTINE_IMPORT = 'import kotlinx.coroutines.launch\n'
LOGIN_COROUTINE_NEW = LOGIN_COROUTINE_IMPORT + 'import kotlinx.coroutines.flow.first\nimport kotlinx.coroutines.withTimeoutOrNull\n'
LOGIN_UTIL_IMPORT = 'import com.nikhil.yt.utils.dataStore\n'
LOGIN_OLD = '''                        if (url?.startsWith("https://music.youtube.com") == true) {
                            innerTubeCookie = CookieManager.getInstance().getCookie(url)
                            coroutineScope.launch {
                                YouTube.accountInfo().onSuccess {
                                    accountName = it.name
                                    accountEmail = it.email.orEmpty()
                                    accountChannelHandle = it.channelHandle.orEmpty()
                                    if (navController.currentBackStackEntry?.destination?.route == "login") {
                                        navController.navigateUp()
                                    }
                                }.onFailure {
                                    reportException(it)
                                    if (navController.currentBackStackEntry?.destination?.route == "login") {
                                        navController.navigateUp()
                                    }
                                }
                            }
                        }
'''
LOGIN_NEW = '''                        if (url?.startsWith("https://music.youtube.com") == true) {
                            val loginCookie = CookieManager.getInstance().getCookie(url).orEmpty()
                            if (loginCookie.isBlank()) return

                            coroutineScope.launch {
                                context.dataStore.edit { settings ->
                                    settings[InnerTubeCookieKey] = loginCookie
                                }

                                val published =
                                    withTimeoutOrNull(5_000L) {
                                        YouTube.authStates.first { state ->
                                            state.cookie == loginCookie && state.hasLoginCookie
                                        }
                                    } != null

                                // DataStore is the source of truth. This fallback only covers an
                                // unexpectedly stalled application collector after the edit has
                                // already committed the same cookie to disk.
                                if (!published) {
                                    YouTube.cookie = loginCookie
                                }

                                YouTube.accountInfo().onSuccess {
                                    accountName = it.name
                                    accountEmail = it.email.orEmpty()
                                    accountChannelHandle = it.channelHandle.orEmpty()
                                    if (navController.currentBackStackEntry?.destination?.route == "login") {
                                        navController.navigateUp()
                                    }
                                }.onFailure {
                                    reportException(it)
                                    if (navController.currentBackStackEntry?.destination?.route == "login") {
                                        navController.navigateUp()
                                    }
                                }
                            }
                        }
'''

SERVICE_OLD = '''        dataStore.data
            .map { prefs ->
                Pair(
                    prefs[AudioStreamPolicyKey].toEnum(AudioStreamPolicy.VISIONOS).normalizedForPlayback(),
                    prefs[AudioQualityKey].toEnum(AudioQuality.AUTO),
                )
            }
            .distinctUntilChanged()
            .combine(YouTube.authStates) { selection, auth -> Triple(selection.first, selection.second, auth) }
            .collect(scope) { (policy, quality, _) ->
                audioStreamPolicy = policy
                audioQuality = quality
                reloadAudioForClientChange(policy)
            }
'''
SERVICE_NEW = '''        dataStore.data
            .map { prefs ->
                Pair(
                    prefs[AudioStreamPolicyKey].toEnum(AudioStreamPolicy.VISIONOS).normalizedForPlayback(),
                    prefs[AudioQualityKey].toEnum(AudioQuality.AUTO),
                )
            }
            .distinctUntilChanged()
            .collect(scope) { (policy, quality) ->
                if (policy != audioStreamPolicy || quality != audioQuality) {
                    audioStreamPolicy = policy
                    audioQuality = quality
                    reloadAudioForClientChange(policy)
                }
            }
'''

INNER_PREWARM_OLD = '''                                Timber.tag(TAG).i(
                                    "Web prewarm awaited id=%s selectedProfile=%s reused=%s ok=%s waitedMs=%d",
                                    videoId,
                                    playbackClientOverrideId,
                                    reused,
                                    warmed.isSuccess,
                                    (System.nanoTime() - waitStartedAt) / 1_000_000L,
                                )
'''
INNER_PREWARM_NEW = '''                                Timber.tag(TAG).i(
                                    "Web prewarm awaited id=%s priority=%s selectedProfile=%s reused=%s ok=%s waitedMs=%d",
                                    videoId,
                                    priority,
                                    playbackClientOverrideId,
                                    reused,
                                    warmed.isSuccess,
                                    (System.nanoTime() - waitStartedAt) / 1_000_000L,
                                )
'''
INNER_RESOLVE_OLD = '''                            Timber.tag(TAG).i(
                                "Resolving audio id=%s selectedProfile=%s",
                                videoId,
                                playbackClientOverrideId,
                            )
'''
INNER_RESOLVE_NEW = '''                            Timber.tag(TAG).i(
                                "Resolving audio id=%s priority=%s selectedProfile=%s",
                                videoId,
                                priority,
                                playbackClientOverrideId,
                            )
'''
INNER_TIMEOUT_OLD = '''            Timber.tag(TAG).w(
                timeout,
                "engine resolve timeout id=%s budgetMs=%d",
                videoId,
                ENGINE_RESOLVE_TIMEOUT_MS,
            )
'''
INNER_TIMEOUT_NEW = '''            Timber.tag(TAG).w(
                timeout,
                "engine resolve timeout id=%s priority=%s budgetMs=%d",
                videoId,
                priority,
                ENGINE_RESOLVE_TIMEOUT_MS,
            )
'''


def require_count(source: str, needle: str, count: int, label: str) -> None:
    actual = source.count(needle)
    if actual != count:
        raise SystemExit(f'{label}: expected {count} occurrences, found {actual}')


def append_before_final_brace(source: str, block: str) -> str:
    pos = source.rfind('\n}')
    if pos < 0:
        raise SystemExit('Could not locate final class brace')
    return source[:pos] + '\n' + block.rstrip() + source[pos:]


def load() -> dict[Path, str]:
    return {path: path.read_text() for path in [MODEL, PLAYER_TEST, PREWARM, PREWARM_TEST, LOGIN, SERVICE, INNER]}


def validate_before(files: dict[Path, str]) -> None:
    require_count(files[MODEL], MODEL_OLD, 1, 'required buttons field')
    require_count(files[PLAYER_TEST], PLAYER_IMPORT_ANCHOR, 1, 'player test import anchor')
    if PLAYER_TEST_MARKER in files[PLAYER_TEST]:
        raise SystemExit('responsive-header regression test already present')

    require_count(files[PREWARM], PREWARM_IMPORT_OLD, 1, 'prewarm import')
    require_count(files[PREWARM], PREWARM_TIMEOUT_OLD, 1, 'prewarm timeout')
    require_count(files[PREWARM], PREWARM_FIELD_OLD, 1, 'prewarm field')
    require_count(files[PREWARM], PREWARM_START_OLD, 1, 'prewarm start implementation')
    require_count(files[PREWARM_TEST], PREWARM_TEST_OLD, 1, 'old failed-prewarm contract test')

    require_count(files[LOGIN], LOGIN_IMPORT_ANCHOR, 1, 'login import anchor')
    require_count(files[LOGIN], LOGIN_COROUTINE_IMPORT, 1, 'login coroutine import')
    require_count(files[LOGIN], LOGIN_UTIL_IMPORT, 1, 'login dataStore import')
    require_count(files[LOGIN], LOGIN_OLD, 1, 'login race block')

    require_count(files[SERVICE], SERVICE_OLD, 1, 'auth-triggered audio reload collector')
    require_count(files[INNER], INNER_PREWARM_OLD, 1, 'InnerTubeX prewarm log')
    require_count(files[INNER], INNER_RESOLVE_OLD, 1, 'InnerTubeX resolve log')
    require_count(files[INNER], INNER_TIMEOUT_OLD, 1, 'InnerTubeX timeout log')


def transform(files: dict[Path, str]) -> dict[Path, str]:
    files[MODEL] = files[MODEL].replace(MODEL_OLD, MODEL_NEW, 1)

    player_test = files[PLAYER_TEST].replace(PLAYER_IMPORT_ANCHOR, PLAYER_IMPORT_NEW, 1)
    files[PLAYER_TEST] = append_before_final_brace(player_test, PLAYER_TEST_APPEND)

    prewarm = files[PREWARM]
    prewarm = prewarm.replace(PREWARM_IMPORT_OLD, PREWARM_IMPORT_NEW, 1)
    prewarm = prewarm.replace(PREWARM_TIMEOUT_OLD, PREWARM_TIMEOUT_NEW, 1)
    prewarm = prewarm.replace(PREWARM_FIELD_OLD, PREWARM_FIELD_NEW, 1)
    prewarm = prewarm.replace(PREWARM_START_OLD, PREWARM_START_NEW, 1)
    files[PREWARM] = prewarm
    files[PREWARM_TEST] = files[PREWARM_TEST].replace(PREWARM_TEST_OLD, PREWARM_TEST_NEW, 1)

    login = files[LOGIN]
    login = login.replace(LOGIN_IMPORT_ANCHOR, LOGIN_IMPORT_NEW, 1)
    login = login.replace(LOGIN_COROUTINE_IMPORT, LOGIN_COROUTINE_NEW, 1)
    login = login.replace(LOGIN_OLD, LOGIN_NEW, 1)
    files[LOGIN] = login

    files[SERVICE] = files[SERVICE].replace(SERVICE_OLD, SERVICE_NEW, 1)

    inner = files[INNER]
    inner = inner.replace(INNER_PREWARM_OLD, INNER_PREWARM_NEW, 1)
    inner = inner.replace(INNER_RESOLVE_OLD, INNER_RESOLVE_NEW, 1)
    inner = inner.replace(INNER_TIMEOUT_OLD, INNER_TIMEOUT_NEW, 1)
    files[INNER] = inner
    return files


def validate_after(files: dict[Path, str]) -> None:
    required = {
        MODEL: ['val buttons: List<Button> = emptyList()'],
        PLAYER_TEST: [PLAYER_TEST_MARKER, 'assertTrue(header.buttons.isEmpty())'],
        PREWARM: ['private val timeoutMs: Long = 15_000L', 'CoroutineStart.LAZY', 'if (result.isFailure)', 'job = null'],
        PREWARM_TEST: ['timeoutIsBoundedAndNextRequestCanRetryWarmup', 'ordinaryFailureIsNotCachedAfterItCompletes'],
        LOGIN: ['settings[InnerTubeCookieKey] = loginCookie', 'YouTube.authStates.first', 'state.cookie == loginCookie && state.hasLoginCookie'],
        SERVICE: ['.collect(scope) { (policy, quality) ->', 'if (policy != audioStreamPolicy || quality != audioQuality)'],
        INNER: ['priority=%s selectedProfile=%s', '"Resolving audio id=%s priority=%s selectedProfile=%s"', '"engine resolve timeout id=%s priority=%s budgetMs=%d"'],
    }
    for path, markers in required.items():
        for marker in markers:
            if marker not in files[path]:
                raise SystemExit(f'missing transformed marker in {path}: {marker}')

    if '.combine(YouTube.authStates)' in files[SERVICE]:
        raise SystemExit('auth changes still trigger audio reload')
    if 'timeoutIsBoundedAndDoesNotStartAnotherWarmupForEveryTrack' in files[PREWARM_TEST]:
        raise SystemExit('old failed-prewarm contract test remains')
    if MODEL_OLD in files[MODEL]:
        raise SystemExit('buttons is still required')


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()

    files = load()
    validate_before(files)
    if args.check:
        print('Field-review Step 25 preconditions satisfied')
        return

    files = transform(files)
    validate_after(files)
    for path, source in files.items():
        path.write_text(source)
    print('Applied field-review fixes for sync, prewarm, profile reloads and resolver provenance')


if __name__ == '__main__':
    main()
