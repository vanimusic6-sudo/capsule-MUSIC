# Search, artwork and collection header latency — code audit (2026-11-01)

## Evidence and scope

Compared Capsule's active `fix/rollback-search-matte-thermal-20261101` implementation with ArchiveTune's public source (`rukamori/ArchiveTune`, `OnlineSearchViewModel.kt`, `App.kt`, `SearchDiscoveryRepository.kt`). Inspected the supplied playlist screen capture and recording showing the full-bleed cover, initial loading artwork, and title transitioning to the pinned toolbar.

This is **source-level evidence**, not a controlled network benchmark. No device thermal, first-frame, or frame-timing measurements were available; do not claim quantified speed-ups until field logs confirm them.

## Verified causes / risks

1. **Search serialized two independent fallback requests.** `YouTube.searchSummary()` awaited a Songs search, *then* Albums search when the main response included <=1 shelf. The UI got nothing until the first response **plus both** supplemental responses completed. Removing the fallbacks made Search fast but caused the observed *Top Result only* regression.
2. **Search parsing and artist deduplication were on the UI collector context.** It performed network suspend calls but processed returned bodies and summary projection in the ViewModel's Main dispatcher.
3. **ArchiveTune loads search summary and category filters concurrently** in its public `OnlineSearchViewModel`. Its architecture doesn't prove a specific response-time measurement but does explain why it needn't serialize all result categories.
4. **Most Capsule track/album thumbnails disabled Coil crossfade** explicitly in `ItemThumbnail` with `.crossfade(false)`, overriding the global `ImageLoader.Builder.crossfade(true)`. Memory-cache hits can also lack native crossfade; merely toggling the global flag cannot guarantee an entrance animation.
5. **Collection art attempted a rewritten 960px CDN URL before displaying anything**, waiting up to seven seconds per candidate before trying the original URL. This serial path can keep an otherwise cached low-resolution cover hidden behind an unavailable high-resolution request.
6. **Online playlist metadata held back page one** by calling `YouTube.playlist(...).completed()` before publishing the ViewModel state. The renderer could not show the first valid title/cover/tracks until all continuation requests completed.
7. **Albums duplicated requests when the first browse response already contained a complete track shelf.** They attempted a separate playlist browse, with additional continuation traversal, rather than reusing the album response. Their Room updates were not awaited before declaring successful loading.
8. **Collection title ownership used opposing alphas** (hero text alpha `1-handoff`; compact text alpha `handoff`). The two text layers made the lettering visibly gray during the last portion of scroll even though they shared a geometrically aligned endpoint. The photograph's scroll fade is a distinct concern and must be retained.

## Changes in the isolated branch

- Search renders the primary response on receipt, launches *both* conditional supplements in parallel, preserves Songs and Albums in the final result, and marks that result complete only after merging. Network/parsing and card projections are moved off Main. Switching tabs no longer treats an unfinished Top-only response as final.
- ItemThumbnail, artist cards, local covers, playlist preview and detail portraits have one bounded 180ms decode-driven entrance; no perpetual shimmer. Google CDN resize failures fall back to the original thumbnail.
- Detail portrait displays the original feed URL in parallel with the larger candidate, preserving crop/palette/matte. It avoids redundant preview decode when original and full URL are equal.
- Album/playlist layout retains the original photo dissolve but uses one opaque measured title paragraph through handoff; the control capsule is raised relative to the cover, and loading actions mirror their real disabled buttons instead of five identical play icons.
- Online playlists publish page one immediately and continue on demand. Albums reuse a complete inline shelf when present, await DB transaction, and preserve cached metadata on network errors. Audio/video stream resolver, Android package identity and download manager are untouched.

## How to validate on a device (with debug logging enabled)

Record a cold start and a warm revisit with the *same* query, album and playlist. Compare:

- `SearchLatency primaryMs=...`: first useful summary published; `completeMs=...`: all needed shelves received.
- `DetailLatency playlistFirstPageMs=... rows=... more=...`: initial playlist metadata; `albumBrowseMs=...` and `albumReadyMs=...`: network/catalog vs Room commit.
- `ArtworkLatency detailPreviewMs=...` / `detailFullMs=...`: first usable photo and the optional 960px refinement; values may differ due to proxy, CDN and cache.
- Record actual screen-to-visible timing, route/network type, cache state, and Android system-level CPU/GPU/battery thermal trace for a comparable idle/playback session.

Sensitive query strings, artwork URLs, signed tokens and media IDs are deliberately excluded from these new timing logs.

**Acceptance:** all search shelves returned, album/playlist card and controls remain usable under slow network, images never remain blank on a failed upsized variant, title stays solid on every frame, photo darkens exactly as before, and offline audio/video behavior is unchanged.
