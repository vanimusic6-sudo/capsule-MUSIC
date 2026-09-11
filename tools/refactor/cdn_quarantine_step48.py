from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
SERVICE = ROOT / "app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt"
LEGACY_POLICY_TEST = ROOT / "app/src/test/kotlin/com/nikhil/yt/playback/CapsuleLoadErrorHandlingPolicyTest.kt"
TEST = ROOT / "app/src/test/kotlin/com/nikhil/yt/playback/CdnRecoveryPolicyTest.kt"

source = SERVICE.read_text(encoding="utf-8")
legacy_test = LEGACY_POLICY_TEST.read_text(encoding="utf-8")

# Step47 deliberately stopped rotating the visitor/BotGuard session on a CDN
# rejection. Keep names honest: this gate only controls player/cipher config
# refresh, never a PoToken session refresh.
old_constant = "SIGNED_URL_SESSION_REFRESH_THRESHOLD_MS"
new_constant = "SIGNED_URL_CIPHER_REFRESH_THRESHOLD_MS"
old_helper = "shouldRefreshStreamSessionAfterSignedUrlRejection"
new_helper = "shouldRefreshCipherConfigAfterSignedUrlRejection"

if old_constant not in source:
    raise SystemExit(f"missing expected constant: {old_constant}")
if old_helper not in source:
    raise SystemExit(f"missing expected helper: {old_helper}")
if old_helper not in legacy_test:
    raise SystemExit(f"legacy policy test no longer references expected helper: {old_helper}")

source = source.replace(old_constant, new_constant)
source = source.replace(old_helper, new_helper)
legacy_test = legacy_test.replace(old_helper, new_helper)
legacy_test = legacy_test.replace(
    "repeatedSignedUrlRejectionRefreshesSessionOnlyOnSecondFreshAttempt",
    "repeatedSignedUrlRejectionRefreshesCipherConfigOnlyOnSecondFreshAttempt",
)

# A 403/410 in step47 quarantines the extraction profile that produced the
# rejected signed URL for this one mediaId. The subsequent cipher-config refresh
# must not immediately erase that evidence before the fresh resolve, otherwise
# the same rejected profile becomes eligible again and recovery can self-cancel.
pattern = re.compile(
    r"(?m)^(?P<indent>[ \t]*)if \(configChanged\) \{\n"
    r"(?P=indent)[ \t]+CapsuleAudioEngine\.clearStreamClientFailures\(\)\n"
    r"(?P=indent)[ \t]+Timber\.tag\(\"MusicService\"\)\.i\(\n"
    r"(?P=indent)[ \t]+\"Player config changed after stream rejection; restored stream clients\",\n"
    r"(?P=indent)[ \t]+\)\n"
    r"(?P=indent)\}"
)

match = pattern.search(source)
if match is None:
    raise SystemExit("expected stream-recovery configChanged block was not found")

indent = match.group("indent")
replacement = (
    f"{indent}if (configChanged) {{\n"
    f"{indent}    // Keep step47's song-local 403/410 evidence through the fresh resolve.\n"
    f"{indent}    // Refreshing player/cipher config can repair signature generation, but it\n"
    f"{indent}    // must not make the just-rejected extraction profile immediately eligible.\n"
    f"{indent}    Timber.tag(\"MusicService\").i(\n"
    f"{indent}        \"Player config changed after stream rejection; preserving per-song rejected-client quarantine id=%s\",\n"
    f"{indent}        mediaId,\n"
    f"{indent}    )\n"
    f"{indent}}}"
)
source = source[:match.start()] + replacement + source[match.end():]

# Guard the intended architecture while applying the patch.
required = [
    "CapsuleAudioEngine.markStreamClientFailed(",
    "httpStatusCode in setOf(403, 410)",
    new_helper,
    "preserving per-song rejected-client quarantine",
]
for needle in required:
    if needle not in source:
        raise SystemExit(f"missing recovery invariant after patch: {needle}")

if old_constant in source or old_helper in source or old_helper in legacy_test:
    raise SystemExit("stale session-refresh naming survived step48")

marker = "preserving per-song rejected-client quarantine"
idx = source.index(marker)
window = source[max(0, idx - 700): idx + 700]
if "clearStreamClientFailures()" in window:
    raise SystemExit("config refresh still clears the per-song rejection quarantine")

SERVICE.write_text(source, encoding="utf-8")
LEGACY_POLICY_TEST.write_text(legacy_test, encoding="utf-8")

TEST.write_text(
    '''package com.nikhil.yt.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CdnRecoveryPolicyTest {
    @Test
    fun rejectedSignedUrlRefreshesCipherConfigAtBoundedRetryThreshold() {
        assertTrue(
            shouldRefreshCipherConfigAfterSignedUrlRejection(
                httpStatusCode = 403,
                budgetDelayMs = SIGNED_URL_CIPHER_REFRESH_THRESHOLD_MS,
            ),
        )
        assertTrue(
            shouldRefreshCipherConfigAfterSignedUrlRejection(
                httpStatusCode = 410,
                budgetDelayMs = SIGNED_URL_CIPHER_REFRESH_THRESHOLD_MS,
            ),
        )
    }

    @Test
    fun rejectedSignedUrlDoesNotRefreshCipherConfigBeforeThreshold() {
        assertFalse(
            shouldRefreshCipherConfigAfterSignedUrlRejection(
                httpStatusCode = 403,
                budgetDelayMs = SIGNED_URL_CIPHER_REFRESH_THRESHOLD_MS - 1L,
            ),
        )
    }

    @Test
    fun unrelatedHttpFailureNeverUsesRejectedSignedUrlCipherRefreshPath() {
        assertFalse(
            shouldRefreshCipherConfigAfterSignedUrlRejection(
                httpStatusCode = 404,
                budgetDelayMs = SIGNED_URL_CIPHER_REFRESH_THRESHOLD_MS,
            ),
        )
    }

    @Test
    fun rejectedSignedUrlRetryRemainsBounded() {
        assertTrue(
            shouldRetryRejectedSignedUrl(
                httpStatusCode = 403,
                budgetDelayMs = SIGNED_URL_MAX_FRESH_RESOLVE_DELAY_MS,
            ),
        )
        assertFalse(
            shouldRetryRejectedSignedUrl(
                httpStatusCode = 403,
                budgetDelayMs = SIGNED_URL_MAX_FRESH_RESOLVE_DELAY_MS + 1L,
            ),
        )
    }
}
''',
    encoding="utf-8",
)

print("step48 applied: per-song CDN rejection quarantine survives cipher-config refresh and legacy tests are aligned")
