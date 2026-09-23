# Capsule MUSIC

Capsule MUSIC is an Android music client built around YouTube Music. This repository is under active cleanup; the `cleanup/capsule-player-only` branch focuses on making playback predictable, conservative with YouTube requests, and easier to maintain.

> Capsule is an unofficial client. YouTube can change player behavior, client requirements, rate limits, or anti-bot checks at any time. The playback code is designed to fail closed and avoid retry storms, but it cannot guarantee that YouTube will never rate-limit or challenge a connection.

## Current playback architecture

Normal audio playback is intentionally separated from Capsule's legacy browse/search InnerTube code:

```text
MusicService
  -> CapsuleAudioEngine
  -> CapsuleInnerTubeXPlayer
  -> AudioResolveScheduler
  -> InnerTubeX / InnerTubeExtractor
  -> signed audio stream
```

Key rules:

- **One maintained audio extraction path.** Normal AUDIO resolves go through InnerTubeX instead of rotating through legacy player implementations.
- **One extraction at a time.** Foreground playback, prefetch, and downloads share a scheduler so swipe bursts cannot create a bank of simultaneous player requests.
- **Foreground wins.** `PLAYBACK` can preempt `PREFETCH` and `DOWNLOAD`; the priority contract is explicit and does not depend on enum declaration order.
- **Stale work is cancelled.** Prefetch is kept close to the current queue position instead of resolving every track the user quickly skips past.
- **Strict manual client selection.** The user-selectable playback profiles are `VISIONOS`, `WEB_REMIX`, and `WEB_EMBEDDED_PLAYER`. An explicit choice does not silently rotate to a different identity when it fails.
- **No retry carousel after anti-bot signals.** HTTP 429 or an explicit bot-check opens a global AUDIO cooldown instead of trying more playback identities.
- **Transport responses are inspected before library retries.** The audio interceptor can stop nested retries after a rate-limit or bot-check response.
- **Signed URL cache is context-aware.** Cached playback data is invalidated when the playback context changes and is not reused too close to expiry.
- **Web PoToken work is shared and bounded.** One visitor-bound BotGuard/WebView session is reused, with cancellation and timeout protection.

The detailed invariants and maintenance notes live in [`docs/PLAYBACK_ARCHITECTURE.md`](docs/PLAYBACK_ARCHITECTURE.md).

## Build requirements

- JDK 21
- Android SDK with `compileSdk 37`
- Android min SDK 26; target SDK 36

Build a universal debug APK:

```bash
./gradlew :app:assembleUniversalDebug
```

Run the main test suites used by the YouTube-core CI:

```bash
./gradlew \
  :innertube:test \
  :betterlyrics:test \
  :app:testUniversalDebugUnitTest
```

The GitHub Actions workflow `.github/workflows/youtube-core-validation.yml` runs those tests and builds the universal debug APK for changes that touch the playback/YouTube core.

## Important playback tests

The branch includes regression coverage for the failure modes that are easiest to reintroduce in an unofficial YouTube client, including:

- resolver priority and preemption;
- cancelled stale prefetches;
- same-track download vs foreground playback;
- playback stability during rapid track changes;
- playback retry budgets;
- global 429 / bot-check safety behavior;
- real HTTP transport behavior around nested InnerTubeX retries;
- playback URL cache identity and expiry;
- shared extractor prewarm behavior.

## Known architectural debt

The modern AUDIO extraction layer is deliberately small and testable, but the application around it is not fully decomposed yet. `MusicService` still owns too many responsibilities: player lifecycle, resolving/prefetch, video mode, audio focus, Bluetooth behavior, effects/normalization, persistence, scrobbling, and integrations.

The next large refactor should split those responsibilities into dedicated coordinators/controllers **without redesigning the already-stable audio extraction protocol at the same time**. Keeping those changes separate reduces the chance of introducing playback regressions while improving the rest of the application architecture.

## Project structure

- `app/` — Android application, playback service, UI, audio/video playback adapters.
- `innertube/` — Capsule's legacy browse/search/account InnerTube implementation and shared YouTube models/policies.
- `betterlyrics/`, `lrclib/`, `kugou/` — lyrics-related modules.
- `lastfm/`, `kizzy/` — external integrations.
- `.github/workflows/` — validation, release, and dependency/client maintenance automation.

## License

See [`LICENSE`](LICENSE).
