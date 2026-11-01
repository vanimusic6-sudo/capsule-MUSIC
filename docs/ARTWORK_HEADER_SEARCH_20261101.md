# Artwork, collection headers, search and source cleanup — 2026-11-01

Review branch: `fix/artwork-header-search-cleanup-20261101`

PR: https://github.com/vanimusic6-sudo/capsule-MUSIC/pull/71

Base: `2409c2a7c515eb2f31ea52ac0241283f77f677aa` (DEV 319 playback/download fixes)

## Changes and causes

| User report | Cause in the previous code | Change |
| --- | --- | --- |
| Cover visibly changes composition | The preview could be replaced by a different YouTube poster variant; high-resolution conversion discarded the original Google crop flags; later metadata could change a visible source. | Keep crop flags and aspect when requesting a larger Google image. Keep YouTube poster variants unchanged. Lock the source after successful decoding, with track-scoped state and bounded painter retention. Immersive frame identity follows its sampled display image, not raw metadata updates. Failed initial requests can still acquire later valid metadata. |
| Caption jumps into toolbar and letters spread at the endpoint | A two-line 25sp layout was replaced in one frame by a one-line 22sp layout; their line bounds and first-glyph origins were not shared, and a scaled 25sp layout was replaced by a separately shaped 22sp layout. | Measure first-line origins and baselines, use matching zero letter spacing, keep both layouts on the same moving first-glyph/baseline trajectory, and complete the layout handoff continuously over the final scroll interval. Reverse scrolling uses the same progress with no extra clock. |
| Animation/function energy cost | Text alpha was read during composition; the cover reveal animated blur in composition; preloads enqueued work that outlived its track. | Read title/reveal values in graphics layers, use derived accessibility visibility, remove the additional blur pass, cancel suspending artwork preloads when track/foreground changes, and reuse compiled artwork-size regexes. |
| Search takes seconds | Any single-artist song was treated as incomplete, triggering a second Songs request and a queue request. The primary response waited up to 1.8 seconds for both. | Publish the primary response immediately; merge all artist credits already available in that same response by video ID. No speculative credit requests or late row-text mutation. |
| Gray feed covers during fast scrolling | Small thumbnails requested 512px sources regardless of their display budget; nearby shelves began loading only when composed. | Quantize source requests to displayed pixel sizes, avoid independent thumbnail fades, and warm up to three covers in each of the next two provider shelves with two concurrent requests. Cancel obsolete requests and pause prefetch while the app is off screen. |
| Remove Audius and restore account button | The header was a source picker, with source-specific API, queue, download and resource branches. | Remove the source/API/parser/icon/strings/search chip and source-only actions. Restore account navigation, the signed-in avatar and guest sun/moon. Parse old selections safely to YouTube. Keep existing local downloaded files and generic licensed catalogs. |

The Android playback/download engines and the scroll-gesture owner are preserved. The work does not change the UI palette or gradient extraction algorithm.

## Verification

Code revision under test: `068bbd5472dc8a0c01a47c250872a50777a6cea1`.

- Playback/extractor CI: https://github.com/vanimusic6-sudo/capsule-MUSIC/actions/runs/41273170411 — **667 app checks and 134 extractor checks passed**, zero failures/errors/skips in the XML reports.
- Focused cover geometry, title handoff, native glyph rendering and discovery cleanup: passed in https://github.com/vanimusic6-sudo/capsule-MUSIC/actions/runs/41273170424.
- Separate TEST APK build: https://github.com/vanimusic6-sudo/capsule-MUSIC/actions/runs/41273170372 — passed.
- Optimized DEV 327 build, R8 mapping, package and APK signature validation: https://github.com/vanimusic6-sudo/capsule-MUSIC/actions/runs/41273170369 — passed.
- Lint on the APK code: **0 errors**, 627 warnings and 25 hints. No reported warning points to an added source line.
- Full UI/regression and Universal Debug APK workflow: https://github.com/vanimusic6-sudo/capsule-MUSIC/actions/runs/41274123699 — **passed**. The combined UI/preferences/Home/player XML reports contain **423 tests across 58 classes**, zero failures/errors/skips. Search metadata, video classification/quality checks and the Universal Debug APK build also passed.
- Native title-rendering screenshots were reviewed at movement, handoff, pinned position and return to the hero. The first-glyph and baseline alignment is also asserted during the middle of the handoff.

The follow-up verification revision `9bb253cffb18c235e1346ef1188c3f7589190044` changes only tests and their workflow. Runtime sources match DEV 327. Legacy source guards now require Foundation gesture ownership throughout the pointer session and the shared draw-phase press feedback, instead of reintroducing the removed vertical-scroll lock. The workflow collects the existing 58 UI regression classes together and continues independent checks after a failed assertion so failures are not hidden by an earlier suite.

APK: `Capsule-Music-DEV-327-artwork-search.apk` (26,785,967 bytes), package `com.nikhil.yt.dev`.

- APK SHA-256: `ac2d8d6be159302f7228ee3c2b72de4f8ba58bf3638d3315090dff3c7c000422`
- Signer certificate SHA-256: `09e22d06b0a5221ab71bf631b4325d026d67736087678f43efe27965667c8e2f`
- CI verified the APK v2 signature. The extracted signer certificate fingerprint was also compared locally against the installed-version deliverable DEV 319 and matches. APK and artifact ZIP digests were checked before delivery.

The attached user recordings were inspected before implementation. Physical-device frame pacing, battery use, real-provider latency, TalkBack, large-font behavior, rotation, and 60/90/120 Hz presentation have not been measured for this revision. Unit rendering checks are not a substitute for those measurements.
