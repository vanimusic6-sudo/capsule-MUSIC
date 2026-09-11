from pathlib import Path
import re

path = Path('app/src/main/kotlin/com/nikhil/yt/playback/MusicService.kt')
text = path.read_text(encoding='utf-8')
original = text

old_imports = '''import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
'''
new_imports = '''import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
'''
if old_imports not in text:
    raise SystemExit('Coroutine import anchor not found')
text = text.replace(old_imports, new_imports, 1)

old_scope = '''    private var scopeJob = Job()
    private var scope = CoroutineScope(Dispatchers.Main + scopeJob)
    private var ioScope = CoroutineScope(Dispatchers.IO + scopeJob)
    private val binder = MusicBinder()
'''
new_scope = '''    private var scopeJob = Job()
    private var scope = CoroutineScope(Dispatchers.Main + scopeJob)
    private var ioScope = CoroutineScope(Dispatchers.IO + scopeJob)
    private val songMutationMutex = Mutex()
    private val binder = MusicBinder()
'''
if old_scope not in text:
    raise SystemExit('Scope anchor not found')
text = text.replace(old_scope, new_scope, 1)

pattern = re.compile(
    r'''    private fun toggleLibrary\(\) \{.*?^    fun toggleStartRadio\(\) \{''',
    re.MULTILINE | re.DOTALL,
)
match = pattern.search(text)
if not match:
    raise SystemExit('toggleLibrary/toggleLike block not found')

replacement = '''    private fun activeSongMetadata(): com.nikhil.yt.models.MediaMetadata? =
        player.currentMetadata
            ?: currentMediaMetadata.value
            ?: player.currentMediaItem?.metadata

    private fun activeSongId(metadata: com.nikhil.yt.models.MediaMetadata? = activeSongMetadata()): String? =
        metadata?.id
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: player.currentMediaItem
                ?.mediaId
                ?.trim()
                ?.takeIf { it.isNotBlank() }

    private suspend fun ensureSongForMutation(
        mediaId: String,
        metadata: com.nikhil.yt.models.MediaMetadata?,
    ): Song? {
        var current = database.getSongById(mediaId)
        if (current != null) return current

        val sourceMetadata =
            metadata
                ?.takeIf { it.id.trim() == mediaId }
                ?: return null

        database.insert(sourceMetadata)
        current = database.getSongById(mediaId)
        return current
    }

    private fun toggleLibrary() {
        val metadata = activeSongMetadata()
        val mediaId = activeSongId(metadata) ?: return

        ioScope.launch {
            songMutationMutex.withLock {
                database.withTransaction {
                    val current = ensureSongForMutation(mediaId, metadata) ?: return@withTransaction
                    update(current.song.toggleLibrary())
                }
            }
        }
    }

    fun toggleLike(source: String = "service") {
        val metadata = activeSongMetadata()
        val mediaId = activeSongId(metadata)

        Timber.tag("MusicService").i(
            "Toggle like requested id=%s source=%s",
            mediaId,
            source,
        )

        if (mediaId == null) {
            Timber.tag("MusicService").w("Toggle like ignored: no active media id source=%s", source)
            return
        }

        ioScope.launch {
            songMutationMutex.withLock {
                val updatedSong =
                    database.withTransaction {
                        val current = ensureSongForMutation(mediaId, metadata)
                        if (current == null) {
                            Timber.tag("MusicService").w(
                                "Toggle like ignored id=%s: no local row and no usable metadata",
                                mediaId,
                            )
                            return@withTransaction null
                        }

                        val wasLiked = current.song.liked
                        val now = LocalDateTime.now()
                        val updated =
                            current.song.copy(
                                liked = !wasLiked,
                                likedDate = if (!wasLiked) now else null,
                                inLibrary =
                                    if (!wasLiked) {
                                        current.song.inLibrary ?: now
                                    } else {
                                        current.song.inLibrary
                                    },
                            )
                        update(updated)
                        updated
                    } ?: return@withLock

                // Keep one owner for the remote mutation. SongEntity.toggleLike()
                // also calls YouTube directly, so using a pure local copy above
                // prevents duplicate like requests while SyncUtils keeps auth and
                // sync-policy checks in one place.
                syncUtils.likeSong(updatedSong)

                Timber.tag("MusicService").i(
                    "Toggle like applied id=%s liked=%s source=%s",
                    updatedSong.id,
                    updatedSong.liked,
                    source,
                )

                if (dataStore.get(AutoDownloadOnLikeKey, false) && updatedSong.liked) {
                    val downloadRequest =
                        androidx.media3.exoplayer.offline.DownloadRequest
                            .Builder(updatedSong.id, updatedSong.id.toUri())
                            .setCustomCacheKey(updatedSong.id)
                            .setData(updatedSong.title.toByteArray())
                            .build()
                    androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(
                        this@MusicService,
                        ExoDownloadService::class.java,
                        downloadRequest,
                        false,
                    )
                }
            }
        }
    }

    fun toggleStartRadio() {'''

text = text[:match.start()] + replacement + text[match.end():]

if text == original:
    raise SystemExit('Patch made no changes')
if 'private val songMutationMutex = Mutex()' not in text:
    raise SystemExit('Mutex patch missing')
if 'Toggle like applied id=%s liked=%s source=%s' not in text:
    raise SystemExit('Like apply log missing')
if 'val song = it.song.toggleLike()' in text:
    raise SystemExit('Old duplicate remote like path still present')

path.write_text(text, encoding='utf-8')
print('Applied final like-state fix')
