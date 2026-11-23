package com.nikhil.yt.lyrics

import org.junit.Assert.*
import org.junit.Test

class AdditionalLyricsSourcesTest {
    @Test fun kugouAcceptsGenuineTimedLinesOnly() {
        val valid = """{"provider":"kugou","lyrics":"[00:01.10]First line\\n[00:03.25]Second"}"""
        assertTrue(extractKugouLrc(valid)?.contains("First line") == true)
        assertNull(extractKugouLrc("""{"provider":"qq","lyrics":"[00:01.10]Wrong provider"}"""))
        assertNull(extractKugouLrc("""{"provider":"kugou","lyrics":"no timestamp"}"""))
        assertNull(extractKugouLrc("""{"provider":"kugou","lyrics":""}"""))
    }

    @Test fun biniUrlsRejectUntrustedDestinationsAndCredentials() {
        val prefix = "https://lyrics-storage.binimum.org/s/test.ttml"
        assertEquals(prefix, safeBinimumLyricsUrl(prefix))
        for (hostile in listOf(
            "http://lyrics-storage.binimum.org/s/test.ttml",
            "https://evil.test/s/test.ttml",
            "https://lyrics-storage.binimum.org.evil.test/s/test.ttml",
            "https://127.0.0.1/test.ttml",
            "https://x:y@lyrics-storage.binimum.org/s/test.ttml",
            "https://lyrics-storage.binimum.org:8443/test.ttml",
            "https://lyrics-storage.binimum.org/s/test.ttml#fragment",
        )) assertNull(hostile, safeBinimumLyricsUrl(hostile))
    }

    @Test fun biniRejectsWrongDurationAndRanksWordTiming() {
        val result = """{"results":[
          {"lyricsUrl":"https://lyrics-storage.binimum.org/line.ttml","timing_type":"line","duration":200},
          {"lyricsUrl":"https://evil.test/word.ttml","timing_type":"word","duration":200},
          {"lyricsUrl":"https://lyrics-storage.binimum.org/word.ttml","timing_type":"word","duration":202}
        ]}"""
        assertEquals("https://lyrics-storage.binimum.org/word.ttml", selectBinimumLyricsUrl(result, 200))
        assertNull(selectBinimumLyricsUrl(result, 500))
    }
}
