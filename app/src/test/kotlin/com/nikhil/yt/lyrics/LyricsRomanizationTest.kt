package com.nikhil.yt.lyrics

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsRomanizationTest {
    @Test fun reopeningReusesFinishedLinesAndPreferenceChangesPrepareOnlyTheMissingScript() = runTest {
        val jp = LyricsEntry(1, "こんにちは")
        val kr = LyricsEntry(2, "안녕")
        val latin = LyricsEntry(3, "Hello")
        var calls = 0
        val convert: suspend (String) -> String = { calls++; "romanized-$it" }
        prepareLyricsRomanization(listOf(jp, kr, latin), true, false, convert, convert)
        assertEquals(1, calls); assertNotNull(jp.romanizedTextFlow.value); assertNull(kr.romanizedTextFlow.value)
        prepareLyricsRomanization(listOf(jp, kr, latin), true, true, convert, convert)
        assertEquals(2, calls); assertNotNull(kr.romanizedTextFlow.value); assertNull(latin.romanizedTextFlow.value)
        prepareLyricsRomanization(listOf(jp, kr, latin), true, true, convert, convert)
        assertEquals(2, calls)
    }

    @Test fun obsoleteLyricsCancelTheirChildrenWithoutPublishingOrReportingAnError() = runTest {
        val entry = LyricsEntry(1, "こんにちは")
        var started = false
        var errors = 0
        val job = launch {
            prepareLyricsRomanization(listOf(entry), true, false,
                romanizeJapanese = { started = true; awaitCancellation() }, onFailure = { errors++ })
        }
        runCurrent(); assertTrue(started)
        job.cancel(); runCurrent()
        assertNull(entry.romanizedTextFlow.value)
        assertEquals(0, errors)
    }
}
