/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.models

import java.io.Serializable

data class PersistQueue(
    val title: String?,
    val items: List<MediaMetadata>,
    val mediaItemIndex: Int,
    val position: Long,
    val queueType: QueueType = QueueType.LIST,
    val queueData: QueueData? = null,
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
