@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.video

import androidx.media3.common.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
class CapsuleVideoSessionTest {
    private class Decoder {
        var item: MediaItem? = MediaItem.Builder().setMediaId("song").build()
        var position = 1_000L
        var duration = 200_000L
        var state = Player.STATE_BUFFERING
        var intent = true
        var playing = false
        var volume = 1f
        var tracks = Tracks.EMPTY
        var selection = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
        var parameters = PlaybackParameters.DEFAULT
        var buffered: Long? = null
        var prepares = 0
        var replacements = 0
        val listeners = mutableListOf<Player.Listener>()
        val seeks = mutableListOf<Long>()
        val intentWrites = mutableListOf<Boolean>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "addListener" -> { listeners += args!![0] as Player.Listener; null }
                "removeListener" -> { listeners -= args!![0] as Player.Listener; null }
                "getCurrentMediaItem" -> item
                "getCurrentPosition" -> position
                "getBufferedPosition" -> buffered ?: (position + 12_000L)
                "getDuration" -> duration
                "getPlaybackState" -> state
                "getPlayWhenReady" -> intent
                "setPlayWhenReady" -> { intent = args!![0] as Boolean; intentWrites += intent; null }
                "isPlaying" -> playing
                "getCurrentTracks" -> tracks
                "getVolume" -> volume
                "setVolume" -> { volume = args!![0] as Float; null }
                "getTrackSelectionParameters" -> selection
                "setTrackSelectionParameters" -> { selection = args!![0] as TrackSelectionParameters; null }
                "getPlaybackParameters" -> parameters
                "setPlaybackParameters" -> { parameters = args!![0] as PlaybackParameters; null }
                "setMediaItem" -> { replacements++; item = args!![0] as MediaItem; if (args.size > 1) position = args[1] as Long; null }
                "seekTo" -> { position = args!![0] as Long; seeks += position; null }
                "stop" -> { state = Player.STATE_IDLE; playing = false; null }
                "clearMediaItems" -> { item = null; null }
                "prepare" -> { prepares++; state = Player.STATE_BUFFERING; null }
                "equals" -> false
                "hashCode" -> System.identityHashCode(this)
                "toString" -> "TestDecoder"
                else -> null
            }
        } as Player
        fun event(flag: Int = Player.EVENT_PLAYBACK_STATE_CHANGED) {
            val events = Player.Events(FlagSet.Builder().add(flag).build())
            listeners.toList().forEach { it.onEvents(player, events) }
        }
        fun frame() = listeners.toList().forEach { it.onRenderedFirstFrame() }
        fun userSeek(target: Long) {
            val old = Player.PositionInfo(null, 0, item, null, 0, position, position, C.INDEX_UNSET, C.INDEX_UNSET)
            position = target
            val next = Player.PositionInfo(null, 0, item, null, 0, position, position, C.INDEX_UNSET, C.INDEX_UNSET)
            listeners.toList().forEach { it.onPositionDiscontinuity(old, next, Player.DISCONTINUITY_REASON_SEEK) }
            event(Player.EVENT_POSITION_DISCONTINUITY)
        }
        fun ready(audioSelected: Boolean = true) {
            state = Player.STATE_READY; playing = intent
            tracks = if (audioSelected) Tracks(listOf(Tracks.Group(
                TrackGroup("soundtrack", Format.Builder().setSampleMimeType("audio/mp4").build()),
                false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true),
            ))) else Tracks.EMPTY
            event()
        }
    }
    private class Fixture(scope: kotlinx.coroutines.CoroutineScope) {
        var clockMs = 0L
        val music = Decoder().apply { state = Player.STATE_READY; playing = true }
        val clip = Decoder()
        val owned = mutableListOf<Boolean>()
        val frames = mutableListOf<String>()
        val hidden = mutableListOf<String>()
        val buffering = mutableListOf<Boolean>()
        val failures = mutableListOf<Throwable>()
        val endings = mutableListOf<String>()
        val session = CapsuleVideoSession(music.player, clip.player, scope,
            { owned += it; music.volume = if (it) 0f else 1f },
            { frames += it }, { _, error -> failures += error }, { endings += it },
            { hidden += it }, { _, loading -> buffering += loading }, clockMs = { clockMs })
        fun start() {
            session.setSurfaceAttached(true)
            session.start("song", MediaItem.Builder().setMediaId("song").build(), 0.8f)
        }
    }
    private fun indexedVideoItem(seconds: Int = 180): MediaItem =
        MediaItem.Builder().setMediaId("song").setUri("https://example.test/clip").setTag(
            YouTubeVideoResolver.ResolvedVideo(
                sourceMediaId = "song", videoId = "abcdefghijk",
                videoStreamUrl = "https://rr.googlevideo.com/videoplayback",
                videoFormat = YouTubeVideoResolver.StreamFormat(248, 1280, 720, "720p"),
                expiresAtMs = Long.MAX_VALUE,
                delivery = com.nikhil.yt.innertube.video.CapsuleVideoDelivery.DASH,
                dashManifest = """<MPD mediaPresentationDuration="PT${seconds}.0S"/>""",
            ),
        ).build()

    @Test fun indexedDashStartsNearMusicWithoutThrowawayFrameAndNetworkSeek() = runTest {
        val f = Fixture(this)
        f.music.position = 12_000L
        f.session.setSurfaceAttached(true)
        f.session.start("song", indexedVideoItem(), 0.8f)
        assertEquals(14_000L, f.clip.position)
        assertTrue(f.clip.seeks.isEmpty())
        f.music.position = 14_050L
        f.clip.ready(); f.clip.frame()
        advanceTimeBy(261L); runCurrent()
        assertEquals(listOf("song"), f.frames)
        assertTrue(f.clip.seeks.isEmpty())
        f.session.stop()
    }

    @Test fun measuredTwoSecondDashStartupAvoidsBothCorrectionSeeks() = runTest {
        val f = Fixture(this)
        f.music.position = 5_440L
        f.session.setSurfaceAttached(true)
        f.session.start("song", indexedVideoItem(), 0.8f)
        // Original October 25 capture: 1.4s anticipation left MUSIC
        // ~582ms ahead after a 2s indexed DASH startup; alignment then
        // caused a second buffered correction. Now stay inside 400ms.
        assertEquals(7_440L, f.clip.position)
        f.music.position = 7_500L
        f.clip.ready()
        f.clip.frame()
        advanceTimeBy(261L); runCurrent()
        assertEquals(listOf("song"), f.frames)
        assertTrue("startup should not issue a second CDN seek", f.clip.seeks.isEmpty())
        f.session.stop()
    }

    @Test fun indexedDashPreseekFallsBackNearClipEndOrWithoutDuration() {
        val item = indexedVideoItem(seconds = 60)
        assertEquals(12_000L, capsuleDashVideoInitialPosition(item, 10_000L, true))
        assertEquals(10_000L, capsuleDashVideoInitialPosition(item, 10_000L, false))
        assertNull(capsuleDashVideoInitialPosition(item, 58_000L, true))
        assertNull(capsuleDashVideoInitialPosition(
            item.buildUpon().setTag(null).build(), 10_000L, true,
        ))
    }

    @Test fun frameReadyTracksOrderingAndShortSeam() = runTest {
        val f = Fixture(this); f.start()
        f.clip.frame(); f.clip.ready(audioSelected = false)
        assertEquals(1f, f.music.volume)
        f.clip.ready()
        assertEquals(0f, f.music.volume)
        assertEquals(0f, f.clip.volume)
        assertTrue(f.frames.isEmpty())
        advanceTimeBy(179); runCurrent()
        assertEquals(0f, f.music.volume)
        assertEquals(0f, f.clip.volume)
        assertTrue(f.frames.isEmpty())
        advanceTimeBy(2); runCurrent()
        assertTrue(f.clip.volume > 0f && f.clip.volume < 0.8f)
        assertTrue(f.frames.isEmpty())
        advanceTimeBy(80); runCurrent()
        assertEquals(0.8f, f.clip.volume)
        assertEquals(listOf("song"), f.frames)
        f.session.stop()
        assertEquals(1f, f.music.volume)
    }
    @Test fun startupAlignsToMusicAfterSlowResolveBeforeMutingIt() = runTest {
        val f = Fixture(this); f.start()
        f.music.position = 8_000L
        f.clip.ready()
        assertEquals(listOf(8_000L), f.clip.seeks)
        assertEquals(1f, f.music.volume)
        assertTrue(f.frames.isEmpty())
        f.clip.frame()
        advanceTimeBy(261); runCurrent()
        assertEquals(0.8f, f.clip.volume)
        f.session.stop()
    }
    @Test fun firstSeekDelayGetsOneBufferedCorrectionBeforeTheClockChanges() = runTest {
        val f = Fixture(this); f.start()
        f.music.position = 8_000L; f.clip.ready()
        f.music.position = 9_700L; f.clip.frame()
        assertEquals(listOf(8_000L, 9_700L), f.clip.seeks)
        assertEquals(1f, f.music.volume)
        assertTrue(f.frames.isEmpty())
        f.music.position = 9_750L; f.clip.frame()
        advanceTimeBy(261); runCurrent()
        assertEquals(listOf("song"), f.frames)
        assertEquals(2, f.clip.seeks.size)
        f.session.stop()
    }
    @Test fun measuredSeekLatencyCorrectsTheMovingClockWithoutAThirdSeek() = runTest {
        val f = Fixture(this); f.start()
        f.music.position = 8_000L; f.clip.ready()
        f.clockMs = 700L; f.music.position = 8_700L; f.clip.frame()
        assertEquals(listOf(8_000L, 9_100L), f.clip.seeks)
        f.clockMs = 1_400L; f.music.position = 9_400L; f.clip.frame()
        advanceTimeBy(261); runCurrent()
        assertTrue(kotlin.math.abs(f.music.position - f.clip.position) <= 400L)
        assertEquals(2, f.clip.seeks.size)
        assertEquals(listOf("song"), f.frames)
        f.session.stop()
    }
    @Test fun reusedClipDoesNotRepeatItsInitialAlignmentWhenReadyArrives() = runTest {
        val f = Fixture(this); f.start(); f.clip.ready(); f.clip.frame()
        advanceTimeBy(261); runCurrent(); f.session.stop(keepPrepared = true)
        f.music.position = 6_000L; f.start()
        f.clockMs = 500L; f.music.position = 6_500L; f.clip.ready(); f.clip.frame()
        assertEquals(listOf(6_000L, 6_900L), f.clip.seeks)
        f.clockMs = 1_000L; f.music.position = 7_000L; f.clip.frame()
        advanceTimeBy(261); runCurrent()
        assertEquals(1, f.clip.prepares)
        assertEquals(2, f.clip.seeks.size)
        assertEquals(2, f.frames.size)
        f.session.stop()
    }
    @Test fun pauseDuringPredictedSeekReturnsToTheExactPausedPosition() = runTest {
        val f = Fixture(this); f.start()
        f.music.position = 8_000L; f.clip.ready()
        f.clockMs = 800L; f.music.position = 8_800L; f.clip.frame()
        assertEquals(9_200L, f.clip.position)
        f.music.intent = false; f.music.playing = false; f.clip.playing = false; f.music.event(Player.EVENT_PLAY_WHEN_READY_CHANGED)
        f.clockMs = 1_600L; f.clip.frame()
        assertEquals(8_800L, f.clip.position)
        f.clockMs = 1_800L; f.clip.frame()
        assertFalse(f.clip.intent)
        assertEquals(listOf("song"), f.frames)
        f.session.stop()
    }
    @Test fun stalledProgressiveSourceHandsOverToRecoveryBeforeTheLongGenericTimeout() = runTest {
        val f = Fixture(this); f.session.setSurfaceAttached(true)
        f.session.start("song", MediaItem.Builder().setMediaId("song").build(), 0.8f, startupBudgetMs = 8_000L)
        f.clip.ready()
        advanceTimeBy(8_001L); runCurrent()
        assertEquals(1, f.failures.size)
        assertEquals(1f, f.music.volume)
        f.session.stop()
    }
    @Test fun slowNetworkSeekCannotPredictASecondLongJumpForTheBufferedSeek() = runTest {
        val f = Fixture(this); f.start()
        f.music.position = 8_000L; f.clip.ready()
        f.clockMs = 3_000L; f.music.position = 11_000L; f.clip.frame()
        assertEquals(11_400L, f.clip.position)
        f.clockMs = 3_200L; f.music.position = 11_200L; f.clip.frame()
        advanceTimeBy(261); runCurrent()
        assertTrue(kotlin.math.abs(f.music.position - f.clip.position) <= 400L)
        assertEquals(2, f.clip.seeks.size)
        assertEquals(listOf("song"), f.frames)
        f.session.stop()
    }
    @Test fun bufferedCorrectionDoesNotCreateAnUnboundedSeekChase() = runTest {
        val f = Fixture(this); f.start()
        f.music.position = 8_000L; f.clip.ready()
        f.music.position = 9_700L; f.clip.buffered = 9_800L; f.clip.frame()
        repeat(5) { f.clip.event() }
        assertEquals(listOf(8_000L), f.clip.seeks)
        f.clip.buffered = 15_000L; f.clip.event()
        assertEquals(listOf(8_000L, 9_700L), f.clip.seeks)
        f.music.position = 10_200L; f.clip.frame()
        repeat(5) { f.clip.event() }
        advanceTimeBy(261); runCurrent()
        assertEquals(2, f.clip.seeks.size)
        assertEquals(listOf("song"), f.frames)
        f.session.stop()
    }
    @Test fun preparedClipSurvivesTheSourceFactoryRewritingItsTimelineUri() = runTest {
        val f = Fixture(this); f.start(); f.clip.ready(); f.clip.frame()
        advanceTimeBy(261); runCurrent()
        f.clip.item = f.clip.item!!.buildUpon().setUri("https://cdn.example.test/clip.mp4").build()
        f.session.stop(keepPrepared = true)
        assertFalse(f.clip.intent)
        f.start(); f.clip.ready(); f.clip.frame()
        advanceTimeBy(261); runCurrent()
        assertEquals(1, f.clip.prepares)
        assertEquals(1, f.clip.replacements)
        assertEquals(2, f.frames.size)
        f.session.stop()
    }
    @Test fun changedSourceOrReleasedDecoderMustPrepareAgain() = runTest {
        val f = Fixture(this); f.start(); f.clip.ready(); f.clip.frame()
        advanceTimeBy(261); runCurrent()
        f.session.stop(keepPrepared = true)
        f.session.start("song", MediaItem.Builder().setMediaId("song").setUri("https://new.example.test/clip").build(), 0.8f)
        assertEquals(2, f.clip.prepares)
        f.session.stop()
        f.start()
        assertEquals(3, f.clip.prepares)
        f.session.stop()
    }
    @Test fun pauseDuringSeamKeepsThePreparedClipPausedAndCancelsTheDelayedRamp() = runTest {
        val f = Fixture(this); f.start(); f.clip.ready(); f.clip.frame()
        f.music.intent = false; f.clip.playing = false; f.music.event(Player.EVENT_PLAY_WHEN_READY_CHANGED)
        f.session.setVolume(0.3f)
        advanceTimeBy(500); runCurrent()
        assertEquals(0.3f, f.clip.volume)
        assertEquals(0f, f.music.volume)
        assertFalse(f.clip.intent)
        assertFalse(f.clip.playing)
        assertEquals(listOf("song"), f.frames)
        f.session.stop()
    }
    @Test fun detachAndReturnNeverReselectTracksSeekOrSwitchSoundOwner() = runTest {
        val f = Fixture(this); f.start(); f.clip.ready(); f.clip.frame()
        advanceTimeBy(261); runCurrent()
        val selectionBefore = f.clip.selection
        val seeksBefore = f.clip.seeks.toList()
        val ownersBefore = f.owned.toList()
        f.session.setForeground(false); f.session.setSurfaceAttached(false)
        f.clip.event()
        assertEquals(0.8f, f.clip.volume)
        assertEquals(0f, f.music.volume)
        assertSame(selectionBefore, f.clip.selection)
        f.session.setSurfaceAttached(true); f.session.setForeground(true)
        f.clip.frame()
        assertSame(selectionBefore, f.clip.selection)
        assertEquals(seeksBefore, f.clip.seeks)
        assertEquals(ownersBefore, f.owned)
        assertEquals(0.8f, f.clip.volume)
        f.session.stop()
    }

    @Test fun reopeningPlayerWhilePausedNeverSeeksOrStartsAnotherSoundSeam() = runTest {
        val f = Fixture(this); f.start(); f.clip.ready(); f.clip.frame()
        advanceTimeBy(261); runCurrent()
        f.music.intent = false; f.clip.playing = false; f.music.event()
        val seeksBefore = f.clip.seeks.toList()
        val ownersBefore = f.owned.toList()
        f.session.setSurfaceAttached(false); f.session.setSurfaceAttached(true)
        assertEquals(seeksBefore, f.clip.seeks)
        assertEquals(ownersBefore, f.owned)
        assertFalse(f.clip.intent)
        f.session.stop()
    }
    @Test fun pausedForegroundAndNativeFrameCallbacksNeverIssueEvenOnePlayCommand() = runTest {
        val f = Fixture(this); f.start(); f.clip.ready(); f.clip.frame()
        advanceTimeBy(261); runCurrent()
        f.music.intent = false; f.clip.playing = false; f.music.event()
        f.clip.intentWrites.clear()
        val seeksBefore = f.clip.seeks.toList()
        repeat(3) {
            f.session.setForeground(false); f.session.setSurfaceAttached(false)
            f.clip.event()
            f.session.setSurfaceAttached(true); f.session.setForeground(true)
            f.clip.frame(); f.clip.event()
        }
        assertTrue(f.clip.intentWrites.none { it })
        assertFalse(f.clip.intent)
        assertEquals(seeksBefore, f.clip.seeks)
        assertEquals(listOf(true), f.owned)
        f.session.stop()
    }
    @Test fun hiddenSurfaceNeverTimesOutAndStaleSeamNeverMutesNewSong() = runTest {
        val f = Fixture(this); f.start(); f.session.setSurfaceAttached(false)
        advanceTimeBy(20_000); runCurrent()
        assertTrue(f.failures.isEmpty())
        assertEquals(1f, f.music.volume)
        f.session.setSurfaceAttached(true); f.clip.ready(); f.clip.frame()
        f.music.item = MediaItem.Builder().setMediaId("next").build(); f.music.event()
        advanceTimeBy(200); runCurrent()
        assertEquals(1f, f.music.volume)
        assertEquals(0f, f.clip.volume)
        f.session.stop()
    }
    @Test fun bufferingAfterPresentationRetainsVideoAndNeverReplaysTheSoundSeam() = runTest {
        val f = Fixture(this); f.start(); f.clip.ready(); f.clip.frame()
        advanceTimeBy(261); runCurrent()
        f.clip.state = Player.STATE_BUFFERING; f.clip.playing = false; f.clip.event()
        val framesBefore = f.frames.toList()
        val hiddenBefore = f.hidden.toList()
        val ownersBefore = f.owned.toList()
        assertEquals(0f, f.music.volume)
        assertEquals(0.8f, f.clip.volume)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(framesBefore, f.frames)
        assertEquals(hiddenBefore, f.hidden)
        assertEquals(listOf(true), f.buffering)
        f.clip.ready(); runCurrent()
        assertEquals(ownersBefore, f.owned)
        assertEquals(listOf(true, false), f.buffering)
        assertEquals(0.8f, f.clip.volume)
        assertEquals(0f, f.music.volume)
        f.session.stop()
    }
    @Test fun selectedTracksWithoutFirstFrameTimeOutWithoutMutingMusic() = runTest {
        val f = Fixture(this); f.start(); f.clip.ready()
        advanceTimeBy(15_100); runCurrent()
        assertEquals(1, f.failures.size)
        assertTrue(f.failures.single() is java.net.SocketTimeoutException)
        assertEquals(1f, f.music.volume)
        assertEquals(0f, f.clip.volume)
        f.session.stop()
    }
    @Test fun repeatedTimelineSeeksAndPausedBufferingNeverHideVideoOrRestoreMusic() = runTest {
        val f = Fixture(this); f.start(); f.clip.ready(); f.clip.frame()
        advanceTimeBy(261); runCurrent()
        val owners = f.owned.toList()
        val hidden = f.hidden.toList()
        repeat(4) { index ->
            val target = 40_000L + index * 20_000L
            f.music.userSeek(target)
            assertEquals(target, f.clip.position)
            f.clip.state = Player.STATE_BUFFERING; f.clip.playing = false; f.clip.tracks = Tracks.EMPTY; f.clip.event()
            advanceTimeBy(2_000); runCurrent()
            assertEquals(0f, f.music.volume)
            assertEquals(hidden, f.hidden)
            f.clip.ready(); f.clip.frame()
            assertEquals(owners, f.owned)
            assertEquals(listOf("song"), f.frames)
        }
        f.music.intent = false; f.clip.playing = false; f.music.event()
        f.music.userSeek(15_000L)
        f.clip.state = Player.STATE_BUFFERING; f.clip.event()
        advanceTimeBy(20_000); runCurrent()
        assertTrue(f.failures.isEmpty())
        assertEquals(hidden, f.hidden)
        assertEquals(owners, f.owned)
        f.session.stop()
    }
    @Test fun permanentStallStillRestoresMusicAfterTheBoundedVideoTimeout() = runTest {
        val f = Fixture(this); f.start(); f.clip.ready(); f.clip.frame()
        advanceTimeBy(261); runCurrent()
        f.clip.state = Player.STATE_BUFFERING; f.clip.playing = false; f.clip.event()
        advanceTimeBy(12_100); runCurrent()
        assertEquals(1, f.failures.size)
        assertTrue(f.failures.single() is java.net.SocketTimeoutException)
        assertEquals(1f, f.music.volume)
        assertEquals(0f, f.clip.volume)
        f.session.stop()
    }
    @Test fun failedClipNeverRewindsOrRebuildsTheHealthyMusicPlayer() = runTest {
        val f = Fixture(this); f.start(); f.clip.ready(); f.clip.frame()
        advanceTimeBy(261); runCurrent()
        val item = f.music.item
        f.clip.position = 20_000L
        f.music.position = 32_000L
        val error = PlaybackException("VIDEO HTTP 403", java.io.IOException("403"), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        f.clip.listeners.toList().forEach { it.onPlayerError(error) }
        assertEquals(1, f.failures.size)
        assertTrue(f.music.seeks.isEmpty())
        assertEquals(32_000L, f.music.position)
        assertSame(item, f.music.item)
        assertEquals(0, f.music.prepares)
        assertEquals(0, f.music.replacements)
        assertTrue(f.music.intent)
        assertEquals(1f, f.music.volume)
        assertEquals(0f, f.clip.volume)
        f.session.stop()
    }
    @Test fun intentionalSwitchBackToMusicStillPreservesTheVideoPosition() = runTest {
        val f = Fixture(this); f.start(); f.clip.ready(); f.clip.frame()
        advanceTimeBy(261); runCurrent()
        f.clip.position = 20_000L; f.music.position = 22_000L
        f.session.stop()
        assertEquals(listOf(20_000L), f.music.seeks)
    }

    @Test fun volumeChangeDuringSeamUsesLatestValueAndStopCancelsIt() = runTest {
        val f = Fixture(this); f.start(); f.clip.ready(); f.clip.frame()
        f.session.setVolume(0.3f)
        assertEquals(0f, f.clip.volume)
        advanceTimeBy(261); runCurrent()
        assertEquals(0.3f, f.clip.volume)
        f.session.stop()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(0f, f.clip.volume)
        assertEquals(1f, f.music.volume)
    }

    @Test fun bufferingDuringSoundRampCannotRevealOrUnmuteTheClipLater() = runTest {
        val f = Fixture(this); f.start(); f.clip.ready(); f.clip.frame()
        advanceTimeBy(200); runCurrent()
        assertTrue(f.frames.isEmpty())
        f.clip.state = Player.STATE_BUFFERING; f.clip.playing = false; f.clip.event()
        advanceTimeBy(500); runCurrent()
        assertEquals(1f, f.music.volume)
        assertEquals(0f, f.clip.volume)
        assertTrue(f.frames.isEmpty())
        f.session.stop()
    }
    @Test fun pausedPreparationOwnsVideoAndResumesWithoutAnotherMusicHandoff() = runTest {
        val f = Fixture(this); f.music.intent = false; f.start()
        f.clip.ready(); f.clip.playing = false; f.clip.frame()
        assertEquals(listOf("song"), f.frames)
        assertEquals(listOf(true), f.owned)
        assertEquals(0f, f.music.volume)
        assertFalse(f.clip.intent)
        f.music.intent = true; f.music.event(); f.clip.ready()
        assertEquals(0.8f, f.clip.volume)
        assertEquals(listOf(true), f.owned)
        f.session.stop()
    }
    @Test fun rapidReopenUsesTheExistingFrameWithoutWaitingForANewFrameEvent() = runTest {
        val f = Fixture(this); f.start(); f.clip.ready(); f.clip.frame()
        advanceTimeBy(261); runCurrent()
        f.session.stop(keepPrepared = true)
        f.start(); f.clip.ready()
        // The same TextureView has not detached and Media3 does not announce
        // another first frame for this prepared, unseeked source.
        advanceTimeBy(261); runCurrent()
        assertEquals(2, f.frames.size)
        assertEquals(1, f.clip.prepares)
        assertTrue(f.failures.isEmpty())
        f.session.stop()
    }

    @Test fun pausedPreparedVideoReopensWithoutChangingPlayIntent() = runTest {
        val f = Fixture(this)
        f.music.intent = false; f.music.playing = false
        f.start(); f.clip.ready(); f.clip.frame()
        f.session.stop(keepPrepared = true)
        f.start(); f.clip.ready()
        assertEquals(2, f.frames.size)
        assertFalse(f.clip.intent)
        assertFalse(f.music.intent)
        assertEquals(1, f.clip.prepares)
        f.session.stop()
    }

    @Test fun detachedSurfaceCannotReuseAStaleFirstFrame() = runTest {
        val f = Fixture(this); f.start(); f.clip.ready(); f.clip.frame()
        advanceTimeBy(261); runCurrent()
        f.session.stop(keepPrepared = true)
        f.session.setSurfaceAttached(false)
        f.start(); f.clip.ready()
        advanceTimeBy(261); runCurrent()
        assertEquals(1, f.frames.size)
        f.clip.frame()
        advanceTimeBy(261); runCurrent()
        assertEquals(2, f.frames.size)
        f.session.stop()
    }


    @Test fun enteringVideoBeyondItsDurationStartsAtZeroInsteadOfReachingEof() = runTest {
        val f = Fixture(this)
        f.music.position = 250_000L
        f.clip.duration = 40_000L
        f.start()
        // MediaItem preparation always begins at zero; READY uses actual duration.
        f.clip.ready()
        assertEquals(listOf(0L), f.clip.seeks)
        f.clip.frame(); advanceTimeBy(261); runCurrent()
        assertEquals(0L, f.clip.position)
        assertTrue(f.failures.isEmpty())
        f.session.stop()
    }

    @Test fun restartedShortVideoRewindsOnlyTheMutedAudioShadowClock() = runTest {
        val f = Fixture(this)
        f.music.position = 250_000L
        f.music.duration = 300_000L
        f.clip.duration = 40_000L
        f.start(); f.clip.ready(); f.clip.frame()
        assertEquals(0L, f.music.position)
        assertEquals(0L, f.clip.position)
        assertEquals(0f, f.music.volume)
        advanceTimeBy(261); runCurrent()
        assertEquals(listOf("song"), f.frames)
        assertTrue(f.failures.isEmpty())
        f.session.stop()
    }

    @Test fun naturalClipEofIsNotAnErrorAndCompletesExactlyOnce() = runTest {
        val f = Fixture(this); f.start(); f.clip.ready(); f.clip.frame()
        advanceTimeBy(261); runCurrent()
        f.clip.state = Player.STATE_ENDED
        f.clip.listeners.toList().forEach { it.onPlaybackStateChanged(Player.STATE_ENDED) }
        f.clip.listeners.toList().forEach { it.onPlaybackStateChanged(Player.STATE_ENDED) }
        assertEquals(listOf("song"), f.endings)
        assertTrue(f.failures.isEmpty())
        assertEquals(1f, f.music.volume)
    }

    @Test fun seekExactlyToClipEndCompletesRatherThanSeekingToLastFrame() = runTest {
        val f = Fixture(this)
        f.clip.duration = 20_000L
        f.start(); f.clip.ready(); f.clip.frame()
        advanceTimeBy(261); runCurrent()
        f.music.userSeek(20_000L)
        assertEquals(listOf("song"), f.endings)
        assertTrue(f.failures.isEmpty())
        assertFalse(f.clip.seeks.contains(19_999L))
    }

    @Test fun unknownDecoderDurationDefersSeekUntilTimelineCanClampIt() = runTest {
        val f = Fixture(this)
        f.clip.duration = C.TIME_UNSET
        f.start()
        f.session.seekTo(5_000L)
        assertTrue("no unbounded seek may reach the decoder", f.clip.seeks.isEmpty())
        f.clip.duration = 20_000L
        f.clip.ready()
        assertEquals(listOf(5_000L), f.clip.seeks)
        assertEquals(5_000L, f.music.position)
        f.session.stop()
    }

    @Test fun deferredSeekBeyondClipEndCompletesOnTimelineReadiness() = runTest {
        val f = Fixture(this)
        f.clip.duration = C.TIME_UNSET
        f.start()
        f.session.seekTo(30_000L)
        f.clip.duration = 20_000L
        f.clip.ready()
        assertEquals(listOf("song"), f.endings)
        assertTrue(f.failures.isEmpty())
    }

    @Test fun explicitClipSeekWhileBufferingKeepsVideoAndClampsToDecoderBounds() = runTest {
        val f = Fixture(this)
        f.clip.duration = 20_000L
        f.start(); f.clip.ready(); f.clip.frame()
        advanceTimeBy(261); runCurrent()
        f.clip.state = Player.STATE_BUFFERING
        f.clip.playing = false
        f.clip.event()
        f.session.seekTo(-200L)
        assertEquals(0L, f.clip.position)
        assertTrue(f.failures.isEmpty())
        assertTrue(f.endings.isEmpty())
        assertEquals(0f, f.music.volume)
        f.session.stop()
    }

    @Test fun switchingBackToShorterAudioClampsWithoutJumpingToAnotherTrack() = runTest {
        val f = Fixture(this); f.start(); f.clip.ready(); f.clip.frame()
        advanceTimeBy(261); runCurrent()
        f.music.duration = 15_000L
        f.clip.position = 20_000L
        f.session.stop()
        assertEquals(14_999L, f.music.position)
        assertEquals(1f, f.music.volume)
    }
}
