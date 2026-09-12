# Capsule AUDIO playback architecture

This document describes the invariants of the modern Capsule AUDIO path on `cleanup/capsule-player-only`. Treat these rules as part of the playback contract: changes that violate them should come with an explicit design decision and regression tests.

## 1. Boundary

Normal audio stream extraction has one supported boundary:

```text
MusicService
  -> CapsuleAudioEngine
  -> CapsuleInnerTubeXPlayer
  -> AudioResolveScheduler
  -> InnerTubeExtractor
```

Browse/search/account functionality can continue to use Capsule's legacy `innertube` module, but normal AUDIO playback must not silently fall back into an older player resolver.

`CapsuleAudioEngine` is the compatibility boundary exposed to the application. `CapsuleInnerTubeXPlayer` owns the modern extraction session and translates InnerTubeX output into Capsule's playback contract.

## 2. Request concurrency

Only one audio extraction is allowed to own the resolver at a time.

`AudioResolveScheduler` uses these explicit ranks:

| Priority | Rank | Meaning |
| --- | ---: | --- |
| `PLAYBACK` | 0 | Current foreground track |
| `PREFETCH` | 100 | Near-future queue item |
| `DOWNLOAD` | 200 | Background download resolve |

Lower rank means higher priority. Scheduling must never depend on `enum.ordinal`.

### Preemption rules

- `PLAYBACK` can preempt `PREFETCH` or `DOWNLOAD`.
- `PREFETCH` can preempt `DOWNLOAD`.
- Equal-priority work does not preempt itself.
- A preempted background operation is requeued by the scheduler.
- Parent coroutine cancellation is never converted into scheduler preemption.
- A queued operation cancelled by its owner must never reach the transport.

`promote(mediaId)` promotes only a shared `PREFETCH` for that media id. A separate `DOWNLOAD` of the same song must remain background work and therefore remain preemptible by foreground playback.

## 3. Rapid track changes

Fast swipe/skip bursts are a hostile workload for unofficial YouTube clients because naive implementations can start one player request chain per transient selection.

Capsule therefore keeps resolve work close to the current playback position:

- in-flight work is shared per media id;
- stale prefetches are cancelled;
- the current track and immediate look-ahead item are the relevant audio set;
- `PlaybackStabilityGate` delays extraction for rapidly changing selections;
- when the actual Media3 loader needs a prefetched item, that existing resolve is promoted rather than duplicated.

Do not increase prefetch depth casually. More look-ahead can reduce startup latency on poor networks, but it also increases speculative YouTube player traffic for songs that may never play.

## 4. Client policy

User-selectable playback policies currently map to one explicit InnerTubeX profile:

| Capsule policy | InnerTubeX profile |
| --- | --- |
| `VISIONOS` | `VISIONOS` |
| `WEB` | `WEB_REMIX` |
| `WEB_EMBEDDED` | `WEB_EMBEDDED_PLAYER` |

Legacy policy values are migration tombstones and normalize to `VISIONOS`.

### Strict explicit selection

When `ContentHints.playbackClientOverrideId` is present, `CapsuleAudioClientStrategy` must keep that selection strict. If the selected client is excluded or unavailable, the strategy returns no candidate instead of silently rotating to another client identity.

This is intentional. A failure is preferable to a rapid identity carousel that can multiply player requests and complicate anti-bot behavior.

### Generic InnerTubeX calls

When no explicit override is supplied, `CapsuleAudioClientStrategy` delegates to the maintained `ContentAwareFallbackStrategy`. This keeps library-internal/prewarm behavior compatible without weakening Capsule's explicit playback policy.

## 5. Rate-limit and bot-check safety

`CapsulePlaybackSafety` is the global AUDIO circuit breaker.

The breaker opens for:

- HTTP 429;
- explicit bot/captcha/unusual-traffic signals in a player response.

The current cooldown is ten minutes. During cooldown new AUDIO extraction is rejected locally rather than contacting YouTube again.

A bot-check or 429 is **not** a reason to rotate through additional playback identities.

### Wire-level observation

`CapsuleAudioRequestInterceptor` inspects YouTube player responses before InnerTubeX can retry or normalize away the original failure reason.

Important behavior:

- a raw HTTP 429 opens the breaker immediately;
- an HTTP 200 player response whose `playabilityStatus` contains an explicit bot-check also opens the breaker;
- age verification/login-required responses are not automatically treated as bot checks;
- successful response metadata containing text such as "not a bot" must not produce a false positive;
- once the breaker is open, nested library retries are stopped by the interceptor before another request reaches YouTube.

## 6. Failure classification

Machine-readable signals take priority over localized human text:

1. HTTP status;
2. `playabilityStatus`;
3. explicit error text as a fallback.

Generic "sign in" text is not enough to classify a response as a bot-check.

