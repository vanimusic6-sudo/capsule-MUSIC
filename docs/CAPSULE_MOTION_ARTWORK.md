# Capsule Animated Artwork — Immersion

Capsule MUSIC uses the Better Lyrics community artwork discovery API for
Apple Music animated album art and Spotify Canvas; all video decoding and
lifecycle management are authored independently of ArchiveTune.

## Lookup and playback

- **No eager requests.** A lookup only happens after a user opens the full
  Immersion player in Audio mode while music plays. Closing the sheet, opening
  another app, or changing tracks in a hidden player does not initiate new
  lookups or prefetches.
- Spotify Canvas and Apple animated artwork are requested **concurrently**.
  Spotify starts immediately, Apple after a short stagger. The first valid clip
  wins, and the slower request is cancelled. Apple codec failures can retry
  once through Spotify-only discovery.
- Lookup responses and misses have TTL limits; throttling receives a shorter
  cooldown. The user can turn the feature off in Appearance.
- A single **silent, repeat-one ExoPlayer** is retained for the Android
  application lifetime. Hiding the player disconnects the TextureView but
  leaves the timeline running. Reopening reattaches it at its current position.
  Finishing the Activity releases the session; process death also frees it.
- On each surface detach, keep one small Canvas frame so it can be displayed
  during the next TextureView handoff rather than exposing the album poster.
  The audio service and official-video engine are unchanged.

## Video-derived gradient

The same Capsule spatial video wallpaper engine is used for Canvas.
The *visible* TextureView supplies tiny 48x28 samples from decoded video
frames, at no more than ~6.7 per second. Samples are triggered by decoder
frame metadata, without a running polling timer. Extraction uses the existing
light filter and **CapsuleVideoAmbientBackdrop**, with 180ms blend between
frames and a soft fade from the static photo. No extra decoder or WebView.
The last sampled colour field can be retained while the player is hidden.
Sampling itself stops when the native view is detached.

## Energy tradeoff

Continuing Canvas while the app is running but its UI is hidden consumes more
battery than the previous pause-on-hide implementation. The separate player's
audio renderer is disabled; video is capped at 720x1280 and 2.5 Mbps. GPU
readback is disabled when no Canvas view is mounted. Surface detach may stop
rendering on some devices, but the Media3 timeline keeps advancing. This is a
best-effort continuous background experience within Android power constraints.

## Rights and service limitations

- API discovery: https://artwork.boidu.dev/
- Better Lyrics sources: https://github.com/better-lyrics/api
- Neither the GPL licence covering provider code nor access through a public
  third-party server grants independent reuse rights for Apple or Spotify video
  content. Review provider terms before public distribution.
- The service receives track title, primary artist, optional album and
  duration; only returned HTTPS Apple/Spotify CDN URLs may be decoded.

## Manual checks

1. Play a track with a live Apple or Spotify visual, close the player and
   reopen. Confirm no new network lookup and animation phase has advanced.
2. Switch tracks while the sheet is closed; confirm NO Canvas lookup until
   Immersion opens again, then correct track identity.
3. Switch to VIDEO while Canvas is moving and back to audio. The video mask
   must morph out of the moving Canvas, without an intervening album poster.
4. Verify moving gradient colours follow the *decoded Canvas* rather than the
   static artwork. Rotate/background/return and watch for black frames.
5. Pause music, open the queue/lyrics, disable the preference, then finish
   the app. Check resource ownership, resumed playback, and decoder shutdown.
6. Check both sources, empty matches, 429, HLS and MP4, fast track skipping.
