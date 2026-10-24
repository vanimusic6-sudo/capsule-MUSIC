package com.nikhil.yt.playback

import com.nikhil.yt.db.entities.SongEntity
import com.nikhil.yt.models.MediaMetadata
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadMetadataTest {
    @Test fun creatingASongOrAddingItToTheLibraryDoesNotClaimItWasDownloaded() {
        assertNull(SongEntity(id = "song", title = "Song").dateDownload)
        val metadata = MediaMetadata("song", "Song", emptyList(), 120)
        assertNull(metadata.toSongEntity().dateDownload)
    }
}
