# Player layout and hidden-work audit — 2026-11-14

Scope: Light and Immersion player presentation, optional inline lyrics, playback-error presentation, visual asset lifetime and clocks. The accepted Immersion image-backed gradient and video colour extraction/filtering algorithm are preserved. No playback bitrate, buffering, animation frame rate or visible effect has been reduced.

## Changes

| Area | Finding | Change |
| --- | --- | --- |
| Retired design | Cosmo remained a runtime enum, renderer branch, toggle variant, exclusive control panel and resource label. | Removed the implementation and labels. Only the historical persisted spelling `SUPER` remains in a migration to Light. Existing Light/Immersion selections are preserved. |
| Light horizontal geometry | Artwork/control shell edges were 28dp in, metadata/progress 24dp in; inline lyrics followed a resizable artwork width. | Metadata, progress and inline lyrics now use the same 28dp column as the default artwork and transport shell. Custom cover resizing remains independent. Immersion keeps its 20dp column and removes the transport shell’s extra 4dp inset; landscape Light also avoids applying it twice. |
| Metadata action | A weighted action column varied with screen width, and a 28dp heart was placed off the common visual edge. | Both remaining designs use a fixed 48dp action column and a 32dp heart aligned to its trailing edge, vertically centered with the title/artist block. |
| Inline instrumental gap | Compact lyrics discarded empty pause intervals. | Reuse full-lyrics interval detection and the same bottom-to-top note glyph. Interpolate existing position samples; add no polling loop. Reserve two text lines, including at larger font scales. |
| Foreground return | Light discarded its entire canvas when the app stopped. On return, measured artwork limits and block positions were calculated again and placement springs restarted. | Retain the canvas and remembered measurements while stopped. Pause lifecycle collectors, requests and motion separately. Do not write normalized editor positions while the canvas is inactive. |
| Video lifetime | Retaining the canvas also requires explicitly detaching its native output while hidden. | Gate the existing output's `active` flag by actual visibility; release the native surface/capture listeners while retaining bounded still-frame/layout state. The window dimensions are unchanged. |
| Playback error | Light replaced the album artwork with an error card; other presentation paths did not share one owner. | One common dismissible dialog above the player, with retry/copy and scrollable details. Dismissal belongs to the exact media/error pair; backgrounding does not reopen a dismissed error. |
| Covered backgrounds | Ordinary wallpaper could still shade/advance beneath opaque lyrics or an expanded queue. | Skip covered drawing. Keep a shared clock only when a visible themed lyrics backdrop also needs it. Stop the root readout and lyrics work beneath a fully expanded queue. |
| Disabled animations | The comet's infinite `animateTo` loop had no duration-scale guard. A zero-duration tween can complete synchronously, creating a busy loop. | Suspend the cycle when system animation scale is zero and observe scale changes. Visible enabled motion retains its existing cadence. |
| Background loading | A retained Light loading spinner/cover retry could continue without visible output. | Gate the spinner, retry and cover request by visibility; cancel when covered/stopped. |
| Palette allocations | An unchanged scene/list was recreated on unrelated progress recompositions. | Remember the returned scene by its actual colour and immutable field inputs. |

## Existing controls inspected

- Root playback-position display sleeps when backgrounded, paused, dismissed or covered by full lyrics. The queue now also stops it.
- Video ambience is driven by real decoded-frame signals, with a bounded 48×28 readback buffer and the existing sample cap/filter. Hidden capture is disabled; there is no additional decoder or periodic wallpaper sampling timer.
- Full lyrics word/note motion is display-clock driven only while visible and advancing; line-only tracking sleeps until a timing boundary. Romanization is visibility gated.
- Artwork prefetch has a 420ms dwell, cancellation on track/foreground changes and bounded caches. No extra full-size bitmap is introduced for the compact note or dialog.
- Paused/background decorative clocks and the comet have explicit visibility gates. Audio/service work required for playback is preserved.
- Preference decryption already has a shared background producer/cache; the new migration adds no permanent task.

## Validation and limits

The player quality workflow includes the new migration, compact-pause boundary, large-font row geometry, fixed action width, retained inactive canvas and modal-error regression tests, plus existing graphics, layout, favorite, lyrics, prefetch, presence and idle-poll tests. Universal Debug, Android lint and the optimized signed DEV build run separately.

CI results are recorded in the pull request after completion. A source audit and Robolectric/native rendering checks cannot establish battery current, device temperature, real GPU time, or 60/90/120Hz perceived smoothness. Those require an Android device trace/recording. No physical-device thermal measurements were available for this batch.
