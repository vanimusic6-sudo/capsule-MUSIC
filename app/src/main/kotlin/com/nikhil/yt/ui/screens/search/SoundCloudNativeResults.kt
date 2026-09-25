package com.nikhil.yt.ui.screens.search

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R
import com.nikhil.yt.soundcloud.SoundCloudCatalog
import com.nikhil.yt.ui.component.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible

internal enum class SoundCloudResultKind { ALL, TRACKS, PLAYLISTS, USERS }

@Composable
internal fun SoundCloudNativeResults(
    query: String,
    kind: SoundCloudResultKind,
    selectedUrl: String?,
    playing: Boolean,
    onTrackClick: (SoundCloudCatalog.Track, List<SoundCloudCatalog.Track>) -> Unit,
    onTrackMenu: (SoundCloudCatalog.Track) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
) {
    var result by remember(query) { mutableStateOf<SoundCloudCatalog.Result<SoundCloudCatalog.SearchPage>?>(null) }
    LaunchedEffect(query) {
        val q=query.trim()
        if(q.isBlank()) {
            result=SoundCloudCatalog.Result.Success(SoundCloudCatalog.SearchPage(emptyList(),emptyList(),emptyList(),null))
            return@LaunchedEffect
        }
        delay(280)
        val initial=runInterruptible(Dispatchers.IO){SoundCloudCatalog.search(q)}
        result=initial
        if(initial is SoundCloudCatalog.Result.Success) {
            var page=initial.value
            var next=page.continuation
            var loaded=0
            while(next!=null && page.tracks.size<50 && loaded<5) {
                val request=next ?: break
                val more=runInterruptible(Dispatchers.IO){SoundCloudCatalog.searchMore(request)}
                if(more !is SoundCloudCatalog.Result.Success) break
                val c=more.value
                page=page.copy(
                    tracks=(page.tracks+c.tracks).distinctBy{it.permalink}.take(50),
                    users=(page.users+c.users).distinctBy{it.url}.take(20),
                    playlists=(page.playlists+c.playlists).distinctBy{it.url}.take(20),
                    continuation=c.continuation
                )
                result=SoundCloudCatalog.Result.Success(page); next=c.continuation; loaded++
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        when(val v=result) {
            null -> Status(R.string.capsule_soundcloud_loading)
            SoundCloudCatalog.Result.RateLimited -> Status(R.string.capsule_soundcloud_rate_limited)
            SoundCloudCatalog.Result.Unavailable -> Status(R.string.capsule_soundcloud_request_failed)
            is SoundCloudCatalog.Result.Success -> {
                val p=v.value
                if(kind==SoundCloudResultKind.ALL || kind==SoundCloudResultKind.TRACKS) {
                    if(p.tracks.isNotEmpty()) SectionTitle(stringResource(R.string.capsule_soundcloud_tracks))
                    p.tracks.forEach { t -> SoundCloudTrackListItem(t,t.permalink==selectedUrl,playing&&t.permalink==selectedUrl,
                        {onTrackClick(t,p.tracks)},onArtistClick,{onTrackMenu(t)}) }
                }
                if(kind==SoundCloudResultKind.ALL || kind==SoundCloudResultKind.PLAYLISTS) {
                    if(p.playlists.isNotEmpty()) SectionTitle(stringResource(R.string.capsule_soundcloud_playlists))
                    p.playlists.forEach { pl -> SoundCloudPlaylistListItem(pl){onPlaylistClick(pl.url)} }
                }
                if(kind==SoundCloudResultKind.ALL || kind==SoundCloudResultKind.USERS) {
                    if(p.users.isNotEmpty()) SectionTitle(stringResource(R.string.capsule_soundcloud_accounts))
                    p.users.forEach { u -> SoundCloudUserListItem(
                        u,stringResource(R.string.capsule_soundcloud_followers,u.followerCount.coerceAtLeast(0))){onUserClick(u.url)} }
                }
                val empty=when(kind){
                    SoundCloudResultKind.ALL -> p.tracks.isEmpty()&&p.playlists.isEmpty()&&p.users.isEmpty()
                    SoundCloudResultKind.TRACKS -> p.tracks.isEmpty()
                    SoundCloudResultKind.PLAYLISTS -> p.playlists.isEmpty()
                    SoundCloudResultKind.USERS -> p.users.isEmpty()
                }
                if(empty) Status(R.string.capsule_soundcloud_no_results)
            }
        }
        HorizontalDivider(Modifier.padding(top=8.dp))
    }
}
@Composable private fun SectionTitle(t:String)=Text(t,color=MaterialTheme.colorScheme.onSurface,
    style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold,
    modifier=Modifier.padding(start=20.dp,top=14.dp,bottom=4.dp))
@Composable private fun Status(r:Int)=Text(stringResource(r),color=MaterialTheme.colorScheme.onSurfaceVariant,
    style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(horizontal=20.dp,vertical=12.dp))
