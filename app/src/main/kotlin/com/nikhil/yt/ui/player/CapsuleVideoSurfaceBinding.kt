package com.nikhil.yt.ui.player

import android.view.View
import android.view.ViewTreeObserver

/** A cold TextureView becomes available during traversal, after attachment/layout.
 * Observe traversal only until ready, then retain just the native attach listener. */
internal class CapsuleVideoSurfaceBinding(
    private val view: View,
    private val isReady: () -> Boolean,
    private val onAttached: () -> Unit,
    private val onDetached: () -> Unit,
) : View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    private var observer: ViewTreeObserver? = null
    private var bound = false
    private var disposed = false

    init {
        view.addOnAttachStateChangeListener(this)
        if (view.isAttachedToWindow) awaitSurface()
    }

    private fun awaitSurface() {
        if (disposed || bound) return
        if (isReady()) {
            stopObserving()
            bound = true
            onAttached()
        } else if (observer == null) {
            observer = view.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        }
    }

    private fun stopObserving() {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observer = null
    }

    override fun onPreDraw(): Boolean { awaitSurface(); return true }
    override fun onViewAttachedToWindow(v: View) = awaitSurface()
    override fun onViewDetachedFromWindow(v: View) {
        stopObserving()
        if (bound) { bound = false; onDetached() }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        stopObserving()
        view.removeOnAttachStateChangeListener(this)
        if (bound) { bound = false; onDetached() }
    }
}
