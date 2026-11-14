# Player window rewrite after reviewing 12804.mp4

## What the recording revealed

The 6.76s recording was inspected as full frames and 30fps strips around opening and closing.
At approximately 0.65–0.85s and 1.45–1.62s the old transition reveals/collapses a large cropped
photograph above Mini. It reads as a sliding shutter rather than a shared container. App labels
remain visible through that photograph during the outer-card fade. Mini, the photograph and the
card boundary therefore have conflicting visual ownership. Repeated short openings amplify this.

The recording establishes appearance, not physical-device frame times, tap latency or battery
consumption. A screen recording alone cannot separate UI stalls from capture cadence.

## New ownership

BottomSheet now delegates the main player to PlayerWindow. Generic menus/queue sheets keep
their old implementation. Old main-player layer alpha, parent translation and anisotropic
artwork deformation have been removed from the generic renderer.

- One anchored, critically damped spring drives window bounds, source handoff, page position and
  focus. Opening stiffness is 650, closing 850, damping ratio 1. Velocity survives reversal. A
  short reversal does not replay a complete fixed 460/360ms animation. Android duration scale is
  still handled by the existing Compose animation clock.
- A native rounded outline interpolates the actual Mini rectangle into the window. The shell
  itself never dissolves over the app. Corners retain their radius and finish outside the viewport.
- The source material is the selected Mini background, using the existing artwork palette and
  glass backdrop. Only this Mini-height material layer stretches to fill the growing shell. It has
  no decorative clock and is removed once the page is opaque. Glass seals into an already sampled
  artwork tone before page content is visible; there is no generic grey replacement page.
- Full-player background, artwork, video and controls form ONE stable measured page. It moves
  with the shell's centre and receives one uniform 0.94–1 scale, preserving aspect ratios. It
  dissolves inside the source material over progress 0.18–0.66. This removes the bright photo
  fragment near Mini and prevents the page fade from exposing app labels behind the photograph.
- Mini stays at its dock. Its foreground yields over progress 0.015–0.20. On closing its upper edge
  reaches towards the incoming window by at most 4.5% of its height, with a fixed bottom and no
  horizontal motion. This follows the same anchored velocity and has no separate spring/timer.
- App navigation is stationary under the window. A 20% scrim follows the same progress, rather
  than introducing another navigation animation. Modal hit shielding stays active for the trip;
  Mini can reopen the window near its dock. The page survives a near-Mini reversal.

## Resource review

No screenshot capture, bitmap copy, new image request, decoder, sampling timer, full-page blur,
infinite transition, FPS cap or quality reduction is introduced. The material is measured at Mini
height and transformed on the GPU. The real page is measured once and transformed rather than
remeasured on every frame. Its draw pass and decorative clocks are suppressed while its opacity
is zero. Covered source material is unmounted; covered app drawing stops. Mini controls are
prepared only in the short final closing region, with their background clock stopped until visible.
The existing Activity-stop handler completes the requested anchor without hidden animation frames.

Audio/video playback, palette extraction, immersion composition and final player layouts are
unchanged. The source bridge reuses the same current palette; it does not extract another one.

## Verification

Native tests cover material opacity before page reveal; exact source/page blend pixels above
Mini; uniform page aspect and fixed measurement; source bounds and rounded finish; stationary Mini
and bottom-anchored stretch; independent downward Mini dismissal; first-frame response; repeated
requests; reversals; early Mini reopening without a remount; modal input; Back registration;
source/app idle drawing and Activity-stop behavior. The actual Android builds, lint, all regression
results and APK provenance are recorded in draft PR 100.

No physical-device 60/90/120Hz trace, TalkBack session, thermal or battery measurement is claimed.
The resulting DEV APK still needs an appearance check on the phone.
