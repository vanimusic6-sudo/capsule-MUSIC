/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.models

import java.io.Serializable

/**
 * The same items, in a list Java serialization can still read after the next release.
 *
 * A snapshot is written with Java serialization, which stores the *class name* of every object in
 * the graph -- the lists included. The stdlib hands back its own implementations for the small
 * cases: `toList()` on an empty collection returns the singleton `kotlin.collections.EmptyList`,
 * which is Serializable and which R8 renames. A capture caught the consequence on startup --
 * `InvalidClassException: kk2; class invalid for deserialization` on persistent_automix.data --
 * and the release mapping names the culprit exactly: unshielded, this branch calls EmptyList
 * `lk2` and gives `kk2` -- the name in the crash -- to `kotlin.collections.EmptyIterator`, which
 * is not Serializable at all. The build that wrote the file had EmptyList under that name; the
 * build that read it back had handed the name to something else.
 *
 * Only the automix file carried it. It is the only one of the three built from `toList()`, and
 * its list of auto-added ids is empty whenever automix has added nothing yet.
 *
 * ArrayList is a platform class no shrinker renames, so a snapshot built from these survives an
 * update whatever R8 does with the rest. proguard-rules.pro keeps the names of Serializable
 * classes as well, which closes the same hole from the other side; this one makes the files
 * correct rather than merely rescued, and does not depend on a rule staying in that file.
 *
 * Every list that goes into a persisted model should come through here.
 */
fun <T> persistableList(items: Collection<T>): List<T> = ArrayList(items)

data class PersistQueue(
    val title: String?,
    val items: List<MediaMetadata>,
    val mediaItemIndex: Int,
    val position: Long,
    val queueType: QueueType = QueueType.LIST,
    val queueData: QueueData? = null,
    /**
     * Automix snapshots historically reused PersistQueue. Keep the additional
     * metadata nullable so Java-serialized snapshots created before these
     * fields existed remain readable with serialVersionUID = 1L.
     * Normal queue snapshots leave both fields null.
     */
    val automixSeedMediaId: String? = null,
    val automixAutoAddedMediaIds: List<String>? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

sealed class QueueType : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    object LIST : QueueType() {
        private const val serialVersionUID = 1L
        private fun readResolve(): Any = LIST
    }

    object YOUTUBE : QueueType() {
        private const val serialVersionUID = 1L
        private fun readResolve(): Any = YOUTUBE
    }

    object YOUTUBE_ALBUM_RADIO : QueueType() {
        private const val serialVersionUID = 1L
        private fun readResolve(): Any = YOUTUBE_ALBUM_RADIO
    }

    object LOCAL_ALBUM_RADIO : QueueType() {
        private const val serialVersionUID = 1L
        private fun readResolve(): Any = LOCAL_ALBUM_RADIO
    }
}

sealed class QueueData : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    /**
     * Keep the old `endpoint` marker for Java-serialization compatibility with
     * pre-fix snapshots. New snapshots store the actual scalar WatchEndpoint
     * state so a YouTube/radio queue can continue paging after process death.
     */
    data class YouTubeData(
        val endpoint: String,
        val continuation: String? = null,
        val videoId: String? = null,
        val playlistId: String? = null,
        val playlistSetVideoId: String? = null,
        val params: String? = null,
        val index: Int? = null,
        val musicVideoType: String? = null,
    ) : QueueData() {
        companion object {
            private const val serialVersionUID = 1L
            const val STRUCTURED_ENDPOINT = "structured_watch_endpoint_v1"
        }
    }

    data class YouTubeAlbumRadioData(
        val playlistId: String,
        val albumSongCount: Int = 0,
        val continuation: String? = null,
        val firstTimeLoaded: Boolean = false,
    ) : QueueData() {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    data class LocalAlbumRadioData(
        val albumId: String,
        val startIndex: Int = 0,
        val playlistId: String? = null,
        val continuation: String? = null,
        val firstTimeLoaded: Boolean = false,
        val initialSongCount: Int = 0,
    ) : QueueData() {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
}
