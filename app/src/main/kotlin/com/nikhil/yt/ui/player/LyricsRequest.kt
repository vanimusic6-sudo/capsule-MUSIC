/**
 * Capsule MUSIC
 * Fetching a track's lyrics once, for whoever needs them.
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.nikhil.yt.db.entities.LyricsEntity
import com.nikhil.yt.LocalDatabase
import com.nikhil.yt.di.LyricsHelperEntryPoint
import com.nikhil.yt.models.MediaMetadata
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * A short wait before asking, so skipping through tracks does not fetch every one of them.
 * The effect is cancelled by a track change, which is what makes the wait work.
 */
private const val REQUEST_DELAY_MS = 800L

/**
 * Media ids currently being fetched, across the whole app.
 *
 * Two surfaces can want the same lyrics at the same moment — the sounding line under the artwork
 * and the lyrics sheet opened over it — and without this they would both go to the network for the
 * same track. Whoever gets here first does the work; the other waits for the database to tell it.
 */
private val inFlight: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

/**
 * Makes sure [mediaMetadata] has lyrics in the database, fetching them once if it does not.
 *
 * This used to live inside the lyrics screen, which meant lyrics existed only for tracks whose
 * lyrics screen had been opened — anything else that wanted to show them, such as the sounding line
 * under Capsule Light's artwork, sat waiting for a request nobody was going to make.
 *
 * It is a one-shot per track: no polling, and nothing left running once the request is answered.
 */
@Composable
internal fun RequestLyricsIfMissing(
    mediaMetadata: MediaMetadata,
    currentLyrics: LyricsEntity?,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current

    LaunchedEffect(mediaMetadata.id, currentLyrics) {
        if (currentLyrics != null) return@LaunchedEffect

        delay(REQUEST_DELAY_MS)

        val id = mediaMetadata.id
        if (!inFlight.add(id)) return@LaunchedEffect
        try {
            val lyrics =
                withContext(Dispatchers.IO) {
                    val entryPoint =
                        EntryPointAccessors.fromApplication(
                            context.applicationContext,
                            LyricsHelperEntryPoint::class.java,
                        )
                    entryPoint.lyricsHelper().getLyrics(mediaMetadata)
                }

            withContext(Dispatchers.IO) {
                database.query { upsert(LyricsEntity(id, lyrics)) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A missing lyric is an expected result; manual refetch stays available.
        } finally {
            inFlight.remove(id)
        }
    }
}
