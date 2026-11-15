# Player opening: measured shared container, artwork and title

## Intent and field recordings

12824.mp4 showed the cover being sliced by a growing mask around a separately centred/scaled full page. The subsequent rigid-slide revision in 12828.mp4 lost the intended relationship with Mini entirely; a large poster also remained below Mini during closing. Both are rejected approaches.

The requested interaction is a container transform: the actual Mini card expands into the full-player surface. The same thumbnail moves and grows into the actual full artwork slot; the same title moves into its final text slot. Controls that have no shared counterpart appear once space exists. Closing follows the same bounds backwards. Tapping has no decorative bounce. The original 650/850 critically damped anchored spring remains the position/velocity owner.

## Shared-element implementation

Evaluated Compose SharedTransitionLayout/sharedBounds/sharedElement and their overlay/clipping rules. Capsule already has an interruptible anchored spring that owns Mini dismissal, Back, visibility, Activity stop, duration scale and Android 17 lifetime protections. Rather than add an independent transition or a per-frame seek coroutine, PlayerSharedMorph matches real measured source/destination endpoints by part and media ID and renders their retained GraphicsLayer display lists on that existing progress. No snapshot bitmap, screenshot, image request or extra decoder is created.

- Surface bounds are measured from the real inset MiniPlayerSurface, with a generic-sheet fixture fallback. All four edges expand to the screen bounds together. The real destination backdrop is clipped to this container; the short source material remains undistorted and retires early, never stretched into a palette panel.
- Artwork endpoints cover the real circular Mini image and the final audio artwork slot in Light/Cosmo. Immersion registers the actual photographic slot including its feather mask. Photographs use uniform fill/crop and an interpolated circular-to-destination corner shape, never anisotropic stretching. Active native VIDEO is not recorded or duplicated; its existing render surface remains in the actual page.
- Title endpoints retain their actual text layout. Font-size interpolation and measured FirstBaseline alignment keep source/target glyphs at the same size and baseline while weight/colour cross over. Line-box scaling is not used: different line-height/font-size ratios otherwise create a doubled line.
- A real decoded/opaque-painter readiness signal retains the source photograph until the full-quality target is ready. A separate artwork-group recording suppresses the empty full-size matte/outline behind the moving photograph.
- The native endpoint paints in its original slot only outside the finite morph. During the morph it records its own current display list and the shared overlay paints one moving artwork/title. Matching requires equal media IDs, preventing unrelated tracks from being joined.
- The rest of the page retains one final measurement and appears over progress 0.22–0.72. Its recorded body visits shared children even while its own opacity is zero, so the cover never waits for controls to appear.

## Lifetime, input and invalidation

Mini and destination keep stable lifetime through the complete finite motion; destination retires after the close actually settles. Overlay references unregister before node/layer disposal; only source bounds/identity may remain cached. Stable semantics gates hide covered content. The actual moving compact hit region supports reopening and compact-only dismiss without leaving an invisible hit target at rest. Covered decorative clocks stop without FPS caps.

All transition painting belongs to one full-viewport clipped parent. Neither page nor artwork translates outside that owner's invalidation bounds. This removes the structural cause of the poster strip below Mini and navigation. Layer capture records drawing commands only, with no backwards snapshot writes from draw and no private framework reflection. Audio/video services, artwork decoding, palette extraction and final player layouts are unchanged.

## Verification

Native Android rendering tests use matching thumbnail/large-cover inputs and locate actual artwork-coloured pixels at multiple progress samples. They assert real growth from the thumbnail, matching positions in both directions, constant photograph aspect, container clipping, and the absence of any enlarged poster after closing. Page measurement, native controls, moving-source hit-testing, rapid reversal, Back, lifetime, Activity stop and idle drawing remain covered. API 37 enabled-service tests also exercise matched display-list nodes during delayed semantics queries, lazy underlay changes, and host removal/restoration.

Exact CI/APK results are recorded in draft PR 100. Native CI is not a physical HONOR smoothness, TalkBack, heat or battery measurement. A phone appearance check remains necessary.

References:
- https://developer.android.com/develop/ui/compose/animation/shared-elements
- https://developer.android.com/develop/ui/compose/animation/shared-elements/customize
- https://developer.android.com/develop/ui/compose/graphics/draw/modifiers
- https://developer.apple.com/videos/play/wwdc2018/803/
- https://developer.apple.com/videos/play/wwdc2023/10158/
