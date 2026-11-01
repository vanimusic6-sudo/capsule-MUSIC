# Playback architecture

This is the active playback contract, not a proposal for a global rewrite. `MusicService` remains the platform/session owner. Focused helpers isolate extraction, scheduling, failure state, caches and video IPC without changing the working player lifecycle.

## Ownership

| Layer | Owner | Responsibility |
| --- | --- | --- |
| Queue, position, focus, media session | `app/playback/MusicService.kt` and its coordinators | Publish only relevant results; own player changes and bounded source recovery |
| Audio facade | `app/playback/audio/CapsuleAudioEngine.kt` | `resolvePlayback` / `resolveDownload`; complete playback contract and client feedback |
| Audio library adapter | `CapsuleInnerTubeXPlayer.kt` | Session snapshot, extraction bundles, token provider and client attempts |
| Client eligibility | `CapsuleAudioFallbackPolicy.kt`, `CapsuleAudioClientStrategy.kt` | Bounded plans and exact manifest selection |
| Shared work | `AudioResolveCoordinator.kt`, `AudioResolveScheduler.kt` | Generations, cancellation, single-flight work and foreground priority |
| Client failure history | `TrackClientFailureCache.kt` | Expiring, bounded per-track state; separate playback/download instances |
| Stream and byte caches | `PlaybackDataCache.kt`, `AudioCacheIdentity.kt` | URL context/expiry, confirmed bytes and format identity |
| Video work | `CapsuleVideoResolveCoordinator.kt`, `YouTubeVideoResolver.kt` | Latest-only requests, official-clip matching and relevant result publication |
| Video IPC | `CapsuleVideoExtractorProtocol.kt`, `CapsuleVideoExtractorProvider.kt` | Bounded typed packets, deadlines and consumer-side revalidation |
| Video route selection | `innertube/.../video/CapsuleVideoRoutes.kt` | Eligibility, health, attempt budgets and fallback between independent routes |
| Video output contract | `VideoStreamValidation.kt`, `CapsuleDashManifest.kt` | CDN URLs, expiry, header limits and Capsule-generated indexed MPD |

All paths in the first column are relative to `app/src/main/kotlin/com/nikhil/yt/` unless explicitly marked `innertube`. Video extractor paths are under `innertube/src/main/kotlin/com/nikhil/yt/innertube/video/`.

## Audio extraction and fallback

There is one maintained audio extraction stack: InnerTubeX. NewPipe and PipePipe are video backends, not hidden audio fallbacks. Audio reserves are eligible client profiles inside the maintained stack.

Supported persisted profile IDs are `WEB_REMIX`, `VISIONOS`, `VISIONOS_0_1`, `TVHTML5_SIMPLY`, `WEB_EMBEDDED_PLAYER` and `WEB_CREATOR`. `AudioClientOrder` defines new-install defaults and migration. Preserve an existing explicit preference order. A cold WEB startup can temporarily use configured Vision profiles while WEB warming runs independently.

`CapsuleAudioClientStrategy` selects exactly the override manifest supplied by Capsule. It does not rewrite a library manifest, remove account cookies, or change a PoToken binding requirement to make a failing request look anonymous.

`CapsuleAudioFallbackPolicy` owns the outer plan:

- At most three profile attempts; recomputing eligibility cannot expand that budget.
- Prefetch uses one eligible primary. A shared prefetch promoted to foreground can use the foreground plan.
- One alternate after a transient network failure; a timeout is not a five-minute verdict against the client.
- One bounded cross-family attempt after a foreground bot challenge. The verdict stays local to the current track/resolve. Downloads use their own bot gate and do not rotate identities after a challenge.
- HTTP 429 opens the ten-minute audio breaker. A bot challenge alone does not create a cross-track client ban or that global breaker.
- Uploaded/restricted tracks retain the eligible authenticated path. The foreground WEB token provider is not installed on the download extractor.

The scheduler admits one audio extraction at a time. Explicit ranks are `PLAYBACK=0`, `PREFETCH=100`, `DOWNLOAD=200`; lower wins. Foreground can preempt background work, but promoting a shared prefetch must not promote an independent download of the same track. Parent cancellation is preserved; waiting in the scheduler does not consume the network extraction timeout.

