# Capsule Music — Motion, header and thermal review (9496.mp4)

Date: 2026-10-18. Recording: 9.62 seconds, 592x1280, approximately 60 frames/s; reviewed as contact sheets with 4 samples per second.

## 1. Upper greeting / account / Settings bar

**Seen:** A short scroll burst shows the greeting disappearing, reappearing and partially painting over underlying feed content.

**Why:** RouteWithScrollAwayHeader previously launched a 220ms reveal as soon as available.y > 0, even for tiny overscroll rebounds. New negative consumed.y cancelled it instantly. Alternating small positive and negative signals made the upper bar flicker. The bar itself was transparent, letting list labels and covers paint through it.

**Repair:** Require 12dp of genuine unconsumed user pull at the TOP of the list, or 36dp of positive unconsumed top-reaching fling. Ignore tiny recoil and sub-1.5dp negative backlash; once the fling reveal begins, only a new finger drag can interrupt it. Keep a single finite reveal coroutine and route-scoped scroll connection. Bar now paints the existing Material surface behind its controls. Motion reads state only in placement through Modifier.offset.

**Caveat:** a programmatic scrollToItem(0) without any nested-scroll events may not trigger a reveal. Device review should verify these cases; if needed, expose actual LazyListState top signals rather than guessing from scroll direction.

## 2. Settings overlap

Two NavHost page trees were fading through each other throughout 235-255ms. Keep the half-width ArchiveTune-inspired travel, but make the next page start at 82% opacity and become opaque in 110-130ms. Departing page's labels disappear in 85-110ms. No new effects or extra animation owners.

## 3. Toggle feedback

Earlier CapsuleSoftSwitch spring was .94 damping and 220 stiffness, and its position was clamped to 0..1, suppressing all bounce. Use .72 damping / 680 stiffness, with bounded +/-7.5% displacement (~1.5dp). Draw subtle horizontal thumb stretch and vertical compression during travel based on SAME spring, with no additional clock or remeasure. Keep no checkmark, 48dp hit target, Role.Switch accessibility, Material-proportioned track.

## 4. Popup and confirmation exit

Shared choice/priority dialog used a mostly generic fade and 6dp travel. Developer warning and logout used raw AlertDialog, disappearing immediately.

Shared close now uses one 185ms exit progression: 2.5dp initial physical retreat, then 21dp gentle downward movement, at most 2.2% scale change, and delayed fade to EXACTLY zero before the Dialog is removed. This is NOT opening reversed. Developer-warning and logout confirmation now use DefaultDialog, with side-effect actions delayed until the exit finishes. Existing approved soft opening spring remains.

## Energy / thermal

All new movement values are sampled in graphicsLayer, Canvas or placement offset; no ongoing animation when idle, no blur, no new full-frame surface or per-frame layout measurement. Opaque header is only a narrow region; avoids ghosting without a blur pass. Two simultaneous Settings NavHost pages still have nonzero cost during the bounded transition.

Verification required on actual HONOR at 60/90/120Hz with rapid Home scroll, touch and fling to top, multiple tab switches, double-toggle, dialog Back, priorities drag and ongoing audio; use Perfetto FrameTimeline and thermal/power data to quantify actual savings. GitHub build success is NOT proof of cooler operation.
