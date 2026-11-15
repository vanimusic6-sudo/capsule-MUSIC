# System artwork, like actions and energy-gating review

## Reported symptoms

On HONOR, first-session artwork reaches the native media player/Magic Capsule but subsequent
artwork stops updating while title/artist still update. Like actions also intermittently stop in
both Mini and full player. These field symptoms are not reproduced on a physical phone here.

## Concrete failure paths corrected

- MusicService used one plain Job for main/IO service work, and its lazy CacheBitmapLoader
  captured that main scope. A failed CoroutineScope.future image request propagates failure to
  a normal parent Job and cancels its siblings. New like tasks can then be immediately cancelled,
  while ExoPlayer callbacks still update text. Recreating service scopes also leaves the lazy
  image loader holding its original cancelled owner. This is a concrete shared failure path
  consistent with both reports, not proof of the exact OEM-side sequence.
- Service sibling work now has a SupervisorJob. Native artwork has its own service-lifetime
  supervised scope, explicitly cancelled on service destruction. CoilBitmapLoader also isolates
  each Future under a supervised child of its caller, so it is safe with a plain external Job.
  Cancellation still propagates from service destruction and does not retry or report an error.
- Like/library tasks use the existing logged exception handler so a failed local mutation does
  not disable unrelated service work. UI likes capture PlayerConnection's displayed metadata
  instead of resolving a potentially different service fallback item at the time of the call.
- LM/library sync read SongEntity before its write transaction. An older response could overwrite
  a new local like/unlike or other song fields. Reads now happen inside the transaction. A bounded,
  event-driven per-song revision fence skips snapshots whose request predates a local action.
  Library-absence updates use the existing column-only inLibrary query, preserving liked fields.

## Related paths inspected

- DefaultMediaNotificationProvider forwards every asynchronous artwork update to Media3; its
  callback does not suppress subsequent tracks. MediaItem builders publish each track's own ID,
  title, artist and artworkUri. The cache remains Media3's last-request cache.
- System artwork loading is independent of UI visibility, artwork prefetch dwell and decorative
  animation gates. Those gates remain limited to optional UI/prefetch work.
- Retain the existing 128–512px decoded system-artwork bound, software/ARGB_8888 output, cache
  deduplication and finite retries. No screenshot, new full-size system bitmap, polling timer or
  timeline/media-source replacement is introduced.
- The UI position/lyrics/background gates do not stop media-session metadata callbacks. This
  change does not reduce visible FPS, image quality, buffering or playback features.

## Regression validation

NativeMediaBitmapLoaderTest now uses the plain parent Job that production previously used:
red first cover -> failed image -> independent like/background task -> blue second cover.
The old loader cancels that owner. A separate test checks owner destruction cancels future work;
the existing bitmap-size/config test remains. MediaSessionMetadataTest checks distinct artwork
URIs on successive tracks. LikeTapGate and revision-fence tests cover accepted repeated actions
and stale remote responses. These checks are included in the player quality workflow.

The shared-element animation's native tests, lint, Debug/test build and signed optimized DEV
build remain required. Physical HONOR image publication, touch behavior, heat and battery still
need device verification. Exact CI/APK provenance is recorded in draft PR 100.