The adapter retains the existing 8-second HTTP and 18-second complete resolve budgets. `AudioExtractorBoundary` converts a third-party `LinkageError` into an ordinary client-attempt I/O failure so the current bounded policy can recover. It does not swallow coroutine cancellation or VM failures.

Direct GVS media and client-sized byte ranges remain the production audio contract. HLS/SABR are not advertised as final audio transports. The live defensive SABR-only rejection/rollover is preserved; the unreachable standalone SABR debug probe has been removed. A future SABR adapter needs timestamp-based seeking and segment/cache identity before its chunks can become a Media3 source.

## Results, caches and format changes

`PlaybackData` carries URL, required headers, format/itag, expiry, client identity, loudness/tracking information and range chunk size. A fallback must preserve this complete contract; returning only a URL discards information needed to open it correctly.

`PlaybackDataCache` is bounded and checks the immutable session/policy/quality/network context. A link that has never delivered audio bytes has a short freshness window whether it was initially resolved as prefetch or foreground work. The removed `prefetched` field did not participate in that rule.

Successful client hints retain at most 128 tracks. Each playback/download failure history retains at most 256 tracks with a five-minute verdict lifetime. Reads do not extend that lifetime. A later failure cannot resurrect clients whose previous verdict expired. Explicit retry/network/session transitions clear the relevant state.

`AudioStreamContract` pins the format used by a Media3 source. If a refreshed reserve uses a different format, `AudioFormatChangedException` requests source recreation. Do not attach a new codec or itag to the old byte offset/extractor state. Download completion still requires verified contiguous bytes of the expected length.

Only relevant generations can publish. Stale jobs must not repopulate a URL cache after invalidation or replace the current song. `awaitForegroundAudioResolve` may rejoin a policy-invalidated shared job within the caller's existing timeout; it cannot revive a cancelled loader or abandoned track.

## Video extraction and fallback

The bundled routes are direct Vision, direct Android VR, PipePipe, NewPipe and direct Android Reel. `CapsuleVideoRouteHealth` prefers the working eligible route and temporarily pauses failed/bot-blocked routes. Enum declaration order is not the cold-start policy: the health policy currently prefers bounded direct Vision.

| Route | Individual budget |
| --- | ---: |
| Direct Vision | 4 s |
| Direct Android VR | 4 s |
| PipePipe | 10 s |
| NewPipe | 10 s |
| Direct Android Reel | 4 s |

The extraction request has a 36-second outer budget, IPC 40 seconds, and song matching/resolution 55 seconds. Route timeout allows the next route; cancellation of the whole request does not. HTTP 429 stops the chain. One bot-blocked route does not imply all independent video backends are blocked.

A route now validates its response **before** recording success or returning it. Invalid URL/manifest/header data, an expired link or the wrong requested video ID are extractor failures and allow the next eligible route. A binary-incompatible parser is classified as an extractor failure, not a network outage. Severe VM failures do not trigger another route attempt.

The provider and the consumer use the same output policy:

- HTTPS official `*.googlevideo.com` targets, correct domain boundaries, no userinfo, unexpected port, fragment or local/numeric host aliases.
- Bounded URL, manifest, quality-label and user-agent lengths, plus an aggregate IPC text budget; no header control characters.
- Fresh URL lifetime matching Media3 source preparation.
- Supported delivery enum and requested video identity before the consumer caches or publishes the result.
- Inline DASH is Capsule's generated static indexed MPD. Malformed XML, external entities, remote references and unsupported MPD elements are rejected. HLS remains a native segmented Media3 source; it is not progressive media.

Video has its own transport, byte cache, route health and request guard. The `:capsule_video` provider is non-exported and receives no account credentials. Process isolation contains parser crashes; it is **not** a security sandbox against malicious library code running under the app's UID.

## Changes that require explicit review

Do not combine cleanup with changing client order, authentication/token rules, timeout budgets, Media3 versions, codec selection, the A/V handoff, native media-session behavior or cache identity. Upgrade an extractor separately and validate its contract at the adapter. See [extractor maintenance](EXTRACTOR_MAINTENANCE.md) for file ownership and failure scenarios.
