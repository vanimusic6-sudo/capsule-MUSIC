# CAPSULE DISCOVERY — experimental catalogs (2026-10-22)

## Scope and kill switch

This package replaces **SoundCloud**, not the stable YouTube Music player. The deleted
SoundCloud catalog/extractor, download manager integration, profile/playlist screens, media
factory, link parser and tests must not be restored.

`Settings → Content → Rare music sources` is **OFF by default**. When off:
- no Audius, Internet Archive or Mirlo requests are initiated by search/home;
- source tabs and discovery shelves are hidden;
- the Audio service rejects experimental queues/media sources and stops an external track
  if the setting is turned off during playback;
- the existing YouTube network resolver, quarantine, prefetch and video routes stay unchanged.

The first version is intentionally separate from the default "All" search and the
regular recommendations. It should **not** contaminate familiar music discovery.

## Provider boundaries

| Provider | Discovery | Playback | Notes |
|---|---|---|---|
| Audius | Read-only official API | Official HTTPS `/tracks/{id}/stream` | Subject to provider access flags, rate limits, redirects |
| Internet Archive | Advanced search + item metadata | HTTPS downloadable item audio | Only items exposing explicit Creative Commons licence URL are offered |
| Mirlo | Opt-in Canimus federation catalog | External source page **only** | Mirlo requires developer API key + listener-authorized stream token. Never bypass permissions |

Each source gets an adapter and a distinct `capsule-open:<source>:<id>` identifier.
URLs are never processed as YouTube video IDs and never enter InnerTube/audio CDN recovery.
Current experiment disables offline download for these external tracks.
Music metadata retains the source page for attribution. Not all works in the Archive
are appropriately licensed; explicit rights may be incomplete even with tags.

## Recommendation algorithm (v0)

**Core principle:** serendipity with a minimum quality floor.

1. Seed with at most two artists from the existing taste suggestions (or no seed on a
   fresh install); request Audius underground and small related searches.
2. Fetch a bounded sample of Archive netlabels and Mirlo opted-in artists.
3. Drop empty title/artist, recordings longer than 20 minutes, duplicate track IDs
   and duplicate normalized `artist:title`.
4. Shuffle deterministically per UTC day so the carousel changes without random
   recomposition flicker.
5. Allocate initial provider quotas around 45% Audius, 40% Archive, 15% Mirlo,
   maximum two tracks per artist.
6. If a provider is unavailable, fill remaining slots from functioning sources.
   Do not fail the existing home screen.

The quota is a **discovery mix**, not an objective measure of song quality or a trained
similarity model. There is no telemetry/remote user profiling in this v0.
Source chips offer an exact-source search; experimental local filters include length,
date and selected genres. If a provider does not expose corresponding metadata, the
filter will not invent it.

## Progressive evaluation before default inclusion

- Verify real tracks in Russia and on mobile networks; captive/walled networks may
  cause timeouts. Protect main feed scroll FPS and audio continuity.
- Check Audius rate limiting and 302 redirect expiry; handle locked/premium tracks.
- Improve Archive attribution, restrict query syntax and show per-item rights.
- Request Mirlo key directly from Mirlo and implement authorized stream tokens,
  or continue opening Mirlo pages externally.
- Add playlist pages, pagination, error/refresh UX and cancellation tests.
- Design opt-in feedback only once there are listeners: explicit "more like this",
  "less like this", skip/finish signals with local retention and opt-out.
- Explicitly test toggle during search, recommendation loading and playback, and
  regression test YouTube audio/video/quarantine.

This is a **draft experiment**, not a production API integration guarantee. A debug
APK build and device smoke tests are mandatory before merging into the stable branch.
