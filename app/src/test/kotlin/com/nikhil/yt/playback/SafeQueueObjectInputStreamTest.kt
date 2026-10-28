package com.nikhil.yt.playback

import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.models.PersistQueue
import com.nikhil.yt.models.PersistPlayerState
import com.nikhil.yt.models.QueueData
import com.nikhil.yt.models.QueueType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InvalidClassException
import java.io.ObjectOutputStream
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeQueueObjectInputStreamTest {
    private fun bytes(value: Any): ByteArray =
        ByteArrayOutputStream().use { buffer ->
            ObjectOutputStream(buffer).use { it.writeObject(value) }
            buffer.toByteArray()
        }

    @Test fun restoreQueueAndMetadataWithoutWipingProgress() {
        val queue = PersistQueue(
            title = "My queue",
            items = arrayListOf(MediaMetadata(
                id = "track", title = "Song",
                artists = arrayListOf(MediaMetadata.Artist("id", "Artist")),
                duration = 14, album = MediaMetadata.Album("album-id", "Album"),
                likedDate = LocalDateTime.of(2026, 10, 28, 14, 0)
            )),
            mediaItemIndex = 0, position = 120L,
            queueType = QueueType.YOUTUBE,
            queueData = QueueData.YouTubeData(
                endpoint = QueueData.YouTubeData.STRUCTURED_ENDPOINT,
                videoId = "track"
            )
        )
        val result = SafeQueueObjectInputStream(ByteArrayInputStream(bytes(queue)))
            .use { it.readObject() as PersistQueue }
        assertEquals(queue.title, result.title)
        assertEquals(queue.items, result.items)
        assertEquals(queue.position, result.position)
    }

    @Test fun restorePlayerState() {
        val state = PersistPlayerState(true, 1, false, 0.5f, 120L, 0, 3)
        val result = SafeQueueObjectInputStream(ByteArrayInputStream(bytes(state)))
            .use { it.readObject() as PersistPlayerState }
        assertEquals(state, result)
    }

    @Test(expected = InvalidClassException::class)
    fun refuseUnknownSerializableClasses() {
        SafeQueueObjectInputStream(ByteArrayInputStream(bytes(File("malicious"))))
            .use { it.readObject() }
    }

    @Test fun rejectUnexpectedTypeAndObjectArrays() {
        assertFalse(isApprovedQueueSerializationType("javax.management.BadAttributeValueExpException"))
        assertFalse(isApprovedQueueSerializationType("[Ljava.net.URL;"))
        assertTrue(isApprovedQueueSerializationType("[Lcom.nikhil.yt.models.MediaMetadata;"))
        assertTrue(isApprovedQueueSerializationType("[I"))
    }
}
