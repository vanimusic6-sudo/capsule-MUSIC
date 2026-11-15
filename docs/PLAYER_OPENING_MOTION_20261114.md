# Player opening: moving Mini and direct page handoff

## Review of 12822.mp4

The 5.80s recording was inspected as full frames at eight samples per second. The user likes the
current speed but rejects the flat artwork-palette panel that expands behind the stationary Mini.
That panel looks like another object, particularly during short openings and reversals. The new
request supersedes the earlier preference for a stationary Mini.

## One trajectory

BottomSheet delegates only the main player to PlayerWindow; generic sheets remain unchanged.
Opening stiffness 650, closing stiffness 850 and critical damping 1 are unchanged. The existing
anchored spring retains velocity on reversal and respects the system animation duration scale.
There is no new timer, easing or independent source animation.

The real Mini follows the growing window's upper edge, using the same progress and lambda
placement of its complete layer. Its size and artwork proportions stay constant. Closing reverses that trajectory
back to the dock. Independent downward Mini dismissal remains available.

The synthetic source-material layer and its compactBackground API are removed entirely. There
is no stretched palette panel or second glass backdrop. Mini fades directly into the actual full
page over progress 0.015–0.25 with complementary opacity curves. The page's existing uniform
0.94–1 scale, native rounded outline and focus scrim remain. The full page is measured at its final
size and transformed as one object; photographs and video are not stretched independently.

## Lifetime, input and energy

The real Mini and destination keep the owner-crash hotfix: Mini's heavy controls retire only at
the settled expanded anchor; destination survives all active closing frames, including transparent
ones. Accessibility modifiers remain stable and use discrete visibility gates. Covered Mini/app
painting stops, and invisible decorative clocks stop without detaching live nodes mid-flight.
Activity stop completes the requested anchor. Modal hit shielding, Back and near-dock reopening
remain covered by regression tests.

No screenshot capture, bitmap copy, new image request, decoder, blur, sampling timer, infinite
transition, FPS cap or quality reduction is introduced. The replacement removes a stretched GPU
layer. Source coordinates are read in lambda offset (placement only, no remeasurement); destination
transforms are read in graphicsLayer. Native pixels also check that Mini paints at its moving
position and leaves no clipped strip behind in its old dock. Audio/video playback, palette extraction and final screen designs are unchanged.

## Verification

Native tests cover direct page blend pixels without a palette bridge; complementary handoff;
Mini travel with constant size on both directions; shared geometry; fixed page measurement and
aspect; rounded finish; first-frame response; reversals; repeated requests; input, Back, Activity
stop and covered idle drawing. API 37 accessibility tests keep actual enabled-service node queries
and stress host removal/restoration. Build, lint and exact CI results are recorded in draft PR 100.

A recording and native CI cannot establish physical-device smoothness, TalkBack behavior, heat or
battery use. No physical HONOR / 60–120Hz measurements are claimed. Appearance still needs the
user's phone check. See PLAYER_WINDOW_OWNER_CRASH_20261115.md for the earlier crash analysis.
