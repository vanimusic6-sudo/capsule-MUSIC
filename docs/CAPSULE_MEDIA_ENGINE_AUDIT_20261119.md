# Capsule MUSIC — media engine architecture audit
**Date:** 2026-11-19  
**Branch:** `engine/media-core-20261119` (isolated from `feature/lyrics-release-polish-20261118`)

## Scope and evidence

This is a source-driven review of the audio resolve, CDN loading, official-video
selection, isolated extractor, video first-frame handoff, and offline-download
subsystems. Code reviewed includes `CapsuleInnerTubeXPlayer.kt`,
`AudioResolveScheduler.kt`, `CapsuleAudioFallbackPolicy.kt`,
`AudioChunkedDataSource.kt`, `AudioCdnHostHealth.kt`,
`CapsuleLoadControl.kt`, `ResolvingAudioDownloader.kt`,
`DownloadPlaybackCoexistence.kt`, `DownloadUtil.kt`,
`YouTubeVideoResolver.kt`, `CapsuleWebVideoExtractor.kt`,
`CapsuleVideoRoutes.kt`, `YouTubeMusicVideoMatcher.kt`,
`YouTubeMusicVideoLink.kt`, `CapsuleVideoCompanion.kt`, and
`CapsuleVideoExtractorProvider.kt`. MusicService entry points and
corresponding source and unit tests were traced. This is **not** evidence that
every line of the full 6,000+ line MusicService or every third-party library was
individually validated.

Comparative upstream code directly inspected:
- **MetroList** (`MetrolistGroup/Metrolist`): `AndroidVideoModeController.kt`,
  `AndroidAudioTrackSelection.kt`, `PlaybackLoadControl.kt`;
  its alternative companion/queue mode and controlled video commit are useful.
- **ArchiveTune** (`rukamori/ArchiveTune`): `ResolveAudioStreamUseCase.kt`,
  `YoutubeiStreamRepository.kt`, `NextStreamPreloader.kt`,
  `ChunkedDataSource.kt`; its keyed ownership for in-flight stream resolves,
  bounded prefetch and cache-freshness checks are useful.

The upstream implementations are **reference material**, not code transplanted
unchanged. Capsule retains its own video IPC, audio scheduler and data model.

## Latency: where 20 seconds could come from

For an ordinary audio track switching to VIDEO:

1. `MusicService.enterCapsuleVideoMode()` initiates
   `CapsuleVideoResolveCoordinator`, preserving playing audio.
2. `YouTubeVideoResolver.resolveForSong()` first tries verified source-video
   identity or a cached song-to-clip map. Otherwise it searches the YouTube
   Music Videos surface, allowing two queries for a strong match.
3. `resolveExact()` previously awaited `CapsuleWebVideoExtractor.resolve()`
   for **up to 10 seconds**, *then* called the isolated extractor.
4. The isolated `CapsuleVideoRoutes` previously tried VISIONOS (up to 4s),
   ANDROID_VR (4s), NewPipe (10s), PipePipe (10s), and reel (4s). A new
   unproven network could therefore accumulate multiple sequential delays.
5. Media3 still needs its own CDN/manifest/first-frame preparation after a
   resolved stream is found; API timing is not equal to first-frame timing.

**Implemented:** budget the first WEB attempt at 4.2s–6.8s depending on
requested maximum resolution, then try the existing guarded independent
fallback. The timeout is **only** for the preferred route, not a global
unsupported 5s video deadline. Cancelling a stale request still propagates.
This decreases potential serial waiting but does **not** guarantee <5s
first frame; the remaining remote route chain and CDN first bytes need real
timing captures.

**Important remaining risk:** route health already remembers the last
successful backend but still serializes route discovery on cold failures.
Do not race multiple unauthenticated /player identities without a measured
need: this can provoke rate limits and undermines existing bot-safety rules.

## Identity and video trust

`YouTubeMusicVideoLinkResolver` accepts YouTube Music's
`MUSIC_VIDEO_TYPE_OMV` from its Videos search surface. But its former
`scoreCandidate` awarded 150 for the same normalized title, 55 for OMV
status, 28 for the words "Official Video", and could still accept a result
after merely deducting 55 for a wrong artist. That is **not** reliable
identity proof.

**Implemented:**
- Reject a wrong or non-whole-phrase artist match, even if category/title
  say official.
