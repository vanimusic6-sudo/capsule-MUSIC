# Capsule Music: Playback, network-pressure and recovery audit
25 October 2026 · release/01-core-reliability

## Scope and level of confidence

The user observes new YouTube bot checks. This is a confirmed symptom, but its cause is **not** established by code inspection alone. There is not yet a synchronized, per-origin request census of MUSIC AUDIO, separate downloads, VIDEO discovery and metadata browsing. The October 24 test logs relate to an earlier APK; no fresh device trace with the current build's server response has been attached. Therefore a definitive claim about which component triggered a challenge would be misleading.

The platform can also legitimately enforce client attestation, account authorization, rights or rate limits. A local optimization can reduce avoidable work but cannot guarantee that a server will accept a request. A confirmed server bot challenge should be respected, not treated as an invitation to change identity or evade verification.

## Request-flow inventory

| Subsystem | Pipeline | Request risk |
| --- | --- | --- |
| AUDIO | MusicService.audioResolveJob -> AudioResolveCoordinator -> CapsuleInnerTubeXPlayer -> InnerTubeX player -> Media3 CDN | Shared per-mediaId in-flight resolution exists. Normal selection has a 250 ms stability window; foreground may make a bounded set of profile attempts. |
| Upcoming audio | MusicService.prefetchUpcomingAudio | Requests one next song just in time, generally after current playback is established; still independent of VIDEO and download request budgets. |
| YouTube download | DownloadUtil -> AudioDownloadQueue -> ResolvingAudioDownloader -> InnerTubeX download extractor | Separate client list, URL cache and bot gate. Up to two shared file-transfer slots. |
| VIDEO | YouTubeVideoResolver -> video matching / separate extractor IPC -> VIDEO CDN | Explicit request, independent throttling, separate video process. Its guard does not share AUDIO's server health record. |
| Browse and search | Legacy InnerTube API | Metadata requests are outside the AUDIO serialized resolver. |
| Artwork | DownloadedArtwork and DownloadUtil startup repair | Image requests do not call /player but add bandwidth, sockets and CPU contention; the prior startup repair scanned all completed downloads. |

## Proven findings from repository source and earlier diagnostics

- The initial reliability patch corrected premature 1–5 ms cancellations of true Media3 loader demands that were misclassified as speculative prefetch.
- Re-adding an active Media3 download could reset an in-flight transfer. The current queue preserves running transfers and caps concurrent file downloads.
- The audio CDN source can open one 1 MiB range per chunk. This protects long, transport-throttled episodes but adds extra HTTP opens for ordinary short songs. The current change enables a single request for identified small files up to 4 MiB, only when the extractor provided a normal chunk contract and the request is not a rapid-skip preview.
- Offline art fetching used to take a global write mutex across the network request; concurrent art saves were serialized. Network reads now happen before taking the write mutex, with another existence check under the lock.
- Completion of artwork also held a shared audio transfer permit. Now the audio transfer slot releases after audio bytes finish; Media3 still waits for the artwork task before reporting success.
- On app startup the legacy cover repair queue could enqueue every saved item even on metered cellular. It now uses a small, six-entry, validated and unmetered best-effort batch.
- Current audio challenge handling is local to each serialized foreground media resolve; downloads and VIDEO have separate cooldowns. A process-local optional-work quiet-time value is now recorded after explicit challenges, but integrations that would actually apply it across all workers are **not yet complete**.
- The artist toolbar and cover were blending with incompatible opacity timing. The toolbar becomes opaque only as the shared portrait fade approaches completion, greatly reducing the top-edge double-dark strip.

## What is not established

The available source alone cannot distinguish a server-origin bot challenge from a missing/invalid playback authorization, an expired signed URL, a CDN-specific 403/410, a proxy route failure or a socket reset. A 403 must never be labelled bot-check without a classifier verdict. Earlier captures suggested refused new sockets were more common than refused reused connections; correlation is not proof that socket churn caused a server bot challenge.

Likewise, VIDEO being isolated does not prove it is harmless to overall network load: its metadata operations are independently guarded and need time-aligned counts. Browsing, token initialization, cookie/session changes, and batch downloads also need measurements before attributing a server verdict to one origin.

## Layered recovery architecture

1. **Classify accurately.** Distinguish HTTP 429, machine-readable bot/playability response, authorization/age restrictions, no format, stale stream signature, CDN transport/reset/timeouts, and cancellation of an obsolete selection. Never collapse all of these into "try next client."
2. **Reuse work.** Prefer complete offline audio and existing Media3 cache, retain healthy same-media in-flight extraction, and share a valid signed URL for compatible foreground requests. Cancel only stale speculative work.
3. **Prioritize.** User-requested foreground audio and current CDN continuation outrank manual downloads, collection downloads, VIDEO discovery, speculative next-track extraction and legacy cover repair. Do not pause a starving audio buffer to preserve optional work.
4. **Finite recovery.** Retry genuine transient transport failures only within explicit limits. A valid refreshed signed URL is appropriate for an expired link, not as a generic response to an anti-bot challenge. Respect server-provided cooldowns and continue local playback.
5. **No background storms.** Avoid mass legacy artwork repairs, repeated adds of live download requests, duplicate VIDEO matching, unnecessarily small CDN segments on short songs, and prefetch for skipped/abandoned tracks.
6. **Independent failure containment.** VIDEO failure must not destroy MUSIC queue or audio URL. Download failures must not invalidate foreground playback tokens or dispose audio session.
7. **Offline integrity.** Report downloaded only after complete bytes and durable cover storage. Cancellation/delete removes only owned partial data and never deletes playlist membership.
8. **Privacy/security.** Avoid logging signed URLs, cookies, bearer tokens or identifiable filenames. Restrict MediaStore deletes to app-owned recordings; protect crash reports and backup rules.


