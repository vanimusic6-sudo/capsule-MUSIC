@file:OptIn(com.metrolist.innertubex.sabr.ExperimentalSabrApi::class)

package com.nikhil.yt.playback.audio

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrChunk
import com.metrolist.innertubex.sabr.SabrFormatId
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CapsuleSabrAudioDataSourceTest {
    private fun bootstrap(total: Long = 7L) = SabrBootstrap(
        videoId = "test",
        serverAbrStreamingUrl = "https://rr1.googlevideo.com/videoplayback",
        videoPlaybackUstreamerConfig = byteArrayOf(1),
        clientName = 1,
        clientVersion = "test",
        audioFormat = SabrFormatId(140),
        discardVideoFormat = SabrFormatId(18),
        discardVideoHeight = 360,
        durationMs = 1_000,
        contentLengthBytes = total,
        mimeType = "audio/mp4",
    )

    private fun dataSpec(position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()) =
        DataSpec.Builder().setUri(Uri.parse("capsule-sabr://test"))
            .setCustomData(CapsuleSabrAudioOpenContext(bootstrap()))
            .setPosition(position).setLength(length).build()

    private val chunks = listOf(
        SabrChunk(byteArrayOf(1, 2, 3), 0, true, 0, 0L, 0L),
        SabrChunk(byteArrayOf(4, 5, 6, 7), 3, false, 1, 0L, 1_000L),
    )
    private fun source() = CapsuleSabrAudioDataSource(chunkStream = { flowOf(*chunks.toTypedArray()) })

    @Test fun preservesEveryEncodedByteWithoutPadding() {
        val source = source()
        try {
            assertEquals(7L, source.open(dataSpec()))
            val buffer = ByteArray(7)
            var written = 0
            while (written < 7) {
                val count = source.read(buffer, written, 7 - written)
                if (count == C.RESULT_END_OF_INPUT) break
                written += count
            }
            assertEquals(7, written)
            assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7), buffer)
            assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(1), 0, 1))
        } finally { source.close() }
    }

    @Test fun seekingToABoundedByteRangeIsExact() {
        val source = source()
        try {
            assertEquals(2L, source.open(dataSpec(position = 3, length = 2)))
            val buffer = ByteArray(2)
            assertEquals(2, source.read(buffer, 0, 2))
            assertArrayEquals(byteArrayOf(4, 5), buffer)
            assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, 2))
        } finally { source.close() }
    }

    @Test fun rejectsGapInsteadOfReturningCorruptedAudio() {
        val broken = CapsuleSabrAudioDataSource(chunkStream = {
            flowOf(chunks.first(), chunks.last().copy(startRange = 4))
        })
        try {
            broken.open(dataSpec())
            val first = ByteArray(3)
            assertEquals(3, broken.read(first, 0, first.size))
            assertThrows(java.io.IOException::class.java) {
                broken.read(ByteArray(4), 0, 4)
            }
        } finally { broken.close() }
    }

    @Test fun refusesMismatchedOrMissingSessionBeforeNetwork() {
        val source = source()
        assertThrows(java.io.IOException::class.java) {
            source.open(DataSpec(Uri.parse("capsule-sabr://test")))
        }
        assertThrows(java.io.IOException::class.java) {
            source.open(DataSpec.Builder().setUri(Uri.parse("capsule-sabr://other"))
                .setCustomData(CapsuleSabrAudioOpenContext(bootstrap())).build())
        }
    }
}
