# Capsule Music — automated optimized development APK

A normal development cycle builds a **real R8-optimized release variant** via
`Capsule Optimized DEV Release`. This does **not** create an official release
or use its signing certificate.

| Variant | application ID | R8 + resource shrinking | Signing |
| --- | --- | --- | --- |
| Debug | `com.nikhil.yt.debug` | No | Android debug key |
| **DevRelease** | **`com.nikhil.yt.dev`** | **Yes** | Isolated development key |
| Official Release | `com.nikhil.yt` | Yes | Owner's offline production key |

On a push to the active development branches, GitHub Actions builds
`:app:assembleUniversalDevRelease`, verifies the APK's package and signature,
and uploads an artifact named `Capsule-DEV-Release-<run number>`.

## Signing: immediately usable, optionally upgradable

The workflow works *without configuration*: it creates an **ephemeral**
nonproduction development keystore on the runner, signs the build and erases
the keystore when finished. Because the signer changes on the next run, a new
DEV build **cannot update the old DEV installation**; uninstall the old
`com.nikhil.yt.dev` first (its DEV data will be deleted). It does not
touch `com.nikhil.yt` or its library/data.

To permit in-place upgrades of future DEV APKs, generate a **separate,
nonproduction** signing keystore and configure these *repository-level*
GitHub Actions secrets:

- `CAPSULE_DEV_KEYSTORE_BASE64` (single-line base64-encoded keystore bytes).
- `CAPSULE_DEV_STORE_PASSWORD` (keystore password).
- `CAPSULE_DEV_KEY_ALIAS` (alias).
- `CAPSULE_DEV_KEY_PASSWORD` (key password).

Configure all four, or none. Do not reuse, upload, copy, or import the
**official production keystore**. To update an ephemeral-signed DEV build with
a persistent-signed DEV build, uninstall once to change signing identity.

## Release discipline

The official app continues to use `com.nikhil.yt` and a separately managed,
owner-controlled local signing process. CI must **never** receive the official
production signing key. Full compatibility with an already installed
official-signed APK can only be achieved by signing with its **same**
private key on the owner's PC.

DEV builds are for the owner's functional tests, not public publication.
R8 shrinker mappings are separate and should not be distributed with APKs.
