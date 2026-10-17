# Capsule Music — settings, chrome and modal motion audit

Date: 2026-10-17
Scope: **only navigation/header/settings dialogs**. No playback, video extraction, player gradients, image sampling or settings defaults touched.

## Ruthless review of prior implementation

### Header flash was a STATE BUG, not an artwork bug
- `HomeScreen.kt` renders modern greeting + guest sun/moon + settings action inside the Home LazyColumn.
- `MainActivity.kt` also builds `StandardHeaderTitle` for Library, History, Stats. It explicitly passed `showHomeGreeting = currentRoute == Home`, which is **always false** in that block, forcing the legacy app-name title and a plain "C" placeholder. That explains the Library/non-Home brand mismatch.
- The outer Scaffold's `shouldShowTopBar` was a mutable remembered boolean set from `LaunchedEffect(navBackStackEntry)`. During any navigation it held the **previous route's** visibility for at least one composition/frame while NavHost had already switched. This exposed the old header momentarily during opening settings, switching tabs and search.
- The search overlay is itself a search bar; it is not supposed to contain an additional greeting title over the input. However the header underneath must not revert to the old brand while search opens/closes.
- Repairs: the `StandardHeaderTitle` can now only render the greeting, independent of route; signed-in avatar fallback is person icon, not "C". The topBar visibility is a synchronous expression of the active route and search state. No `LaunchedEffect`, no remembered stale title/visibility and no per-frame clock work.

### Settings root entrance was not coming from ABOVE
- Previously: a +X shift (88% of width) plus 0.86 scale, pivot at (1, .10), no Y movement whatsoever. The user asked for an arrival **from the settings control at the top-right**, not just a small tilt.
- Changed: +66% viewport width AND -27% viewport height; starts at 80% size, pivot at top-right. A matched spring pair with the same stiffness/damping controls both position and scale; visual landing is softly damped. Pop Back uses reverse vector and slightly quicker damping.
- This still is *not* a pixel-accurate sharedBounds morph anchored to the icon's measured on-screen bounds. That would require a NavHost/SharedTransitionLayout integration across both screen trees; do not falsely represent this approximation as the true morph.

### Nested settings felt stiff/ragged
- Previously: full-screen push `spring<IntOffset>` of stiffness 400 and damping 1. Two large screens move the whole width on a tight, harsh curve.
- Changed: 235 stiffness, 0.98 damping, exactly the same spec for entering and leaving page. No text scaling or transparency between subpages. One NavHost owns their movement; `CapsuleRouteSurface` deliberately does not animate them again.
- Keep other app destinations instant: previous global NavHost animations caused destroyed NavBackStackEntry/ViewModel crashes with rapid tab switching.

### Popup lists were inconsistent and layout-heavy
- Standard/Action/List dialogs had a tiny scale-only entrance with no direction or visual approach. Some settings warnings used raw AlertDialogs, without any matching entrance.
- Unified helper now animates ONE spring progress, sampled exclusively by `graphicsLayer`, and derives scale (93.5%→100%) and bottom-to-top rise (16dp→0). No layout updates per frame, no alpha/blur overlays, no independent animation clocks.
- Add helper to developer warning and sign-out AlertDialogs. Native Dialog dismissal / button action semantics remain unchanged (instant exit for buttons); the next iteration should use explicit retained visibility if a genuine reversible exit is required.
- Selection list Dialog previously had no viewport cap. It now bounds scroll content to available height; long choices scroll inside the surface rather than push it beyond screen.

### Energy analysis
- All new continuously changing values are sampled in draw/placement layers. A finite spring runs only while a visible transition is active.
- **No** blur shaders, independent per-row clocks, infinite transitions, frame delays or timer polling were added. The greeting's clock updates at day-part boundaries only.
- Priority-order drag used to read its animated elevation as a value passed to Material Surface, recomposing the whole row on every shadow frame. Now both lift elevation and scale are sampled in one graphicsLayer, while Material Surface shadow is static zero. The keyed LazyColumn placement animation is still responsible for neighbours.
- NavHost temporarily keeps **two settings pages** alive for nested transitions. The worst-case cost and measured dropped frames on a HONOR device remain **unverified**; performance needs a Perfetto/FrameTimeline recording under fast Back/re-entry and scrolling during page travel.
- Avoid changing unrelated `Material3SettingsGroup.animateContentSize()` or playback animations based only on a guess.

## Tests / sign-off
- Header: no old app_name / C fallback, direct current-route topBar state, guest sun/moon all main screens.
- Motion: animated routes gated to settings, matched opaque nested push, root starts above and right and scales subtly; no second route layer.
- Popup: finite draw-phase arrival, constrained list height, settings warning and confirmation receive same helper.
- CI: lint, UI structural and navigation regression tests, Debug APK and signed Release APK.
- Unverified: recording on HONOR at 60/90/120Hz, first frame of search expansion, predictive Back, large accessibility font, rotation, real battery impact.
- Do **not** merge without device review; PR remains Draft.
