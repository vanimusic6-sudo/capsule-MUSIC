# Capsule Discovery — selectable Home catalogs (2026-10-23)

## Home source switcher

The Home greeting's circular profile control is a **source selector on Home
only**; account access remains in the menu and through Settings, and other
tabs retain the prior account control. Choices persist in DataStore under
`capsuleHomeFeedSourcesV1`. The default selection includes YouTube and all
current experimental providers. Experimental network traffic is still disabled
until the user explicitly opts in via Settings → Content or selects one of the
external sources in this picker.

The source picker offers:
- YouTube Music (existing Home recommendations, listening and releases).
- Audius (official public API and the separate official streaming endpoint).
- Jamendo **via Openverse**, no separate Jamendo Client ID.
- Wikimedia Commons **via Openverse**.
- Freesound **via Openverse**.

Each selector entry is a **source of the Home catalog**, not an audio-extractor
fallback. Removing YouTube from the selection hides YouTube-powered Home
sections, but never unplugs the actual playback engine, quarantines or queues.
No changes to app-wide search source priorities.

## Discovery curation

The old "Long Forgotten" / "Давно забытое" shelf is removed. Every opted-in
source contributes to **one** "Beyond the familiar" / "За пределами привычного"
row on Home. Audius and each selected Openverse upstream are fetched
independently. Rankings prioritize valid streamable URLs, likely musical content,
sensible music duration, genre metadata, artwork and the listener's artist
hints. Deduplicate normalized artist/title, maximum two per artist, rotate tie
breaks by UTC day, and cap per-upstream dominance when there are several
upstreams. This is transparent heuristic quality scoring, **not** platform
listener votes, human ratings, exclusivity or proven obscurity.

Failures show an empty/error state with Retry rather than removing the entire
section. An unreachable Openverse API cannot hide functioning Audius tracks.
Each upstream's response is cached for 15 minutes. API clients respect
anonymous Openverse limits and do not scrape or register on behalf of users.

## Current limitations

Openverse currently indexes the audio catalogs of Jamendo, Wikimedia Commons
and Freesound; Internet Archive audio is **not an officially indexed source**.
Openverse may be unavailable in some networks, and individual publisher media
links may stop working. The app supplies upstream and Creative Commons metadata
but cannot independently verify all uploader rights. Never silently play
unlicensed content. Only optional sources are on this development branch and
the stable YouTube audio/video behavior is unchanged. Device playback,
regional availability and real recommendation taste quality still require
live smoke tests before merging.
