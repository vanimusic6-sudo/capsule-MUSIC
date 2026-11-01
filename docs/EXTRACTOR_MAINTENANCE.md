# Maintaining extraction backends

Start with [playback architecture](PLAYBACK_ARCHITECTURE.md). The application is not an extractor SDK: extensions should use the maintained facades and typed contracts, not call old compatibility methods or manipulate `MusicService` from a parser.

## Dependency ownership

| Dependency | Pin | Owner and purpose |
| --- | --- | --- |
| InnerTubeX | `v0.11.2` | `app` audio extraction, cipher, client manifests and token contracts |
| NewPipeExtractor | `v0.26.5` | `innertube` video backend and existing JS/player utilities |
| PipePipeExtractor | commit `f8982ca9e7` | `innertube` independent video backend |
| Media3 components | `1.11.1` | `app` player, session, HTTP source, HLS, DASH and UI; one catalog bundle |
| Wire runtime constraint | `6.4.5` | Existing runtime compatibility/security constraint for extractor protobuf models |
| OkHttp / Ktor engines | resolved graph | HTTP transport, not alternative extraction algorithms |

These are repository pins, not claims about the latest upstream release. Cleanup preserves them. The authoritative coordinates live in `gradle/libs.versions.toml`; the resolved CI dependency report records transitive versions.

PipePipe excludes `com.google.protobuf:protobuf-java` because its unused full runtime conflicts with NewPipe's protobuf-lite; its YouTube models use Wire. Do not remove that exclusion as a duplicate-looking line. Keep the Wire constraint. The NewPipe update workflow intentionally updates the catalog's literal version and `CapsuleVideoEngineUpdater.BUNDLED_EXTRACTOR_VERSION` together.

## Audio changes

`CapsuleAudioEngine.resolvePlayback` and `resolveDownload` are the application entry points. The backend's `resolveStream` adapts InnerTubeX to Capsule's playback result. The deleted legacy wrapper accepted parameters that no longer changed routing; do not reintroduce it as a second audio path.

A client/profile change belongs in `AudioClientOrder` and `CapsuleAudioFallbackPolicy`, not inside Media3 or a UI click handler. The per-attempt library boundary translates binary incompatibility into I/O while preserving cancellation and fatal VM errors. Keep the existing outer fallback owner, budgets, authentication eligibility and failed-client history.

A new audio transport must specify seeking, time/byte mapping, stream headers, format identity, cache keys and completion verification. Raw SABR/UMP is not AAC/Opus progressive media. Preserve the existing rejection guard until a complete segment-based adapter exists.

## Video changes

1. Add/adapt the bundled implementation under `innertube/.../video/`; its output is the shared resolved-video model. Despite its historical `CapsuleNewPipeResolved` name, that model is used by all five video routes.
2. Keep vendor-specific setup, downloader and API exceptions in that backend. Normal browsing/account traffic and audio extraction do not use video route health.
3. Add a route explicitly to `CapsuleVideoRoutes` with a finite budget. Keep a single request/route cancellation owner through `VideoExtractionRequests`.
4. Normalize output to the supported contract. Do not bypass `VideoStreamValidation`, inject an arbitrary host, pass a remote MPD directly, or tag an unsupported transport as progressive.
5. Return data. The provider/consumer validate IPC; session coordinators alone decide when to prepare/switch a player. Keep source ID, position, play intent and active audio intact while a video reserve resolves.
6. Add failure-injection coverage, then upgrade the dependency pin in a separate reviewed change if needed.

### Required failure scenarios

| Scenario | Expected behavior |
| --- | --- |
| One endpoint/parser fails | Try the next eligible route within existing budgets |
| Missing compatible format on this clip | Do not poison the backend for unrelated clips |
| Malformed/expired/wrong-track response | Reject before success/cache/player publication; try reserve |
| Incompatible library API | Route/client-local error; no process-wide audio failure |
| HTTP 429 | Stop the chain and obey its scoped cooldown |
| One bot challenge | Apply the existing local policy; no invented app-wide ban |
| Whole request cancelled / track skipped | Cancel transport and suppress publication; no reserve request |
| Decoder/source format changes | Recreate the source; never splice incompatible cached bytes |
| All eligible backends unavailable | Return a classified bounded failure; no unbounded retry carousel |

## Verification and diagnostics

The playback CI runs audio/video recovery, downloads and all `innertube` tests. Its audit artifact includes the exact runtime dependency tree, static source results and OSV status. Check the scan status: an API timeout is incomplete coverage, not a zero-vulnerability verdict. The Python audit recognizes existing backup/credential/checksum guards and tests that removing a guard makes the finding return.

The full regression workflow runs native title rendering, 58 UI regression classes, Android lint, metadata/video tests and a Universal Debug APK. DEV APKs use the dedicated development signer; official signing policy remains unchanged.

Log route/profile names, elapsed time and classified failure kinds. Do not log signed URLs, player JSON, cookies, visitor data, tokens or arbitrary upstream exception messages. Keep diagnostic errors machine-generated and retain useful exception types for local classification.

Read dated audit reports as evidence for their recorded revision. Record current automated results, artifact/source identity and physical-device limits in a new report. Live upstream behavior, device-specific codecs/HTTP3, first-frame latency and battery consumption still need device traces after protocol or engine changes.
