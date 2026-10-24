package com.nikhil.yt.playback

import android.app.Application
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class OfflinePlaybackQueueTest {
    @Test fun realPlayerProjectsPartialQueueBeforePreparingAndRestoresItWithoutLosingRepeatOrSelection() = runBlocking {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).setMediaSourceFactory(
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(RuntimeEnvironment.getApplication())
                .setDataSourceFactory(androidx.media3.datasource.DataSource.Factory {
                    androidx.media3.datasource.ByteArrayDataSource(byteArrayOf(0))
                })).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val queue = OfflinePlaybackQueue(player, scope) { it.mediaId in setOf("a", "c") }
        val original = listOf("a", "failed", "c", "never-downloaded", "missing-file").map(::item)
        try {
            player.repeatMode = Player.REPEAT_MODE_ALL; player.shuffleModeEnabled = true
            queue.replaceOffline(original, 1, 0L, false)
            assertEquals(listOf("a", "c"), ids(player))
            assertEquals("c", player.currentMediaItem?.mediaId)
            assertEquals(original, queue.savedItems()); assertEquals(2, queue.savedIndex())
            player.seekToPreviousMediaItem(); assertTrue(player.currentMediaItem?.mediaId in setOf("a", "c"))
            queue.connectivityChanged(true)
            assertEquals(original.map { it.mediaId }, ids(player))
            assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode); assertTrue(player.shuffleModeEnabled)
        } finally { scope.cancel(); player.release() }
    }
    @Test fun completelyOfflineQueueRemainsPersistableAndReappearsWithoutAutoplay() = runBlocking {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).setMediaSourceFactory(
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(RuntimeEnvironment.getApplication())
                .setDataSourceFactory(androidx.media3.datasource.DataSource.Factory {
                    androidx.media3.datasource.ByteArrayDataSource(byteArrayOf(0))
                })).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val queue = OfflinePlaybackQueue(player, scope) { false }
        val original = listOf(item("a"), item("b"))
        try {
            queue.replaceOffline(original, 1, 0L, true)
            assertEquals(0, player.mediaItemCount); assertFalse(player.playWhenReady)
            assertEquals(original, queue.savedItems()); assertEquals(1, queue.savedIndex())
            queue.connectivityChanged(true)
            assertEquals(listOf("a", "b"), ids(player)); assertFalse(player.playWhenReady)
        } finally { scope.cancel(); player.release() }
    }
    @Test fun queueEditsRetainHiddenMembersAndRepeatedSongOccurrences() {
        assertEquals(listOf("a", "x", "y", "c"), reconcileOfflineQueue(
            listOf("a", "x", "b", "y", "c"), listOf("a", "b", "c"), listOf("a", "c")))
        assertEquals(listOf("a", "new", "x", "b"), reconcileOfflineQueue(
            listOf("a", "x", "b"), listOf("a", "b"), listOf("a", "new", "b")))
        assertEquals(listOf("a", "x", "b"), reconcileOfflineQueue(
            listOf("a", "x", "a", "b"), listOf("a", "a", "b"), listOf("a", "b")))
    }
    private fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("https://example.org/$id.mp3").build()
    private fun ids(player: Player) = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
}
