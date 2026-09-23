package com.nikhil.yt.ui

import android.app.Application
import com.nikhil.yt.lyrics.LyricsHelper
import com.nikhil.yt.utils.NetworkConnectivityObserver
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An answer with no words in it has to count as no answer.
 *
 * This decides whether the search moves on to the next provider. Getting it wrong does not show an
 * error — it shows a blank lyrics screen for a song several other sources had, because an empty
 * result won and nobody else was asked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EmptyLyricsTest {
    private val helper =
        LyricsHelper(
            context = ApplicationProvider.getApplicationContext(),
            networkConnectivity = NetworkConnectivityObserver(ApplicationProvider.getApplicationContext()),
        )

    /**
     * The one that actually happened. TTML with no words is angle brackets and attributes all the
     * way down, so a check that only stripped line timestamps saw plenty of "content".
     */
    @Test fun ttmlWithNoWordsIsNotLyrics() {
        val empty =
            """<tt xmlns="http://www.w3.org/ns/ttml"><body><div></div></body></tt>"""
        assertFalse(helper.isMeaningfulLyrics(empty))

        val emptyParagraphs =
            """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>""" +
                """<p begin="1s" end="2s"></p><p begin="2s" end="3s">  </p>""" +
                """</div></body></tt>"""
        assertFalse(helper.isMeaningfulLyrics(emptyParagraphs))
    }

    @Test fun ttmlWithWordsIsLyrics() {
        val real =
            """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>""" +
                """<p begin="1s" end="2s">My day will come</p></div></body></tt>"""
        assertTrue(helper.isMeaningfulLyrics(real))
    }

    /** Word stamps are not whitespace, so a timings-only line used to read as content too. */
    @Test fun enhancedLrcWithNoWordsIsNotLyrics() {
        assertFalse(helper.isMeaningfulLyrics("[00:00.55]<00:00.55> <00:00.90> <00:01.83>"))
    }

    @Test fun enhancedLrcWithWordsIsLyrics() {
        assertTrue(helper.isMeaningfulLyrics("[00:00.55]<00:00.55>My <00:00.90>day<00:01.83>"))
    }

    @Test fun timestampsWithNothingAfterThemAreNotLyrics() {
        assertFalse(helper.isMeaningfulLyrics("[00:00.55]\n[00:05.81]\n[00:09.20]"))
    }

    @Test fun ordinaryLyricsSurviveAllOfThis() {
        assertTrue(helper.isMeaningfulLyrics("[00:00.55]My day will come"))
        assertTrue(helper.isMeaningfulLyrics("My day will come\nI gave too much"))
    }

    @Test fun nothingAtAllIsNotLyrics() {
        assertFalse(helper.isMeaningfulLyrics(""))
        assertFalse(helper.isMeaningfulLyrics("   \n​ ﻿ "))
        assertFalse(helper.isMeaningfulLyrics("LYRICS_NOT_FOUND"))
    }
}
