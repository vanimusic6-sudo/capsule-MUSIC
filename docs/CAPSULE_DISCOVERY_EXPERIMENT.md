# Capsule Discovery — Audius + Openverse experiment (2026-10-23)

## Scope

Discovery remains **OFF by default** in Settings → Content. It is isolated
from the stable YouTube audio resolver, download manager and video player.
Turning the toggle off hides its chips/shelf and rejects experimental playback.
SoundCloud, Mirlo, and Internet Archive adapters are absent; do not revive them.

## Catalogs

- **Audius**: the official public Audius catalog and HTTPS stream URLs.
  Respect `is_streamable` and access flags. Keep this source independent.
- **Openverse**: official anonymous `GET https://api.openverse.org/v1/audio/`.
  Indexes openly-licensed audio from multiple *original publishers*, **including
  Jamendo**. There is deliberately **no separate Jamendo API connection, account,
  client ID or scraper**. The recommendation lane scopes `source=jamendo` to
  prioritize real independent songs over incidental sound effects.
  Openverse can show more indexed sources on a user-driven text search.

Openverse result mapping: `id`, `title`, `creator`, `source`,
`creator_url`, `foreign_landing_url`, `url`, `thumbnail`, `audio_set.url`,
`duration` (milliseconds), `license` and `license_url`.
For user attribution the original creator/source and license link must remain
accessible in the in-app menu. We accept HTTPS original audio URLs for playback
only with explicit Creative Commons license metadata; index inclusion by itself
is not a rights guarantee. Never download/redistribute assets for offline use
from this experimental integration. Remote publishers can restrict streaming.

## Operational expectations

- Respect anonymous API throttling, pagination and no-scraping rules. Use a
  short rate gate on calls, 20 records per page and a 15-minute nonempty cache.
- Recommend diverse candidates every UTC day (Audius ~55%, Openverse ~45%
  when both are available); fill missing provider quotas from the other provider.
- Audio remains with the original publisher, not proxied through YouTube.
- Audius and Openverse fetch in separate lanes, so timeout of one source
  never blocks the other. Player and search should display provider failure
  without falsely saying the catalog is empty.
- Artist pages on Openverse query the indexed `creator` field, optionally
  scoped to a provider (e.g. Jamendo), and expose the original profile URL.
- Restrict requests to the official Openverse and Audius JSON APIs; user
  access to provider sites is through ordinary external HTTPS links.
- Live network access from the build environment, playback rights for specific
  titles, rate-limit behavior in different regions, and device playback still
  require on-device smoke testing. Do **not** merge into stable untested.

## Long Forgotten shelf

The optional home screen presents a **separate** "Long forgotten" (Давно забытое)
shelf. It uses only the official Openverse anonymous audio API, with historical
and niche music query rotation against `wikimedia_audio` first and `freesound`
as an optional musical fallback. Both are among Openverse's currently listed
audio sources, alongside `jamendo`. **Internet Archive is NOT an indexed audio
source in Openverse at the time of this change.** No direct Internet Archive,
Freesound or Wikimedia client key, scraper or audio resolver is added.

Curated results exclude short language pronunciations, generic effects,
missing CC license and HTTPS audio, and duplicated artists/tracks.
The input and ranking rotate daily and are cached, preserving anonymity and
avoiding unnecessary calls. Failure never affects the main recommendation
shelf or normal audio playback.

**No exclusivity claim:** Openverse offers no evidence about whether any
recording exists elsewhere. "Long forgotten" means niche or archival
discoveries, not proven unique recordings, verified release dates, or absence
from mainstream services. Show upstream creator links and license details.
