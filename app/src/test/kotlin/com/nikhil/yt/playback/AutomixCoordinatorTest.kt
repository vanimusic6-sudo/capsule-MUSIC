package com.nikhil.yt.playback

import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomixCoordinatorTest {
    private fun item(id: String) = MediaItem.Builder().setMediaId(id).build()

    @Test
    fun mergeCandidatesKeepsSourceOrderDeduplicatesAndExcludesQueueIds() {
        val merged =
            mergeAutomixCandidates(
                sources =
                    listOf(
                        listOf(item("queue"), item("a"), item("b")),
                        listOf(item("b"), item("c"), item("a")),
                    ),
                excludedIds = setOf("queue"),
                limit = 10,
            )

        assertEquals(listOf("a", "b", "c"), merged.map { it.mediaId })
    }

    @Test
    fun mergeCandidatesHonoursLimitAndDropsBlankIds() {
        val merged =
            mergeAutomixCandidates(
                sources = listOf(listOf(item(""), item("a"), item("b"), item("c"))),
                excludedIds = emptySet(),
                limit = 2,
            )

        assertEquals(listOf("a", "b"), merged.map { it.mediaId })
    }

    @Test
    fun removeAtIsBoundsSafeAndDoesNotForgetQueueOwnership() {
        val runtime = AutomixRuntime()
        val coordinator =
            AutomixCoordinator(
                runtime = runtime,
                scopeProvider = { throw AssertionError("scope should not be used") },
                stabilityGate = PlaybackStabilityGate(),
                cacheRelatedSongs = { _, _ -> },
            )
        runtime.items.value = listOf(item("a"), item("b"))
        runtime.autoAddedMediaIds += "owned"

        assertNull(coordinator.removeAt(9))
        assertEquals("a", coordinator.removeAt(0)?.mediaId)
        assertEquals(listOf("b"), runtime.items.value.map { it.mediaId })
        assertTrue(runtime.autoAddedMediaIds.contains("owned"))
    }
}
