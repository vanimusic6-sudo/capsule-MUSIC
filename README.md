# Capsule MUSIC

Capsule MUSIC is an Android YouTube Music client derived from Velune. The playback core is maintained as a set of explicit boundaries: extraction finds a supported stream, policy controls fallback, and Media3 owns playback. Working client order, authentication, transport capabilities and recovery budgets are changed independently of code cleanup.

## Start here

| Need | Entry point |
| --- | --- |
| Understand playback and recovery | [Playback architecture](docs/PLAYBACK_ARCHITECTURE.md) |
| Maintain extractors or add a fallback | [Extractor maintenance guide](docs/EXTRACTOR_MAINTENANCE.md) |
| Find current design notes and historical audits | [Documentation index](docs/README.md) |
| Build a separately signed development APK | [DEV signing](docs/CAPSULE_DEV_SIGNING.md) |

## Playback boundaries

- **Audio:** `MusicService` → `CapsuleAudioEngine` → `CapsuleInnerTubeXPlayer` → the pinned InnerTubeX extractor. Capsule owns the bounded profile plan and request scheduler; the library owns its client manifests, cipher and extraction implementation.
- **Downloads:** the same audio facade, with a dedicated extractor instance, profile plan and failure/cooldown history. Downloading a song does not consume the foreground WEB token provider.
- **Video:** the non-exported `:capsule_video` provider runs direct clients, PipePipe and NewPipe behind one result contract. A route is successful only after URL, expiry, track identity and supported manifest checks. A broken route can yield to the next eligible route before the player is changed.
- **Playback state:** Media3 and the service/session coordinators own queue, position, audio focus and audio/video switching. Extractors never replace players or manipulate UI state.
- **Caches:** signed URLs retain their headers, selected representation, expiry and context. Audio byte offsets belong to one format; fallback to a different format recreates the source instead of joining incompatible bytes.

Audio uses at most three configured, eligible profiles per resolve. Speculative prefetch does not rotate identities. HTTP 429 stops the relevant request chain; bot challenges and deterministic client failures retain their explicit per-track/per-route scope. Cancellation is not an invitation to try another backend.

Capsule is an unofficial client. Upstream changes can make an endpoint unavailable; bounded fallback contains failures but cannot guarantee a working stream during a complete upstream outage.

## Modules and dependencies

| Module | Responsibility |
| --- | --- |
| `app/` | Android service, Media3 sources, audio adapter, video IPC, UI and platform integrations |
| `innertube/` | Browse/search/account APIs, shared YouTube policies, bundled video adapters and video output validation |
| `betterlyrics/`, `lrclib/` | Lyrics providers |
| `lastfm/`, `kizzy/` | External integrations |
| `canvas/` | Canvas media integration |

Exact dependency versions are in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). Media3 components share one version through the `media3-playback` bundle. Audio extraction, NewPipe and PipePipe are separate coordinates; the PipePipe protobuf exclusion is intentional. See the maintenance guide before upgrading an extractor or removing a seemingly duplicate runtime.

## Build and verify

Requirements: JDK 21, Android SDK 37.1, Android min SDK 26 / target SDK 36. The Gradle wrapper distribution is checksum-pinned.

```bash
./gradlew :app:assembleUniversalDebug
./gradlew :innertube:test :app:testUniversalDebugUnitTest
python3 -m unittest discover -s scripts -p 'test_*.py'
```

CI builds a separately signed DEV APK, a separate TEST APK and the Universal Debug APK. The playback validation workflow runs recovery/download/extractor tests and records the exact runtime dependency graph and OSV audit status. The full regression workflow runs native title rendering, UI regressions and Android lint. An unavailable advisory service is recorded as an incomplete scan, not as evidence that dependencies are clean.

Capsule uses the independent `com.vanimusic.capsule` install ID, with `.dev` and `.debug` variants. Earlier builds shared Velune's ID. Before moving to the new installation, read [application identity and data migration](docs/APP_IDENTITY.md); the new package installs separately and cannot automatically read the old app's private data.

Historical dated reports describe the revision they audited; the architecture and maintenance guide describe the active contracts. Keep changes on review branches until the resulting behavior has been reviewed on a device.

## License

See [LICENSE](LICENSE).
