#!/usr/bin/env python3
from pathlib import Path
import sys

CHECK_ONLY = "--check" in sys.argv


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one guarded match, found {count}")
    if not CHECK_ONLY:
        file.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "app/src/main/kotlin/com/nikhil/yt/App.kt",
    '''                    if (hasVisitorData) {
                        CapsuleInnerTubeXPlayer.prewarm()
                    }
''',
    '''                    if (hasVisitorData) {
                        CapsuleInnerTubeXPlayer.prewarm(
                            prewarmWebPoToken = startupPolicy == AudioStreamPolicy.WEB,
                        )
                    }
''',
)

replace_once(
    "app/src/main/kotlin/com/nikhil/yt/playback/audio/CapsuleInnerTubeXPlayer.kt",
    '''    suspend fun prewarm() {
        // Capture/create the bundle under the same lock as playback so a
        // preference collector cannot close the transport during extraction.
        val preparation = resolveMutex.withLock {
            if (CapsulePlaybackSafety.blockedExceptionOrNull() != null) return
            bundle().prewarm.start()
        }
        try {
            preparation.await()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            // Replacing a bundle cancels its warmup, not the application's
            // visitor-data collector that happened to be waiting for it.
            Timber.tag(TAG).d("Startup prewarm superseded by a new extraction session")
        }
    }
''',
    '''    suspend fun prewarm(prewarmWebPoToken: Boolean = false) {
        // Capture/create the bundle under the same lock as playback so a
        // preference collector cannot close the transport during extraction.
        // Start extractor warmup first; the optional visitor-bound BotGuard
        // warmup then runs concurrently outside resolveMutex.
        val (preparation, webPoTokenVisitorData) = resolveMutex.withLock {
            if (CapsulePlaybackSafety.blockedExceptionOrNull() != null) return
            val extractionBundle = bundle()
            val auth = extractionBundle.key.auth
            val hasConfiguredPoTokens =
                auth.poTokenPlayer?.trim().orEmpty().isNotBlank() &&
                    auth.poTokenGvs?.trim().orEmpty().isNotBlank()
            val visitorData =
                auth.visitorData
                    ?.trim()
                    ?.takeIf {
                        prewarmWebPoToken &&
                            !hasConfiguredPoTokens &&
                            it.isNotBlank() &&
                            it != "null"
                    }
            extractionBundle.prewarm.start() to visitorData
        }

        // This prepares only the reusable WebView/BotGuard session. It does not
        // issue a YouTube /player request, rotate clients, or bypass the global
        // anti-bot/rate-limit breaker. Playback shares the same PoToken mutex,
        // so an early first track joins this work instead of creating another session.
        webPoTokenVisitorData?.let { visitorData ->
            poTokenGenerator.prewarm(visitorData)
        }

        try {
            preparation.await()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            // Replacing a bundle cancels its warmup, not the application's
            // visitor-data collector that happened to be waiting for it.
            Timber.tag(TAG).d("Startup prewarm superseded by a new extraction session")
        }
    }
''',
)

print("step37 guarded replacements verified" if CHECK_ONLY else "step37 patch applied")
