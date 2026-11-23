package com.nikhil.yt.betterlyrics

import org.junit.Assert.assertTrue
import org.junit.Test

class TtmlXmlSecurityTest {
    @Test fun externalEntitiesAndDtdAreRejected() {
        val hostile = """<!DOCTYPE tt [<!ENTITY steal SYSTEM "file:///etc/passwd">]>
            <tt><body><div><p begin="0s" end="2s">&steal;</p></div></body></tt>"""
        assertTrue(TTMLParser.parseTTML(hostile).isEmpty())
    }

    @Test fun oversizedDocumentsAreRejectedBeforeDomConstruction() {
        assertTrue(TTMLParser.parseTTML("<tt>" + "x".repeat(1_048_576) + "</tt>").isEmpty())
    }

    @Test fun harmlessTimedLyricsRemainParseable() {
        val safe = """<tt><body><div><p begin="0s" end="3s">Hello</p></div></body></tt>"""
        assertTrue(TTMLParser.parseTTML(safe).isNotEmpty())
    }
}
