# Player opening: a rigid opaque page from the Mini dock

## Recording 12824.mp4

Reviewed the 4.948s, 592x1280, 60fps user recording at 12 samples per second, with full frames retained. Across repeated openings and closings, the former transition exposes a horizontal slice of the cover and brings controls into view early. During closing, the player becomes a floating short card with a changing cover crop. The previous blend also lets the home page show through the destination during handoff.

The root cause is geometric: a changing-aspect window clips a separately scaled page, whose centre follows the centre of that window. Cover, controls and shell therefore have different apparent trajectories despite sharing one progress value. Mini movement alone does not fix it.

## Replacement

Remove the changing-height window, page zoom, centre alignment, and destination alpha transition. Measure the actual page once at final size. A single graphics layer translates that page vertically from the Mini dock to its final origin; its full height extends below the viewport during travel. Cover and controls retain their actual size and local coordinates. The real page stays opaque, so home text/cards do not ghost through it. Only native rounding follows the top-edge attachment.

The Mini uses the same top-edge position and fades only its own controls over the opaque destination during progress 0.02–0.18. Its whole placement/alpha layer moves together. On close the same geometry reverses without creating a short floating window. Opening stiffness 650, closing stiffness 850 and critical damping 1 remain unchanged. Anchored motion preserves current position and velocity on reversal and respects system duration scale.

## Lifetime, input and energy

Retain the Android 17 owner-crash protections: keyed destination, stable semantics modifiers, Mini children retained through active motion, and destination retained through all closing frames until settling. Input switches discretely from Mini to destination; the underlying app remains shielded through the transition. Activity stop completes the current target. Independent Mini dismissal remains available.

No new bitmap, decoder, image request, blur, timer, FPS limit or rendering-quality reduction. Translation is read in graphicsLayer, Mini placement in lambda offset. Covered decorative work stops. Audio/video playback, artwork processing, palettes and final player layouts are unchanged.

## Verification

Native graphics tests assert actual opaque page pixels over the green app, the absence of a vacated Mini strip, full-height coverage down to navigation, constant page/artwork size, and a fixed artwork origin relative to the moving page. These replace assertions about the discarded centred-window effect. Retain reversal, repeated requests, Back, taps, modal shielding, source lifetime, idle drawing, and API 37 enabled-service accessibility stress tests. Exact CI and APK provenance are recorded in draft PR 100.

Native CI is not a physical HONOR smoothness/heat/TalkBack measurement. The new APK still needs a phone appearance check.
