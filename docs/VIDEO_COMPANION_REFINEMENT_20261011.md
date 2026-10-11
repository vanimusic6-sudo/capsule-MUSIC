# VIDEO companion refinement (from c77358d)

User reproduction: 8963.mp4, 6.72 seconds, 592×1280. The top cover remained white while the lower page switched to grey and the VIDEO Surface appeared later. This was a real two-tone UI regression, not a missing album thumbnail.

## Fixed in experiment

- VIDEO no longer replaces AUDIO's MediaItem, so AUDIO stays the sole owner of the MusicService session, playlist, seek, CDN quarantine and retry budget.
- Separate ExoPlayer reads only previously resolved signed VIDEO URLs; `CapsuleVideoDirectMediaSourceFactory` never calls the extractor or AUDIO ResolvingDataSource. Muxed and split VIDEO formats both use `videoCache`.
- Removed redundant optional preflight that could delay VIDEO by another six seconds. Companion begins buffering immediately after NewPipe resolves; large CDN media already uses bounded 4 MiB HTTP Range requests.
- MusicService silences MUSIC only after VIDEO's real first frame and selected soundtrack are playable, waits ~115 ms, then unmutes VIDEO. VIDEO stalls immediately restore MUSIC audio.
- Foreground/background: VIDEO mode remains selected. Background parks just the VIDEO renderer and allows the existing MUSIC decoder to play sound. Reopening seeks the VIDEO renderer to the current AUDIO position without making new metadata requests.
- Immersion keeps its calculated album gradient across the entire canvas, rather than suppressing the previous full-frame backdrop and showing an inconsistent grey replacement.
- The VIDEO window attaches ONLY the companion's renderer, not the MUSIC audio player.

## Architectural safeguards

- Audio queue and CDN transport are unchanged.
- Normal next-track selection stops video and restores main volume immediately.
- VIDEO first-frame and original soundtrack are mandatory; if they do not arrive within 12 seconds of foreground playback, VIDEO stops and MUSIC continues.
- The stable branch `refactor/video-sandbox-core-20261011` and commit `c77358d` are NOT modified.
- CI establishes compile/test/signing only. Check physical device, latency and crossfades before production merge.

## Future extractor fallbacks researched, not silently enabled

- PipePipe is a maintained hard fork of NewPipe; a direct code port is possible with GPL compliance, but similar anonymous YouTube IP restrictions may apply.
- youtubei.js (YouTube.js) is an independently maintained MIT-licensed Innertube client. Android would need an isolated JS runtime and explicit signed stream test matrix.
- yt-dlp has strong extraction breadth, but packaging Python plus frequent binary/updater maintenance in a native Android media app carries major size and stability costs.
- Piped/Invidious are server-mediated and introduce external trust/availability requirements; not used automatically.
- No fallback can guarantee bypass of a genuine upstream bot/IP restriction; avoid retry storms and avoid involving the AUDIO extractor.

## Second-pass hardening

- VIDEO now has a distinct OkHttp Dispatcher (2 concurrent requests) and 2-connection pool; AUDIO connection scheduling is not shared.
- Returning explicitly VIDEO→AUDIO includes a ~105 ms quiet boundary. Fatal error, screen-off and next-track paths restore AUDIO promptly; the existing VIDEO switch still uses a ~115 ms gap.
- Robolectric tests verify muxed signed video goes directly to a single ProgressiveMediaSource, split VIDEO merges two prepared signed streams, and no network extraction happens inside MediaSource creation.
