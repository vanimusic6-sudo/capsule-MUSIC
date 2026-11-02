package com.nikhil.yt.ui.player

import android.app.Activity
import android.app.Application
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CapsuleVideoSurfaceBindingTest {
    @Test fun coldSurfaceBindsOnceWhenReadyAndSleepsUntilARealReattachment() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = FrameLayout(activity)
        val view = View(activity)
        root.addView(view)
        activity.setContentView(root)
        var ready = false
        var attached = 0
        var detached = 0
        var readinessChecks = 0
        val binding = CapsuleVideoSurfaceBinding(view, { readinessChecks++; ready }, { attached++ }, { detached++ })
        try {
            view.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(0, attached)
            ready = true
            view.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(1, attached)
            val checksAtReady = readinessChecks
            repeat(30) { view.viewTreeObserver.dispatchOnPreDraw() }
            assertEquals("Ready playback has no readiness callback per frame", checksAtReady, readinessChecks)
            root.removeView(view)
            assertEquals(1, detached)
            root.addView(view)
            view.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(2, attached)
            binding.dispose()
            assertEquals(2, detached)
            binding.dispose()
            assertEquals(2, detached)
        } finally { binding.dispose(); controller.pause().stop().destroy() }
    }

    @Test fun disposalBeforeAvailabilityCannotAttachLater() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val view = View(activity)
        var ready = false
        var attached = 0
        var detached = 0
        val binding = CapsuleVideoSurfaceBinding(view, { ready }, { attached++ }, { detached++ })
        activity.setContentView(view)
        binding.dispose()
        ready = true
        view.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(0, attached)
        assertEquals(0, detached)
        controller.pause().stop().destroy()
    }
}
