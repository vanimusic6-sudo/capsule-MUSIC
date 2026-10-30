# Capsule Music — code integrity and hostile-input security review (29 Oct 2026)

## Threat model and trust boundaries

**Security goals:** maintain APK/code integrity, protect credentials and files, prevent
malicious data from executing with the application's privileges, contain damage
from a compromised parser, and fail closed without hanging audio or destroying
the user's local library.

**Possible attacker-controlled inputs:** arbitrary .backup/.cbackup/.m3u/.csv
documents provided through the Android document picker, GitHub-looking APKs
offered through links, forged capsule://together links and LAN packets,
media metadata and response bodies, intentionally malformed video IDs, remote
scripts used for YouTube BotGuard, and unreviewed dependency updates.

A ZIP/SQLite backup **does not bypass AES-GCM**. The actual risks are decompression
bombs, XML resource exhaustion/type confusion, corrupted SQLite data, backup
rollback/replacement, and malicious content processed **after** decrypting data
inside a privileged app process.

## Changes implemented on audit/security-deep-20261028

### Restores and settings
- Portable backups remain authenticated with chunked AES-256-GCM, a separate
  password-derived key and verified final frame. Wrong password, malformed
  ciphertext and truncation fail closed.
- Untrusted ZIP entries are allowlisted by **exact filename**. Directory,
  traversal, duplicate, unknown and oversized entries fail during validation.
- Restore now fully materializes a disposable **private** staging directory
  before stopping audio, closing Room, or changing the live database. Content
  URIs are opened once only: no source substitution between preflight and use.
- Backup settings.preferences_pb is never imported; it may contain raw legacy
  auth credentials.
- XML DOCTYPE/ENTITY, excessive nesting, keys, values and element counts
  are rejected before touching Room.
- Known preferences are checked against the declared key/type schema; invalid
  floats (NaN/infinity), unexpected types, imported credentials and unknown
  keys are ignored instead of poisoning Compose or playback initialization.
- Live database replacement includes previous-file rollback and startup
  recovery if the process dies during installation; a committed marker
  distinguishes successful moves from interrupted ones.
- There are automated tests for crafted ZIP entries, size limits, recovery
  markers, encrypted backup corruption and preferences type confusion.

### Releases, APK signing and build provenance
- Normal app updates are offered via the official project release page.
  No downloaded JAR/DEX is executed dynamically.
- New *Update screen* tool can inspect a selected APK's package identity and
  signing certificate against an independently pinned official release
  certificate SHA-256. The APK is streamed into a bounded temporary file, not
  executed, and is deleted afterward.
- This is a **certificate metadata preflight**, not a replacement for Android's
  full signature validation during package installation. Release CI also uses
  apksigner and a pinned certificate check for signed artifacts.
- **GitHub account name alone does not cryptographically prove authorship**.
  Developer identity here is possession of the official private signing key.
  Anyone with that key could impersonate releases; a compromised signing
  workflow/CI runner is in scope.
- HIGH-PRIORITY owner follow-up: restrict the signing keystore to a **protected**
  GitHub Environment requiring maintainer approval and protected release refs;
  stop signing unreviewed pushes to dozens of feature branches. The current
  release workflow still has direct access to signing secrets on many branches.
- For a second independent signature, enroll an OFFLINE, developer-held Ed25519
  signing key for release manifest hashes, whose public key can be pinned
  in the app. The private key must **never** be in repo or CI. This needs a real
  owner-generated key and independent verification path; not yet implemented.

### Untrusted parsers and external code
- VIDEO provider refuses malformed video IDs; VIDEO/legacy Discord diagnostics
  no longer print raw upstream exception text or signed content URLs.
- Account and BotGuard WebViews deny local file/content providers, mixed
  content, file-origin cross-origin access and geolocation. BotGuard retains
  the JS bridge it requires for token minting; WebView code remains an
  especially sensitive area.
- Video extraction is already in a non-exported **separate process** and
  doesn't receive normal account DataStore state at process startup.
  But `android:process=":capsule_video"` **does not create another UID**.
  This is CRASH isolation, not a proper security sandbox: compromised code
  in that process may still access the app's UID-visible data.
- NewPipe and pipepipe parsers still use untrusted upstream code. InnerTubeX
  AUDIO operates in the main application process. A library running under
  the app's UID cannot be made privilege-isolated just by wrapping its API.
- Future strict isolation requires a separate `android:isolatedProcess="true"`
  bound Service with a different UID, narrow binder messages, no file/keystore
  access and a host-controlled, allowlisted HTTP request relay (isolated UIDs
  lack normal app permissions). Migrating the video resolver, and especially
  the AUDIO/InnerTubeX client, must be staged and benchmarked for latency,
  battery and 403/410 regressions before replacing the active stable engine.

## What remains unverified / cannot be guaranteed by source scanning

1. Actual Android-17 test: encrypted backup import/export on two devices,
   interrupted restore, corrupted files, unchanged library if validation fails.
2. First-install APK check with genuine and intentionally differently signed
   APKs; Android package installer must reject modified signed APK bytes.
3. Remote WebView subframe/callback attacks and BotGuard script behaviour;
   production cookies should not be used in those tests.
4. Video parser fuzzing, signed media URL redirects, and IPC stress testing.
5. Runtime traffic capture with credentials redacted; battery/CPU profiling
   before and after more restrictive parser isolation.
6. Protect/rotate signing credentials and implement independently signed
   release manifests.
7. Global `usesCleartextTraffic="true"` still required by some LAN/radio
   paths; review allowlist and transport migration rather than disabling it
   blindly.
8. A compromised OS/kernel, stolen release signing key, or arbitrary code
   execution within the main app process cannot be fixed by DataStore
   encryption. Keystore plaintext is used by the app at runtime.

**Rule for release:** green CI is necessary but not sufficient. Never describe
Capsule as malware-proof or the separate VIDEO process as a strict sandbox.