- When source artist metadata is missing, accept only an explicitly
  source-ID-verified OMV; do not guess by title alone.
- Continue to a second Videos search if only weak candidates were found,
  rather than stopping on any match.
- Keep rejection of live, remixes, fan, lyric uploads, visualizers and
  anti-bot/challenge responses as in the original implementation.

This improves match precision at the expense of sometimes showing
"no official video." It does not prove distribution/licensing rights;
an official YouTube Music OMV category is a metadata signal, not a content
license to re-distribute the clip.

## Audio: stability, quality and privacy

Capsule already has valuable safety barriers:
- InnerTubeX extraction is separate from browse/search InnerTube.
  `AudioResolveScheduler` elevates foreground playback and cancels obsolete
  prefetch; only a bounded client chain is permitted.
- `CapsuleAudioFallbackPolicy` respects user client priority, authentication
  and uploaded tracks. Prefetch does not cycle client profiles.
- `AudioCdnHostHealth` tracks *observed* slow/hard-failing CDN groups,
  has cooldown, healthy probes and an escape hatch for repeated local skips.
- `AudioChunkedDataSource` uses bounded ranges while preserving format/cache
  keys and media seek semantics.
- `CapsuleLoadControl` intentionally allocates up to 6MiB and a long 180s
  audio horizon; copying MetroList's 2MiB buffer would be inappropriate for
  Capsule's long podcasts and weak/mobile routes.

**Implemented:** update the pinned InnerTubeX from 0.11.2 to 0.11.9. The
upstream changes cover SABR audio selection, late token handling, cipher
scripts and current YouTube Music IDs. Add routing failures
`NoRouteToHostException` to DNS failures as **network-wide**, so VPN/Wi-Fi
switches do not poison the healthy per-song playback-client cache.

**Privacy/security:** do not brute-force identity rotation, fake logins,
scrape private accounts, force or misbind PoTokens, or disable 429/bot
backoffs. Stable session identifiers, no signed URLs/tokens in logs, and
bounded network pacing provide better privacy *and* reliability than
aggressive evasion.

## Downloading and offline

`ResolvingAudioDownloader` verifies a complete content length before
publishing a completed download, tries playback cache first, uses a bounded
admission slot and discards failed signed URLs. `DownloadUtil` has an
independent authenticated/anonymous client plan and explicit retry budget;
`DownloadPlaybackCoexistence` yields bandwidth during foreground rebuffer
and `AudioDownloadSlots` caps competing file transfers to two.

No evidence in the inspected paths justifies replacing this stack with
ArchiveTune's simpler `ChunkedDataSource` or MetroList's different cache.
A larger rewrite would need regression evidence for: resume after expired
URL, login changes during download, partial content, cache hash/format
mismatch, zero-byte responses, and queue preemption.

## Dependency update / change control

- InnerTubeX: **v0.11.2 → v0.11.9** (upstream tagged 2026-11-17)
- NewPipeExtractor: **v0.26.5 → v0.27.1** (tagged 2026-11-10; 0.27.0
  changed Java compatibility to 17 and removed old API surfaces)
- Run TEST and R8 DEV builds before approving or merging. In particular,
  the NewPipe change is a breaking update: **roll back only that coordinate**
  if third-party API incompatibility is proven and cannot be fixed safely.

Separate `engine/**` CI now runs optimized DEV APK, test APK, and VIDEO/audio
regression tests on every change. Previously only the test APK ran there.

## On-device acceptance gate (not yet asserted)

Record exact phone/model and network, 20 official tracks and 10 long podcasts.
For each record: button → match time, match → URL time, URL → first video
frame, CDN first byte, effective video resolution, 403/410/429 responses,
audio continuity, phone power/heat and battery draw. Test under cold start,
warm cache, network swap, lock/screen off, VPN, repeated seeks and downloads.

Suggested goals to evaluate rather than promise:
- Cold video first-frame p50 < 6s, p95 < 12s on a good connection, with
  no 20s fallthroughs in normal operation.
- Wrong-channel/lyric/fan-video selection = zero in the evaluation sample.
- Audio continues unbroken while video loads or fails, and download
  prefetch never preempts current music.
- No repeat stream-client carousel following an actual 429/bot challenge.

**Status:** changes and test/build automation committed to a non-main branch.
This document is a technical audit and implementation status, not a claim
that a fully redesigned engine has been proven on a physical phone.