## Re-analysis of the supplied 24 October device capture (1,745 log lines)

The original on-device file `capsule-logs-2026-10-24_21-34-29.txt`, covering approximately 21:20–21:34, materially narrows the hypotheses:

- **59 InnerTubeX AUDIO extraction-profile attempts:** **46 DOWNLOAD (78%)**, **12 PLAYBACK**, and **1 PREFETCH**. Across these, 44 distinct media IDs appear. This is a *profile-attempt count, not an exact HTTP request count*: some extractor attempts perform several HTTP operations.
- Several songs repeated download resolution: one had **five** DOWNLOAD profile attempts, another three. This establishes unnecessary or failed re-resolution pressure worth instrumenting, but does not by itself prove a server challenge was caused by those requests.
- **No VIDEO request records** were present in this log. VIDEO remains an unverified possibility on other sessions, but it was *not observed causing* this captured failure.
- **57 visible player-status lines:** **52 OK**, **3 LOGIN_REQUIRED**, **2 UNPLAYABLE**. No HTTP 429 was recorded.
- At **21:32:05**, WEB_REMIX returned `player-item status=OK` and a signed stream. Its first Googlevideo CDN open failed with **HTTP 403 at 21:32:07** before reading any audio bytes. The client immediately retried **the identical signed URL and byte range**, receiving a second **HTTP 403 at 21:32:08**. Both failures occurred on new HTTP/1.1 connections.
- The player then attempted alternative profiles: VISIONOS returned `LOGIN_REQUIRED` at **21:32:09**, TVHTML5_SIMPLY `UNPLAYABLE` at **21:32:10**. The app logged `AUDIO bot-check isolated` for both and exhausted its local fallback. The statuses alone do **not** prove anti-bot verification, because explicit reason text or an interceptor's wire verdict could change classification; that evidence is absent from these log lines.
- The 403 record contained `authChallenge=false` in the CDN diagnostic; this is not a proof of permission, it only means that the CDN response itself did not expose a recognized challenge.
- Other CDN opens timed out after around **15 seconds** with no server HTTP status. That is separately a network transport performance problem, not evidence of bot enforcement.

**Interpretation:** In this actual captured session, automatic/queued download work dominates the recorded AUDIO profile attempts. That makes DOWNLOAD the first subsystem to profile and throttle when foreground startup is suffering, **rather than blaming VIDEO without evidence**. The immediate duplicate same-URL CDN 403 attempt is another concrete source of wasted requests. More importantly, we have *not* proved whether the 403 was due to account/token acceptance, route, socket conditions, URL binding or platform enforcement; no reliable client-switch workaround should be claimed.

**Required next check:** per-origin wire request counters and precise failure-category logging around the same minute on the user's newest APK. The current changes reduce network work but **do not eliminate server bot checks**. Respect explicit server verification rather than repeatedly changing profiles.

## Critical instrumentation still missing

A single 60-second redacted request census should record AUDIO player starts, prefetch starts, DOWNLOAD player starts, VIDEO metadata/extractor starts, metadata browse requests, artwork fetches, CDN opens, HTTP status classes, classified server verdicts, cache hits, superseded cancellations and successful first bytes. Give each request a local anonymous origin marker, not a stable user or video identifier. Record p50/p95 first-audio-byte, first-frame timing, buffer underruns, failed reads, startup radio/network-state and app CPU/wakelock counts.

Without that synchronized census we cannot honestly say which path "burns" the AUDIO request allowance.

## Acceptance matrix before release

- Fully offline downloaded playlist: **zero** network requests, intact artwork and Media3 cache.
- 20 rapid skips, 30 normal next transitions, ten uncached seeks, under Wi-Fi and metered cellular with controlled network loss.
- Batch download of 100 tracks plus one manual high-priority track plus uninterrupted foreground listening; no more than two file-transfer workers, no active-download reset.
- VIDEO mode left in AUDIO: no unsolicited video resolver; explicit VIDEO request either succeeds or gracefully reports a genuine server-side restriction.
- Interrupt downloads at 10%, 90% and while saving artwork; verify no orphaned partial data, cache corruption or lost playlist members.
- Android 16/17 HONOR: baseline versus current release first-byte p50/p95, CPU time, thermal status and battery usage across matched 30-minute runs.
- GitHub Actions debug/release and unit tests, plus a device log confirming the challenge's actual originating subsystem. Do not claim zero server challenges from synthetic tests.

## External references

yt-dlp YouTube extractor wiki: https://github.com/yt-dlp/yt-dlp/wiki/Extractors
yt-dlp PO Token Guide (2026): https://github.com/yt-dlp/yt-dlp/wiki/Po-Token-Guide
Media3 network stacks / caching: https://developer.android.com/media/media3/exoplayer/network-stacks

## Sign-off

This is a **staged, not final**, deep source audit. The code changes above are not yet proof of device-level performance or elimination of server bot checks. The shared traffic policy, complete end-to-end request census and additional recovery testing remain open work. No changes are merged into main automatically.
