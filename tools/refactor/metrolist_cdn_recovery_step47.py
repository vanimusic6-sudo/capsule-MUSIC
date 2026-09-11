from pathlib import Path
import re

root = Path('.')
service_path = root / 'app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt'
player_path = root / 'app/src/main/kotlin/com/nikhil/yt/playback/audio/CapsuleInnerTubeXPlayer.kt'
potoken_path = root / 'app/src/main/kotlin/com/nikhil/yt/playback/audio/potoken/PoTokenGenerator.kt'
test_path = root / 'app/src/test/kotlin/com/nikhil/yt/playback/audio/CapsuleAudioFallbackPolicyTest.kt'

service = service_path.read_text()
old_service = '''            // A rejected/expired URL does not mean the selected client is broken.
            // Keep the same client and identity, but do not burn through fresh
            // generations in a 250 ms loop. After a second signed-URL rejection,
            // refresh the same visitor-bound streaming session once before the
            // final bounded fresh resolve.
            CapsuleAudioEngine.clearTrackClientFailures(currentMediaId)
'''
new_service = '''            // A CDN 403/410 is attached to the stream generation and the
            // extraction profile that produced it. Match Metrolist's recovery
            // model: quarantine only that profile for this mediaId, then let the
            // existing bounded foreground plan try the next maintained profile.
            // Never rotate visitorData/account identity here, and never use this
            // path for rate limits or bot-checks (handled above as hard stops).
            if (httpStatusCode in setOf(403, 410)) {
                CapsuleAudioEngine.markStreamClientFailed(
                    videoId = currentMediaId,
                    clientKey = null,
                    httpStatusCode = httpStatusCode,
                )
            } else {
                CapsuleAudioEngine.clearTrackClientFailures(currentMediaId)
            }
'''
if service.count(old_service) != 1:
    raise SystemExit(f'expected one MusicService recovery block, found {service.count(old_service)}')
service = service.replace(old_service, new_service)
service_path.write_text(service)

player = player_path.read_text()
pattern = re.compile(
    r'''    suspend fun refreshAfterStreamRejection\(\): Boolean =\n        resolveMutex\.withLock \{.*?\n        \}\n\n    suspend fun playerResponseForPlayback''',
    re.S,
)
replacement = '''    suspend fun refreshAfterStreamRejection(): Boolean =
        resolveMutex.withLock {
            CapsulePlaybackSafety.blockedExceptionOrNull()?.let { throw it }
            // Keep recovery equivalent to Metrolist: a rejected GVS generation
            // may justify refreshing cipher/player configuration, but it must not
            // recreate the healthy visitor-bound BotGuard/PoToken session.
            bundle().cipherService.refreshAfterStreamRejection()
        }

    suspend fun playerResponseForPlayback'''
player, count = pattern.subn(replacement, player, count=1)
if count != 1:
    raise SystemExit(f'expected one refreshAfterStreamRejection block, replaced {count}')
player_path.write_text(player)

potoken = potoken_path.read_text()
pattern = re.compile(
    r'''\n    suspend fun refreshSameVisitorSession\(visitorData: String\): Boolean \{.*?\n    \}\n\n    suspend fun close\(\)''',
    re.S,
)
potoken, count = pattern.subn('\n    suspend fun close()', potoken, count=1)
if count != 1:
    raise SystemExit(f'expected one refreshSameVisitorSession method, removed {count}')
potoken_path.write_text(potoken)

test = test_path.read_text()
marker = '\n}\n'
if not test.endswith(marker):
    raise SystemExit('fallback policy test has unexpected ending')
new_test = '''
    @Test
    fun rejectedPrimaryIsSkippedForOnlyThatTracksFreshResolve() {
        val plan =
            CapsuleAudioFallbackPolicy.profilePlan(
                primaryProfileId = CapsuleAudioFallbackPolicy.WEB_REMIX,
                priority = AudioResolvePriority.PLAYBACK,
                authenticated = false,
                isUploaded = false,
                excludedProfiles = setOf(CapsuleAudioFallbackPolicy.WEB_REMIX),
            )

        assertEquals(
            listOf(
                CapsuleAudioFallbackPolicy.VISIONOS_0_1,
                CapsuleAudioFallbackPolicy.WEB_EMBEDDED,
                CapsuleAudioFallbackPolicy.TVHTML5_SIMPLY,
            ),
            plan,
        )
    }
'''
test = test[:-len(marker)] + new_test + marker
test_path.write_text(test)

print('step47 Metrolist-style CDN recovery applied')
