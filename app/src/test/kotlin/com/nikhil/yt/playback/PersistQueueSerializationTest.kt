package com.nikhil.yt.playback

import com.nikhil.yt.models.PersistQueue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersistQueueSerializationTest {
    @Test
    fun persistQueueKeepsLegacySerialVersionUid() {
        assertEquals(1L, ObjectStreamClass.lookup(PersistQueue::class.java).serialVersionUID)
    }

    @Test
    fun automixOwnershipMetadataRoundTrips() {
        val original =
            PersistQueue(
                title = "automix",
                items = emptyList(),
                mediaItemIndex = 0,
                position = 0L,
                automixSeedMediaId = "seed-id",
                automixAutoAddedMediaIds = listOf("auto-a", "auto-b"),
            )

        val bytes =
            ByteArrayOutputStream().use { buffer ->
                ObjectOutputStream(buffer).use { output -> output.writeObject(original) }
                buffer.toByteArray()
            }

        val restored =
            ObjectInputStream(ByteArrayInputStream(bytes)).use { input ->
                input.readObject() as PersistQueue
            }

        assertEquals("seed-id", restored.automixSeedMediaId)
        assertEquals(listOf("auto-a", "auto-b"), restored.automixAutoAddedMediaIds)
    }

    @Test
    fun normalQueueLeavesAutomixMetadataAbsent() {
        val queue =
            PersistQueue(
                title = "queue",
                items = emptyList(),
                mediaItemIndex = 0,
                position = 0L,
            )

        assertNull(queue.automixSeedMediaId)
        assertNull(queue.automixAutoAddedMediaIds)
    }
}