Keep AUDIO and VIDEO failure classification aligned through `YouTubeFailureClassifier` rather than maintaining separate collections of string heuristics.

## 7. Stream compatibility and per-track failures

Capsule's current Media3 AUDIO consumer uses direct GVS URLs. It intentionally does not advertise transport capabilities it cannot honor:

- HLS disabled;
- SABR disabled as a final transport;
- bounded-range transport disabled until Capsule implements the corresponding scheduler.

If InnerTubeX returns a SABR-only result, Capsule can retire that client for the current song and perform only a bounded rollover. Per-song failed client state has a TTL and must not poison unrelated tracks globally.

A stream/source rejection is local to the song + extraction client unless the response is a global stop signal such as HTTP 429 or an explicit bot-check.

## 8. Playback cache contract

`PlaybackDataCache` stores the complete extraction contract, not just the URL:

- selected format;
- signed stream URL;
- expiry;
- stream client;
- required request headers;
- loudness/tracking metadata carried by `PlaybackData`.

Entries are invalid when:

- the current playback context differs from the context stored with the entry;
- the signed stream is expired;
- the remaining lifetime is below the caller's freshness threshold.

Do not reuse a cached signed URL across a playback-policy/context change.

## 9. Session and PoToken lifecycle

`CapsuleInnerTubeXPlayer` owns an extraction bundle containing the HTTP client, InnerTube instance, cipher service, and extractor. Bundle identity is derived from the immutable playback session snapshot rather than a lossy hash.

Web PoToken generation is visitor-bound and shared:

- one BotGuard/WebView minter is reused for a visitor-data value;
- startup prewarm and first playback share that same session;
- generation is protected by a mutex;
- slow/dead WebView behavior is bounded by timeout;
- cancellation closes unpublished WebView state;
- deterministic broken-WebView state fails closed instead of repeatedly creating renderers.

Anonymous playback/session helpers must not import account cookies or `dataSyncId` into public anonymous requests. Only explicitly enabled public attestation data may cross that boundary.

## 10. Retry rules

Retries must be bounded and classified.

Do not add a generic loop around the complete player extraction stack. In particular:

- do not retry HTTP 429;
- do not retry explicit bot-checks;
- do not use a failure as a trigger to sweep many client identities;
- do not allow stale prefetch retries after the media item leaves the relevant queue window;
- do not start the network timeout while a request is merely waiting for its scheduler turn.

## 11. VIDEO isolation

Optional VIDEO playback has its own request guard/backoff behavior and must not be able to destabilize normal AUDIO playback.

A VIDEO-specific block should remain VIDEO-specific. Conversely, shared classification helpers may be reused so that the same YouTube response is interpreted consistently across modes.

## 12. Required regression coverage

Changes to the AUDIO path should preserve tests for at least these cases:

- foreground playback preempts a background download;
- a same-song download is not promoted with a shared prefetch;
- cancelled queued work never reaches the transport;
- promoted prefetch remains one extraction;
- parent cancellation is not swallowed as preemption;
- scheduler wait time is not counted as transport timeout;
- explicit client choices stay strict;
- no-override library calls retain maintained fallback behavior;
- HTTP 429 stops nested extractor retries;
- HTTP 200 bot-check responses stop nested extractor retries;
- age restrictions do not open the global bot breaker;
- successful metadata containing bot-like words does not create false positives;
- cached playback data respects expiry and playback-context identity.

The CI workflow `.github/workflows/youtube-core-validation.yml` is expected to run the relevant unit tests and build a universal debug APK for playback-core changes.

## 13. Known debt outside the extraction core

The extraction core is intentionally narrower and cleaner than the current application service around it. `MusicService` still owns too many unrelated responsibilities.

A future architecture cleanup should extract responsibilities incrementally, for example:

- `AudioResolveCoordinator` / `AudioPrefetchManager`;
- `PlaybackRecoveryManager`;
- `VideoModeController`;
- `AudioFocusController`;
- `BluetoothPlaybackController`;
- `AudioEffectsController`;
- `PlaybackPersistence`;
- scrobbling/integration coordinators.

Do not combine that large service decomposition with another redesign of the YouTube extraction protocol. Move one responsibility at a time, preserve observable behavior, and add regression tests before removing the old path.

## 14. Maintenance checklist

Before merging a playback-core change, verify:

- Does it increase the number of possible YouTube player requests for one user action?
- Can stale/cancelled work still reach the network?
- Can a 429 or explicit bot-check trigger another client attempt?
- Can download/prefetch block foreground playback?
- Does the change preserve required GVS headers and signed URL expiry?
- Does a cache entry survive a context change when it should not?
- Does a library update bypass `CapsuleAudioRequestInterceptor` or the scheduler?
- Is the behavior covered by a deterministic regression test?

If any answer is uncertain, treat the change as a playback-safety change rather than a cosmetic refactor.
