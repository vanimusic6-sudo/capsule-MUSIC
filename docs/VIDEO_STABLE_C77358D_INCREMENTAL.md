# Capsule VIDEO — controlled incremental changes from stable c77358d

## Evidence from user's c77358d release log (2026-10-11 17:55)

- AUDIO resolves in ~0.3–2.9 seconds in ordinary cases and receives correctly ranged HTTP 206 with request chunk `1,048,576` bytes. A few AUDIO CDN socket read/handshake timeouts are independent of VIDEO and do not establish an application bot-check issue.
- VIDEO NewPipe encountered `LOGIN_REQUIRED: "Sign in to confirm that you're not a bot"` around 17:45:22. This happens at NewPipe's **anonymous stream metadata** retrieval, *before* any media byte request. It cannot be fixed by adjusting the CDN Range size. NewPipe upstream also documents the anonymous-watch/IP issue (NewPipeExtractor issue #1521).
- The AUDIO extractor uses independently selected VISIONOS/WEB_REMIX/WEB_CREATOR clients, session config, signed cipher and optionally PoToken. Its successful operation **does not** prove that the separate anonymous NewPipe metadata endpoint will permit streaming on the same public IP.
- In the stable MusicService, after an already successful VIDEO resolve the `CAPSULE_VIDEO_CACHE_PREFIX` DataSource case unnecessarily calls `resolveMuxed()` again inside Media3's loading thread. Even with same video id this can fetch the anonymous watch page a second time.
- VIDEO's existing `CapsuleVideoChunkedDataSource` serves large files in **4 MiB bounded Range requests**, while AUDIO independently slices by 1 MiB. It is false that Capsule intentionally downloads an entire hour-long video in one request. There is a compatibility fallback for origins that ignore Range. This path is unchanged.

## Low-risk changes in the isolated branch

1. For already-resolved VIDEO, directly use `YouTubeVideoResolver.peekResolved(videoId)` when Media3 first opens the stream. Preserve one existing legacy fallback if the URL truly has expired.
2. Limit repeated NewPipe metadata on the same video to a max-two-entry, 60-second VIDEO-only inventory; expire before the nearest signed URL. The cache is explicitly invalidated when a failed video URL is evicted, including over isolated-process IPC.
3. Distinguish the literal `LOGIN_REQUIRED "not a bot"` exception from ordinary age/login-required cases, and trip only the **VIDEO** breaker rather than treating the unavailable route as a missing music clip. Do not rotate identities or disturb normal AUDIO.
4. NewPipe's initialization is lazy and sets initialized=true only on successful setup.
5. Prepare VIDEO media bytes conservatively while the existing AUDIO plays: prime at most 256 KiB of video and (for adaptive streams) 64 KiB of original VIDEO soundtrack into the **dedicated VIDEO cache**. The warmup runs on a separate short-lived HTTP pool, with a six-second request budget. On ordinary warmup errors, fall back to the exact original stable startup; on a verified 401/403/429 block, keep the AUDIO player untouched.
6. Cache-hit selection of quality now updates VIDEO's latest format index.
7. On the Immersion screen VIDEO uses the already-sampled Immersion gradient rather than hardcoded grey. Artwork is hidden when VIDEO takes the screen so reopening cannot flash the square thumbnail. A small GPU scale/reveal animates the VIDEO window; the SurfaceView stays attached so it can render frame one. The normal player reserves the VIDEO rectangle while resolving.

## Non-goals and remaining limits

- **Important: This is not an IP restriction bypass.** We can reduce redundant watch requests and react correctly to explicit blocks, but cannot promise anonymous NewPipe will always resolve without a login challenge. A separate extractor or user-authorized authentication would have separate reliability/privacy risks.
- **There remains a short potential AUDIO→VIDEO interruption** when Media3 replaces its single decoder. The warmup keeps original AUDIO playing until verified video bytes are present and makes the final switch faster, but truly gapless decoded-audio handoff requires a second player/decoder. A previous two-player experiment caused severe AUDIO regressions; do not silently restore that risky architecture in this stability-first release.
- Do not merge until a real device plays VIDEO and next AUDIO tracks correctly, checks pause/seek, 360/720 quality, first frame without thumbnail flash, Immersion theme, a 40+ minute video without whole-file request, cancellation while fetching, and explicit IP bot refusal.
- Debug/Release CI verifies code compiles and bundled regressions pass, *not* native YouTube playback on the user's network.
- The untouched stable original remains `refactor/video-sandbox-core-20261011` at `c77358da9a60f95ee2f25c3011418ce628820c44`.


