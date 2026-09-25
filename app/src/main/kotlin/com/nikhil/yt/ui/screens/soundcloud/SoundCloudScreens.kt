package com.nikhil.yt.ui.screens.soundcloud

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.nikhil.yt.*
import com.nikhil.yt.playback.queues.SoundCloudQueue
import com.nikhil.yt.soundcloud.*
import com.nikhil.yt.ui.component.*
import com.nikhil.yt.ui.menu.SoundCloudTrackMenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

private fun NavController.scProfile(url:String)=navigate("soundcloud/profile?url="+Uri.encode(url))
private fun NavController.scPlaylist(url:String)=navigate("soundcloud/playlist?url="+Uri.encode(url))

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SoundCloudProfileScreen(url:String,navController:NavController){
    var result by remember(url){mutableStateOf<SoundCloudCatalog.Result<SoundCloudCatalog.Profile>?>(null)}
    val pc=LocalPlayerConnection.current
    val menu=LocalMenuState.current
    val downloads by LocalDownloadUtil.current.downloads.collectAsState()
    val meta=pc?.mediaMetadata?.collectAsState()?.value
    val playing=pc?.isPlaying?.collectAsState()?.value==true
    val downloaded=downloads.filterValues{it.state==Download.STATE_COMPLETED}.keys
    LaunchedEffect(url){result=runInterruptible(Dispatchers.IO){SoundCloudCatalog.profile(url)}}
    Column{
        TopAppBar(title={Text(stringResource(R.string.capsule_soundcloud_account_title))},
            navigationIcon={IconButton({navController.navigateUp()}){Icon(painterResource(R.drawable.arrow_back),null)}})
        LazyColumn(contentPadding=LocalPlayerAwareWindowInsets.current.asPaddingValues()){
            when(val v=result){
                null->item{StatusText(R.string.capsule_soundcloud_loading)}
                SoundCloudCatalog.Result.RateLimited->item{StatusText(R.string.capsule_soundcloud_rate_limited)}
                SoundCloudCatalog.Result.Unavailable->item{StatusText(R.string.capsule_soundcloud_request_failed)}
                is SoundCloudCatalog.Result.Success->{
                    val p=v.value
                    item{
                        Column(Modifier.fillMaxWidth().padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally){
                            AsyncImage(p.avatarUrl,null,Modifier.size(112.dp).clip(RoundedCornerShape(56.dp)))
                            Text(p.name,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,
                                modifier=Modifier.padding(top=12.dp))
                            Row(verticalAlignment=Alignment.CenterVertically){
                                Text(stringResource(R.string.capsule_soundcloud_followers,p.followerCount.coerceAtLeast(0)),
                                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.size(6.dp)); SoundCloudSourceIcon()
                            }
                            if(p.description.isNotBlank()) Text(p.description,modifier=Modifier.padding(top=12.dp))
                        }
                    }
                    if(p.tracks.isNotEmpty()) item{Header(stringResource(R.string.capsule_soundcloud_tracks))}
                    items(p.tracks,key={"t:"+it.permalink}){t->
                        val id=soundCloudMediaId(t.permalink)
                        SoundCloudTrackListItem(t,meta?.id==id,playing&&meta?.id==id,
                            {SoundCloudQueue.create(p.name,p.tracks,t.permalink,downloaded)?.let{q->pc?.playQueue(q)}},
                            {navController.scProfile(it)},
                            {menu.show{SoundCloudTrackMenu(t,navController,menu::dismiss)}})
                    }
                    if(p.playlists.isNotEmpty()) item{Header(stringResource(R.string.capsule_soundcloud_playlists))}
                    items(p.playlists,key={"p:"+it.url}){pl->SoundCloudPlaylistListItem(pl){navController.scPlaylist(pl.url)}}
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SoundCloudPlaylistScreen(url:String,navController:NavController){
    var result by remember(url){mutableStateOf<SoundCloudCatalog.Result<SoundCloudCatalog.PlaylistDetails>?>(null)}
    val pc=LocalPlayerConnection.current
    val menu=LocalMenuState.current
    val downloads by LocalDownloadUtil.current.downloads.collectAsState()
    val meta=pc?.mediaMetadata?.collectAsState()?.value
    val playing=pc?.isPlaying?.collectAsState()?.value==true
    val downloaded=downloads.filterValues{it.state==Download.STATE_COMPLETED}.keys
    LaunchedEffect(url){
        result=null
        val initial=runInterruptible(Dispatchers.IO){SoundCloudCatalog.playlist(url)}
        result=initial
        if(initial is SoundCloudCatalog.Result.Success){
            var page=initial.value; var next=page.continuation; var loaded=0
            while(page.tracks.size<100&&loaded<7){
                val req=next?:break
                val more=runInterruptible(Dispatchers.IO){SoundCloudCatalog.playlistMore(url,req)}
                if(more !is SoundCloudCatalog.Result.Success)break
                val c=more.value;if(c.tracks.isEmpty())break
                page=page.copy(tracks=(page.tracks+c.tracks).distinctBy{it.permalink}.take(100),continuation=c.continuation)
                result=SoundCloudCatalog.Result.Success(page);next=c.continuation;loaded++
            }
        }
    }
    Column{
        TopAppBar(title={Text(stringResource(R.string.capsule_soundcloud_playlist_title))},
            navigationIcon={IconButton({navController.navigateUp()}){Icon(painterResource(R.drawable.arrow_back),null)}})
        LazyColumn(contentPadding=LocalPlayerAwareWindowInsets.current.asPaddingValues()){
            when(val v=result){
                null->item{StatusText(R.string.capsule_soundcloud_loading)}
                SoundCloudCatalog.Result.RateLimited->item{StatusText(R.string.capsule_soundcloud_rate_limited)}
                SoundCloudCatalog.Result.Unavailable->item{StatusText(R.string.capsule_soundcloud_request_failed)}
                is SoundCloudCatalog.Result.Success->{
                    val p=v.value
                    item{
                        Column(Modifier.fillMaxWidth().padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally){
                            AsyncImage(p.artworkUrl,null,Modifier.size(180.dp).clip(RoundedCornerShape(16.dp)))
                            Text(p.title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,
                                modifier=Modifier.padding(top=14.dp))
                            Row(verticalAlignment=Alignment.CenterVertically,
                                modifier=Modifier.clickable(enabled=p.uploaderUrl!=null){
                                    p.uploaderUrl?.let(navController::scProfile)}.padding(vertical=6.dp)){
                                Text(p.uploader,color=MaterialTheme.colorScheme.primary);Spacer(Modifier.size(6.dp));SoundCloudSourceIcon()
                            }
                            Text(stringResource(R.string.capsule_soundcloud_playable_tracks,p.tracks.size,p.trackCount.coerceAtLeast(0)),
                                color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(enabled=p.tracks.isNotEmpty()&&pc!=null,onClick={
                                SoundCloudQueue.create(p.title,p.tracks,null,downloaded)?.let{q->pc?.playQueue(q)}
                            },modifier=Modifier.padding(top=12.dp)){Text(stringResource(R.string.capsule_soundcloud_play_all))}
                        }
                    }
                    items(p.tracks,key={"t:"+it.permalink}){t->
                        val id=soundCloudMediaId(t.permalink)
                        SoundCloudTrackListItem(t,meta?.id==id,playing&&meta?.id==id,
                            {SoundCloudQueue.create(p.title,p.tracks,t.permalink,downloaded)?.let{q->pc?.playQueue(q)}},
                            {navController.scProfile(it)},
                            {menu.show{SoundCloudTrackMenu(t,navController,menu::dismiss)}})
                    }
                    if(p.tracks.isEmpty())item{StatusText(R.string.capsule_soundcloud_no_playable_tracks)}
                }
            }
        }
    }
}
@Composable private fun Header(t:String)=Text(t,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,
    modifier=Modifier.padding(horizontal=20.dp,vertical=12.dp))
@Composable private fun StatusText(r:Int)=Text(stringResource(r),color=MaterialTheme.colorScheme.onSurfaceVariant,
    modifier=Modifier.padding(20.dp))
