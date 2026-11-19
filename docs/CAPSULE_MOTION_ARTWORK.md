# Capsule Motion Artwork (Immersion)

Independent animated-artwork implementation for Capsule MUSIC. It does not copy
ArchiveTune code or interact with MusicService's audio/video players.

## Source, attribution and limitations

- Lookup: Better Lyrics / boidushya Artwork API, https://github.com/boidushya/artwork.boidu.dev
- Apple Music animated album artwork is preferred. Spotify Canvas at /canvas is
  tried only when Apple artwork is unavailable or its video fails to play.
- The service receives **song title, primary artist, optional album name and duration**.
  The returned videos are streamed directly from Apple or Spotify CDNs.
- Public API access can be rate-limited and does not guarantee every song has a
  licensed video. No Better Lyrics extension key is embedded or bypassed.
- Source code availability does **not** grant rights to redistribute Apple or
  Spotify audiovisual recordings. Project maintainers must review their usage
  terms before distributing the feature broadly.

## Lifecycle / energy policy

- Enabled only in Immersion when the full player is visible and audio is playing;
  no lookup or secondary decoder in the background, queue, lyrics, paused player,
  or dedicated video mode.
- A standalone Media3 ExoPlayer decodes the silent looping animation. It has
  audio track selection disabled, video size/bitrate limits, and repeat-one mode.
  The player is released on unmount.
- No new palette extraction, per-frame readback, WebView or recurring timer.
- The existing static artwork, soft edge blend and gradient remain underneath
  until the first rendered frame. Failures return to them automatically.
- Provider results and misses have separate bounded TTL caches. Server
  throttling/errors receive a short cooldown, never a tight retry loop.

## Manual verification

1. In Appearance select *Immersion*, enable *Live album artwork*.
2. Start audio for a song with an Apple Music animated album; verify only the
   cover moves and full-audio playback remains unaffected.
3. Start a track with Spotify Canvas but no Apple animation; verify the vertical
   loop crops without stretching and runs without sound.
4. With no motion available, verify a static cover remains unchanged.
5. Pause, collapse, open queue/full lyrics, and switch to video. Check hardware
   decoder stops, image remains readable and no blank/glass frame appears.
6. Fast-skip A -> B -> C. Verify stale motion never appears for C.
7. Disable the preference and verify zero artwork lookups / motion decoders.
