# Capsule playback: measured bottlenecks and prepared changes — 2026-11-02

Base: cc4d35b3ae39d0bbcf8b070acd7127790072f6ef.
Working branch: fix/playback-fast-recovery-20261102.

## Evidence from the supplied 16:55:57 log

- New audio WPFkE9KvVJw: transition buffering 5,871 ms; resolution 3,107 ms, including watch config 834 ms and cold cipher processing 1,940 ms. After the first CDN byte it reached READY in about 192 ms.
- Warm audio resolution tjlnUlPLqx8 took 385 ms, but its CDN open took 4,101 ms (first byte 4,152 ms). The original host was queried twice before following a healthy 302.
- Five anonymous video routes all returned BOT_BLOCKED. Their logged attempt times total 10,807 ms; matching took another 829 ms. The old global guard then suppressed VIDEO for 30 minutes.
- Cached audio BX0lKSa_PTk reached READY in 167 ms. This supports addressing extraction and network admission before decoder replacement.
- HTTP/2 negotiation is absent in this capture; merely setting the existing OkHttp protocol list again cannot prove it will change.

## Final implementation

1. VIDEO first uses the maintained InnerTubeX WEB_REMIX client with the existing compatible PO-token provider, established visitor, cipher and player config. The new adapter validates direct video/audio URLs, cap, expiry, and carries required User-Agent/Origin/Referer to VIDEO transport. Account headers are excluded from CDN headers.
2. VIDEO extraction is bounded to 7 seconds including queueing, and scheduled below foreground AUDIO. It cannot evict the AUDIO URL cache or record video failures in the per-track AUDIO client-failure cache. The dedicated video source/cache/transport and isolated parser fallback remain.
3. The installed provider advertises WEB_BOTGUARD, whereas the pinned Web SABR manifests advertise different accepted providers. Therefore WEB_REMIX direct is the active primary. No fake capability declaration, forced token reuse, or unconditional SABR switch. The adapter can validate complete future SABR contracts and carries exact xtags, PO-token and Origin to UMP.
4. Startup begins the existing extractor/config/cipher prewarm as well as the token minter. The preparation owns its lifetime independently of an individual loading track; no loop or continuous background timer was added.
5. Healthy CDN redirects are followed immediately, with successful destinations remembered. The single original-host reissue is retained only for a destination already observed timing out on this network. Existing bounded slices, read recovery and rate-limit handling remain.
6. Foreground CDN opens add no extra 90 ms delay after selection stability. Rapid-skip/nonplaying cancellation windows remain.
7. A verified track-to-clip match is cached before stream extraction, so a failed stream does not cause another search on retry. Anonymous roster bot verdicts remain route-local and no longer prohibit the new Web route. Migration clears only the legacy anonymous-roster reason; explicit metadata bot-check and HTTP 429 restrictions remain.

## Validation status

PASSED: git diff --check; six Python app-identity/security helper tests; repository targeted integrity gate; source audit reported zero blockers. These checks do not validate playback.

ADDED/UPDATED, NOT EXECUTED: Kotlin regression tests for healthy/known-failed redirect destinations, exact signed URL/header fidelity, no foreground settle delay, legacy guard migration, token-backed direct video adaptation, SABR MP4 quality selection, tagged audio identity, token/origin IPC preservation, unsafe endpoint/container/origin rejection. CI configuration includes playback/video suites, extractor/betterlyrics tests, lint, Universal Debug, DEV and TEST APK builds.

BLOCKED: Gradle wrapper attempted services.gradle.org but failed with Network is unreachable; no local Gradle/Android SDK toolchain is available. APK compilation, Kotlin tests, lint, device playback, first-frame timings, underrun and thermal validation have not run. No claim of YTM-equivalent speed or proven stability.

GitHub push/CI was denied by automatic approval review, which requires explicit user-authored permission to publish this branch to vanimusic6-sudo/capsule-MUSIC. No alternate publication path was used. No merge or official release is part of this change.

## Next checks after permission

Push the prepared work branch, wait for actual green CI, fix failures, download the DEV/TEST APK artifacts. On the device test a cold first track, warm next track, first and repeated video handoff, seek after first frame, fast skipping and a network change. Compare tap-to-audio and request-to-first-frame measurements in new logs; do not infer performance from build success.

## Sources inspected

- Local source at the exact base above and the supplied log.
- MetrolistGroup/innertubex v0.11.2 source: extraction/InnerTubeExtractor.kt, extraction/PlayerClientDirector.kt, strategy/PlaybackClientCatalog.kt, sabr/SabrModels.kt. Verified the actual pinned implementation and provider contracts.
- https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide — token binding and provider context.
- https://developer.android.com/reference/androidx/media3/exoplayer/LoadControl — buffering responsibilities.
