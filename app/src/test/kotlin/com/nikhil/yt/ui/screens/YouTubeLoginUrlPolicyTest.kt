package com.nikhil.yt.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeLoginUrlPolicyTest {
    @Test
    fun acceptsOnlyExactHttpsMusicYouTubeHost() {
        assertTrue(isYouTubeMusicLoginPage("https://music.youtube.com/"))
        assertTrue(isYouTubeMusicLoginPage("https://music.youtube.com/watch?v=test"))

        assertFalse(isYouTubeMusicLoginPage(null))
        assertFalse(isYouTubeMusicLoginPage("http://music.youtube.com/"))
        assertFalse(isYouTubeMusicLoginPage("https://music.youtube.com.evil.example/"))
        assertFalse(isYouTubeMusicLoginPage("https://accounts.google.com/ServiceLogin"))
        assertFalse(isYouTubeMusicLoginPage("not a url"))
    }
}