## Subsequent VIDEO-window and background-only fix (October 11)

Recorded reproduction: the attached 6.7s screen recording shows the Immersion player transitioning from a full-screen album cover to an **empty grey rectangle**. The change in this branch removes a Compose graphicsLayer(scale) wrapped around PlayerView / SurfaceView, as well as the fullscreen opaque gradient mask laid on top of the native surface. An unclipped native PlayerView with a transparent shutter is layered over the existing Immersion gradient, clipped by the outer static rounded box; a small spinner indicates "waiting for first frame". The border/chrome rather than the native SurfaceView is the only animated element.

The generic Capsule player removes the same scaled SurfaceView wrapper; its border reveal stays in Compose. On HOME (MainActivity.onStop), the Activity no longer calls MusicService.suspendCapsuleVideoForAppBackground(). Merely minimizing the app must NOT change the selected VIDEO media item or start a new AUDIO resolution. Android releases/re-attaches its visible surface on re-entry. **Screen off remains an independent policy** that deliberately returns to AUDIO. Explicit task removal also remains a separate policy.

For less AUDIO silence on AUDIO->VIDEO and VIDEO->AUDIO within the stable **single-ExoPlayer** design, removed the unnecessary unconditional extra prepare() after replaceMediaItem+seekTo. Media3's prepared timeline already schedules the replacement; only prepare() if STATE_IDLE. Reduced optional VIDEO CDN first-byte warmup call timeout from six to three seconds so a slow preflight doesn't add as much delay before the original startup. The original 4MiB bounded VIDEO ranges, AUDIO requests/quality cache and NewPipe metadata cooldown logic remain unchanged.

**Important limitation:** this deliberately does NOT claim a 100ms near-gapless handoff between separate AUDIO and VIDEO soundtracks. Once ExoPlayer replaces its own current MediaItem, the old audio is no longer available. To actually wait for the first decoded frame + selected VIDEO audio track and leave a precise 100ms dip, the app would need a verified second independent playback owner. An earlier attempt introduced major playback and queue regressions. Do not silently ship another dual-player implementation to the stable baseline until a safe ownership and timeline model passes on-device transition, seek, next-track, minimization and error tests.

### Independent extraction options (research; not shipped)

- **SimpMusic Innertube-based streaming path**: attractive because native Android/Kotlin pipeline and media metadata integration, but must independently validate signed URL freshness, proof-of-origin, licenses, and actual VIDEO format extraction. Its internal API is not a stable upstream SDK and cannot blindly be transplanted.
- **yt-dlp via youtubedl-android**: independent extractor, but bundles Python/yt-dlp, incurs higher APK size/startup and update burden. Alternative yt-dlp Android libraries may add 60–80 MB, do not import without explicit proof of stability and license compatibility.
- **PipePipe's NewPipe forks / Faraday extraction**: inspect actual dependency graph and provenance. A fork that shares NewPipe's anonymous watch endpoint is not a true bypass of a YouTube IP bot check; silent cycling of forks on HTTP 429 may worsen throttling.
- **Invidious / Piped proxy instances**: independent remote infrastructure with fluctuating availability and user IP/privacy tradeoffs. Public Invidious maintainers explicitly warn against trusting unlisted instances; no random instance list or hidden proxy is installed in Capsule.
- **Official YouTube embed / authorized playback**: more stable contract in some settings, but not equivalent to extracting a signed MP4/CDN URL and embedding it within our Media3 audio/video renderer, subject to provider playback conditions.

An actual NewPipe SignInConfirmNotBotException is a server-side **anonymous-session/IP** refusal in the metadata phase, *before* 4MiB-range CDN data transfer. It is not repaired by changing the chunk size. On that signal, retain the VIDEO-only cooldown and keep normal AUDIO untouched; never churn backend identities without distinguishing permitted retries from route bans. See NewPipeExtractor #1521.
