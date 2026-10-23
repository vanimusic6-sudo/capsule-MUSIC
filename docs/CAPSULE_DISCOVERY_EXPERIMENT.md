# Capsule Discovery — independent music catalogs (2026-10-23)

## Home selector and reset

The Home header has an independent source picker. **The initial choice is only
YouTube Music**. Selecting a provider saves it without overwriting global
player/extractor settings. A separate **Reset to YouTube Music** item restores
the default Home (it does not reset experimental search opt-in). The
**All available sources** choice remains opt-in, not a first-install default.

Providers in the experimental branch:

- **YouTube Music:** existing personal recommendations and playlists.
- **Audius:** official anonymous API plus independent streaming (confirmed by user).
- **Internet Archive / Netlabels:** official archive.org/advancedsearch.php and
  archive.org/metadata/{identifier} catalog, with audio from official download
  URLs. Require explicit Creative Commons/public-domain licensing per item.
- **Wikimedia Commons:** native official MediaWiki imageinfo metadata and file
  URLs instead of Openverse's stale audio index.
- **Jamendo via Openverse:** index-only unless the HTTPS original stream
  passes a bounded Range GET returning real audio content. A dedicated
  official Jamendo API integration requires a developer client_id.
- **Freesound via Openverse:** best-effort music-only subset; many public
  results are effects or cannot be played directly without provider tokens.

## Why Openverse looked playable but wasn't

The old code marked any HTTPS link from the Openverse index as playable, even
when the upstream Jamendo/Wikimedia/Freesound CDN returned 403/404, HTML or
invalid redirects. filter_dead=true checks index health, not the exact
Media3 streaming request from a user's device. Now limited, cached Range GET
checks are required for Jamendo/Freesound Openverse links. Broken entries
remain original-site links in search but do not become dead Play cards at Home.

Commons now independently searches music-like audio with MediaWiki, verifies
file MIME and license, and obtains direct HTTPS Wikimedia file URLs.
Freesound's indexed catalog includes many sound effects and should not be
presented as a reliable streaming music subscription service. A Jamendo
client ID must be provisioned separately for a robust official API client;
the public developer test ID is not appropriate for shipping.

## UX and isolation

Catalog shelves use the existing HomeLoadingShimmer skeleton, stream in
individually and preserve visible content during refresh. Failed catalog
sections disappear without any persistent error text. If all selected
external catalogs fail, YouTube Home is shown as a temporary in-memory
fallback without overwriting the saved source choices.

No changes to the stable YouTube streaming engine, cipher/quarantine paths,
video subsystem, or private accounts. Public catalog HTTP requests observe
Capsule's network proxy setting.

## Limitations

The presence of an indexed file and a claimed CC license cannot prove that
the uploaded media is appropriately licensed or will remain online. Actual
device playback and region-specific access still require testing. No
claim of 100 percent universal availability is made for Internet Archive,
Wikimedia, Jamendo or Freesound.
