@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.video

import androidx.media3.common.*
import androidx.media3.common.util.FlagSet
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
        val listeners = mutableListOf<Player.Listener>()
        val seeks = mutableListOf<Long>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "addListener" -> { listeners += args!![0] as Player.Listener; null }
                "removeListener" -> { listeners -= args!![0] as Player.Listener; null }
                "getCurrentMediaItem" -> item
                "getCurrentPosition" -> position
                "getDuration" -> duration
                "getPlaybackState" -> state
                "getPlayWhenReady" -> intent
                "setPlayWhenReady" -> { intent = args!![0] as Boolean; null }
                "isPlaying" -> playing
                "getCurrentTracks" -> tracks
                "getVolume" -> volume
                "setVolume" -> { volume = args!![0] as Float; null }
                "getTrackSelectionParameters" -> selection
                "setTrackSelectionParameters" -> { selection = args!![0] as TrackSelectionParameters; null }
                "getPlaybackParameters" -> parameters
                "setPlaybackParameters" -> { parameters = args!![0] as PlaybackParameters; null }
                "setMediaItem" -> { item = args!![0] as MediaItem; if (args.size > 1) position = args[1] as Long; null }
                "seekTo" -> { position = args!![0] as Long; seeks += position; null }
                "stop" -> { state = Player.STATE_IDLE; playing = false; null }
                "clearMediaItems" -> { item = null; null }
                "prepare" -> { state = Player.STATE_BUFFERING; null }
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
        fun ready(audioSelected: Boolean = true) {
            state = Player.STATE_READY; playing = true
            tracks = if (audioSelected) Tracks(listOf(Tracks.Group(
                TrackGroup("soundtrack", Format.Builder().setSampleMimeType("audio/mp4").build()),
                false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true),
            ))) else Tracks.EMPTY
            event()
        }
    }
    private class Fixture(scope: kotlinx.coroutines.CoroutineScope) {
        val music = Decoder().apply { state = Player.STATE_READY; playing = true }
        val clip = Decoder()
        val owned = mutableListOf<Boolean>()
        val frames = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val session = CapsuleVideoSession(music.player, clip.player, scope,
            { owned += it; music.volume = if (it) 0f else 1f },
            { frames += it }, { _, error -> failures += error }, {})
        fun start() {
            session.setSurfaceAttached(true)
            session.start("song", MediaItem.Builder().setMediaId("song").build(), 0.8f)
        }
    }
    @Test fun frameReadyTracksOrderingAndShortSeam() = runTest {
        val f = Fixture(this); f.start()
        f.clip.frame(); f.clip.ready(audioSelected = false)
        assertEquals(1f, f.music.volume)
        f.clip.ready()
        assertEquals(0f, f.music.volume)
        assertEquals(0f, f.clip.volume)
        advanceTimeBy(116); runCurrent()
        assertEquals(0.8f, f.clip.volume)
        assertEquals(listOf("song"), f.frames)
        f.session.stop()
        assertEquals(1f, f.music.volume)
    }
    @Test fun startupAlignsToMusicAfterSlowResolveBeforeMutingIt() = runTest {
        val f = Fixture(this); f.start()
        f.music.position = 8_000L
        f.clip.ready(); f.clip.frame()
        assertEquals(listOf(8_000L), f.clip.seeks)
        assertEquals(1f, f.music.volume)
        assertTrue(f.frames.isEmpty())
        f.clip.frame()
        advanceTimeBy(116); runCurrent()
        assertEquals(0.8f, f.clip.volume)
        f.session.stop()
    }
    @Test fun pauseDuringSeamRestoresMusicAndCannotUnmuteOldClip() = runTest {
        val f = Fixture(this); f.start(); f.clip.ready(); f.clip.frame()
        f.music.intent = false; f.clip.playing = false; f.music.event(Player.EVENT_PLAY_WHEN_READY_CHANGED)
        advanceTimeBy(200); runCurrent()
        assertEquals(0f, f.clip.volume)
        assertEquals(1f, f.music.volume)
        f.session.stop()
    }
    @Test fun detachPreservesOriginalClipSoundAndDisablesOnlyVideoTrack() = runTest {
        val f = Fixture(this); f.start(); f.clip.ready(); f.clip.frame()
        advanceTimeBy(116); runCurrent()
        f.session.setForeground(false); f.session.setSurfaceAttached(false)
        f.clip.event()
        assertEquals(0.8f, f.clip.volume)
        assertEquals(0f, f.music.volume)
        assertTrue(f.clip.selection.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO))
        f.session.setSurfaceAttached(true); f.session.setForeground(true)
        assertFalse(f.clip.selection.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO))
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
    @Test fun bufferingAfterSoundHandoffImmediatelyRestoresMusic() = runTest {
        val f = Fixture(this); f.start(); f.clip.ready(); f.clip.frame()
        advanceTimeBy(116); runCurrent()
        f.clip.state = Player.STATE_BUFFERING; f.clip.playing = false; f.clip.event()
        assertEquals(1f, f.music.volume)
        assertEquals(0f, f.clip.volume)
        f.session.stop()
    }
}
