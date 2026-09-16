/**
 * Capsule MUSIC
 * What a widget needs from the app graph.
 * GPL-3.0
 */

package com.nikhil.yt.di

import com.nikhil.yt.db.MusicDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * A widget is not a Hilt-injected object: the launcher builds it, so it reaches into the graph
 * rather than being handed anything.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun database(): MusicDatabase
}
