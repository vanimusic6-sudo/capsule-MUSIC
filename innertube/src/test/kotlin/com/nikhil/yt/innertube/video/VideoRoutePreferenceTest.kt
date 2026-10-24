package com.nikhil.yt.innertube.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoRoutePreferenceTest {
    @Test fun knownWorkingVideoRouteWinsOnNextClipButNotWhenItFails() {
        val health = CapsuleVideoRouteHealth()
        assertEquals(CapsuleVideoRoute.PIPEPIPE,
            health.order(null, preferredBackend = "pipepipe").first())
        health.failure(CapsuleVideoRoute.PIPEPIPE)
        assertEquals(CapsuleVideoRoute.ANDROID_VR,
            health.order(null, preferredBackend = "pipepipe").first())
        health.success(CapsuleVideoRoute.PIPEPIPE)
        assertEquals(CapsuleVideoRoute.ANDROID_VR,
            health.order("pipepipe", preferredBackend = "pipepipe").first())
    }
}
