# Capsule Music — native dual-track SABR VIDEO engine (test branch)

**Branch:** `feature/native-sabr-video-20261102`  
**Status:** real in-app UMP/POST playback architecture, not a URL-only SABR flag.
This is a deliberately experimental VIDEO route. No live phone speed, first-frame
latency or SABR success rate may be claimed from CI compilation alone.

## Architecture, from tapping VIDEO to first frame

1. MUSIC continues playing through its existing separate stable audio engine.
2. Existing official-video match resolves an exact 11-character YouTube clip ID.
3. The isolated `:capsule_video` extractor requests the VisionOS player **once**.
   If that response includes `serverAbrStreamingUrl`, the ustreamer config,
   supported H.264 video / AAC audio formats with real content lengths and an
   expiration >=45s away, Capsule selects the native SABR route. Otherwise its
   existing direct/DASH/HLS/NewPipe routes stay available.
4. The extractor sends only a validated GoogleVideo endpoint, a bounded
   ustreamer base64 payload, itags, lengths, last-modified values, duration,
   and the matching client identity through the existing private Binder IPC.
   Signed URL / session content is never included in diagnostics.
5. The app constructs a **genuine Media3 MergingMediaSource** from TWO native
   `SabrVideoDataSource` instances: video and audio. The registry owns one
   `SabrVideoStream` for the selected track; Media3's seek/open/close does not
   destroy or re-extract the active session.
6. A dedicated thread executes a stateful `VideoPlaybackAbrRequest` POST,
   interprets UMP `MEDIA_HEADER` / `MEDIA` / policy / context / redirect /
   protection frames, pins the two required itags, repeats the request and
   reassembles the video and audio bytes at their **absolute positions** in
   separate disk-backed buffers. No full video is held in RAM.
7. The existing independent Capsule VIDEO player decodes those buffers through
   Media3, seeks through SABR's restart/anchor logic, and takes over audio
   **only when the first frame and soundtrack are actually ready**.
8. Leaving VIDEO, skipping, reaching the end, errors, or destroying the service
   destroys both SABR buffers/threads; original MUSIC remains independent.
   Real 403/429 responses, attestation caps, timeouts and network failures
   propagate to the existing bounded VIDEO recovery / alternative route.

## Source and legal provenance

Protocol and disk-buffer components are GPL-3.0 adaptations from
[ZemerTeam/zemer-app](https://github.com/ZemerTeam/zemer-app), files originally
located at `app/src/main/kotlin/com/jtech/zemer/playback/sabr/`.
Capsule's adaptation lives at
`app/src/main/kotlin/com/nikhil/yt/playback/video/sabr/`.
The repository remains GPL-3.0. The upstream code is substantially different
from a progressive `videoplayback` URL or an ordinary DASH manifest parser.

Protocol references:
- [PipePipe's SABR documentation](https://priveetee.github.io/Docs-PipePipe/developer-guide/introduction.html)
- [LuanRT/googlevideo](https://github.com/LuanRT/googlevideo)
- [Zemer SABR engineering notes](https://github.com/ZemerTeam/zemer-app/blob/main/docs/sabr/README.md)

## Safety boundaries

- VIDEO-only: zero changes to MUSIC audio resolver, download cache or playback
  client rotation.
- Only `https://*.googlevideo.com` signed endpoints, never arbitrary redirects.
- Source-IP/loopback / signed-URL expiry are validated before the IPC payload
  is cached. Config base64 <=65K chars, strict itags and stream lengths.
- Every UMP response is capped to 16 MiB before parsing; a dedicated OkHttp
  client has 5s connect, 10s read and 20s whole-call bounds, no HTTP redirects.
- A dead initial network/TLS/DNS route terminates into the proven VIDEO
  fallback; a `403/429` does not retry endlessly against the same session.
- All two-track media bytes and metadata live in the app's cache directory,
  with unique spool files and crash cleanup. No account cookies or personal
  session tokens are exported to third-party services.
- An attestation cap is a **failure**, not proof of successful playback; Capsule
  returns to a working direct route and retains its AUDIO session.

## Why this is a NEW engine

Previously, Capsule only accepted extracted normal CDN URLs, indexed DASH
manifests or HLS playlists; SABR-only server responses could not be played.
This architecture really sends stateful UMP POSTs, decodes stream frames,
reassembles bytes by itag and gives Media3 live seekable dual-track sources.
The old URL engine is deliberately retained as an independent **fallback**,
not silently used in place of SABR.

## What remains experimentally uncertain

- YouTube sometimes enforces PO-token / attestation requirements. The initial
  VisionOS route transmits only the identity and ustreamer returned by the
  server, with an empty SABR poToken when none is available. YouTube can serve
  an initial segment then throttle/cap. Such cases must explicitly fail over,
  not freeze or claim SABR success.
- The current experimental SABR track pins one compatible H.264/AAC itag pair.
  Fully transparent mid-stream bitrate switching, other codecs, protected
  content and live/DVR are NOT part of this branch; unsupported responses stay
  on the previous working engine.
- Unlike Android video on a real device, CI does not verify network availability,
  live first-frame median, codec thermal behavior, or CDN adaptive quality.

## Verification

Targeted tests run under the
`Capsule VIDEO Startup and Buffer Regression` GitHub Actions workflow:
- Protobuf writer/reader, UMP variable-length framing and media header IDs;
- bounded byte-accurate disk segment buffer, seek resets, demand pacing;
- generated dual-track VideoPlaybackAbrRequest and attestation cap;
- **real session loop with an intercepted OkHttp HTTP POST containing UMP
  video+audio frames**: each exact byte must be read through its correct track
  buffer; an HTTP 403 must be terminal after a single request;
- synthetic VisionOS /player JSON with no direct video URL yields valid
  new-SABR IPC metadata and the correct 720p or 360p pinned video itag;
- existing previous VIDEO session and artwork regressions.

Device test:
1. Start any VIDEO whose search has an official clip; AUDIO should keep playing
   while the clip is resolved.
2. Collect logs tagged `CapsuleVideo` and `SabrVideoSession`.
3. The line `source-native-sabr` explicitly proves that the selected media
   source is the new transport. `SABR timing first-response` measures
   UMP/CDN handshake; `VIDEO timing ready` measures decoding/buffering;
   `VIDEO timing first-frame` measures complete visible startup.
4. Seek to 30%, 70%, back to 10%, pause/resume, lock/unlock, skip between
   clips, interrupt VPN/Wi-Fi and return to AUDIO. Check for no black-frame
   flashes, no wrong-audio ownership, no persistent buffering, no leaked
   sessions, and bounded thermal/network impact.
5. If the output says `source-direct` rather than `source-native-sabr`,
   that particular clip/client response did **not** qualify for native SABR
   or recovered into the proven backup. That is not a SABR success.

The requested target of 4–6s is a *benchmark*, not a measured conclusion.
