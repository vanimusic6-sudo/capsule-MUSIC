package com.nikhil.yt.playback

import com.nikhil.yt.models.PersistQueue
import com.nikhil.yt.models.persistableList
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

        val restored = roundTrip(original)

        assertEquals("seed-id", restored.automixSeedMediaId)
        assertEquals(listOf("auto-a", "auto-b"), restored.automixAutoAddedMediaIds)
    }

    /**
     * The failure this guards against, stated as the thing a unit test can actually see.
     *
     * A snapshot is Java-serialized, so the *class name* of every object in the graph goes into
     * the file — the lists included. `kotlin.collections.EmptyList` is Serializable and is what
     * the stdlib returns for an empty `toList()`, and R8 renames it like any other library class.
     * A capture caught the result on persistent_automix.data:
     *
     *   InvalidClassException: kk2; class invalid for deserialization
     *
     * — the name the previous build wrote, resolving in the new build to something else entirely.
     * Unshielded, this branch's release mapping calls EmptyList `lk2` and gives `kk2` to
     * `kotlin.collections.EmptyIterator`, which is not Serializable; that is the whole failure.
     *
     * Nothing here can reproduce it, because tests do not run through R8. What they can check is
     * the invariant that makes it impossible: no class a shrinker is free to rename ever gets
     * into the graph in the first place.
     */
    @Test
    fun anEmptyStdlibListIsNotSomethingToPersist() {
        // The exact call site that wrote the broken file: toList() on an empty collection.
        assertNotEquals(ArrayList::class.java, emptySet<String>().toList().javaClass)
    }

    @Test
    fun persistableListHandsBackAPlatformClassThatIsNeverRenamed() {
        assertEquals(ArrayList::class.java, persistableList(emptySet<String>()).javaClass)
        assertEquals(ArrayList::class.java, persistableList(setOf("only")).javaClass)
        assertEquals(ArrayList::class.java, persistableList(listOf("a", "b")).javaClass)
        assertEquals(listOf("a", "b"), persistableList(listOf("a", "b")))
    }

    @Test
    fun anAutomixSnapshotCarriesNoStdlibCollectionSingleton() {
        // Built the way MusicService builds it, including the empty auto-added list that is the
        // normal state until automix has added something.
        val snapshot =
            PersistQueue(
                title = "automix",
                items = persistableList(emptyList()),
                mediaItemIndex = 0,
                position = 0L,
                automixSeedMediaId = "seed-id",
                automixAutoAddedMediaIds = persistableList(emptySet<String>()),
            )

        assertEquals(ArrayList::class.java, snapshot.items.javaClass)
        assertEquals(ArrayList::class.java, snapshot.automixAutoAddedMediaIds?.javaClass)

        val restored = roundTrip(snapshot)

        assertEquals("seed-id", restored.automixSeedMediaId)
        assertEquals(emptyList<String>(), restored.automixAutoAddedMediaIds)
        assertEquals(emptyList<Any>(), restored.items)
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

    private fun roundTrip(queue: PersistQueue): PersistQueue {
        val bytes =
            ByteArrayOutputStream().use { buffer ->
                ObjectOutputStream(buffer).use { output -> output.writeObject(queue) }
                buffer.toByteArray()
            }
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { input ->
            input.readObject() as PersistQueue
        }
    }
}
