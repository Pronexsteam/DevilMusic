package dev.devilmusic.androidproof

import android.os.Process
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import dev.devilmusic.proof.QueueTrace
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** The service owns audio. Activity/controller recreation must not create another player. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var playbackTools:PlaybackTools?=null
    private var queueShuffle:QueueShuffle?=null
    private var cachedTracks=emptyList<SavedTrack>()
    private var tracksDirty=true
    private val online = OnlineSession.resolver
    private lateinit var storage:MusicStorage
    private val stateWorker=java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private var queueGeneration=0
    private var destroyed=false
    private var stateStatus="loading"
    private var lastHeard:SavedTrack?=null
    private val listening=ListeningMeter {track,signal,at->stateWorker.submit {runCatching {storage.taste.record(track,signal,at)}}}
    private fun sampleTaste(player:Player) {
        val item=player.currentMediaItem
        val track=item?.let {SavedTrack(it.mediaId,it.mediaMetadata.title?.toString() ?: "Audio",it.mediaMetadata.artist?.toString() ?: "",it.mediaMetadata.artworkUri?.toString())}
        listening.sample(track,player.isPlaying,player.duration,android.os.SystemClock.elapsedRealtime(),System.currentTimeMillis(),player.playerError!=null)
    }
    private val persistenceGate=QueuePersistenceGate()
    private val saveState=Runnable {session?.player?.let(::persist)}
    private val periodicSave=object:Runnable{override fun run(){session?.player?.let {sampleTaste(it);if(it.isPlaying)persist(it)};if(!destroyed)main.postDelayed(this,15_000)}}
    private fun persist(player:Player) {
        if(!persistenceGate.canSave())return
        if(tracksDirty){cachedTracks=(0 until player.mediaItemCount).map {index->val item=player.getMediaItemAt(index);SavedTrack(item.mediaId,item.mediaMetadata.title?.toString() ?: "Audio",item.mediaMetadata.artist?.toString() ?: "",item.mediaMetadata.artworkUri?.toString())};tracksDirty=false}
        val snapshot=SavedQueue(cachedTracks,player.currentMediaItem?.mediaId,player.currentPosition,player.shuffleModeEnabled,player.repeatMode,player.playbackParameters.speed,queueShuffle?.snapshot().orEmpty())
        stateWorker.submit {val ok=runCatching {storage.state.save(snapshot)}.isSuccess;main.post {stateStatus=if(ok)"saved" else "save_failed"}}
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        storage=MusicStorage(this)
        val sources=DefaultMediaSourceFactory(androidx.media3.datasource.DataSource.Factory {
            OnlinePlaybackDataSource(this,online) { id, resolved -> Handler(Looper.getMainLooper()).post {
                session?.player?.let { player ->
                    for(index in 0 until player.mediaItemCount) {
                        val item=player.getMediaItemAt(index)
                        if(item.mediaId=="youtube:$id" && (item.mediaMetadata.title?.toString()!=resolved.title || item.mediaMetadata.artist?.toString()!=resolved.artist || (item.mediaMetadata.artworkUri==null && resolved.artwork!=null))) player.replaceMediaItem(index,item.buildUpon().setMediaMetadata(
                            item.mediaMetadata.buildUpon().setTitle(resolved.title).setArtist(resolved.artist)
                                .setArtworkUri(item.mediaMetadata.artworkUri ?: resolved.artwork?.let(android.net.Uri::parse)).build()).build())
                    }
                }
            } }
        }).setLoadErrorHandlingPolicy(OnlineLoadErrorPolicy())
        val renderers=object:androidx.media3.exoplayer.DefaultRenderersFactory(this){
            override fun buildAudioSink(context:android.content.Context,enableFloatOutput:Boolean,enableAudioOutputPlaybackParams:Boolean):androidx.media3.exoplayer.audio.AudioSink? =
                super.buildAudioSink(context,enableFloatOutput,enableAudioOutputPlaybackParams)?.let(::EnergyAudioSink)
        }
        val player = ExoPlayer.Builder(this,renderers).setMediaSourceFactory(sources).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    tracksDirty=true
                    queueGeneration++
                    PlaybackTrace.record(QueueTrace.Scope.SERVICE, QueueTrace.Event.TIMELINE, this@apply, reason = reason)
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    listening.finish(android.os.SystemClock.elapsedRealtime(),System.currentTimeMillis(),reason==Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
                    PlaybackTrace.record(QueueTrace.Scope.SERVICE, QueueTrace.Event.TRANSITION, this@apply, reason = reason)
                }
                override fun onEvents(player:Player,events:Player.Events){
                    sampleTaste(player)
                    if(player.playbackState==Player.STATE_ENDED)listening.finish(android.os.SystemClock.elapsedRealtime(),System.currentTimeMillis(),false)
                    main.removeCallbacks(saveState);main.postDelayed(saveState,700)
                    if(player.isPlaying)player.currentMediaItem?.let {item->
                        val heard=SavedTrack(item.mediaId,item.mediaMetadata.title?.toString() ?: "Audio",item.mediaMetadata.artist?.toString() ?: "",item.mediaMetadata.artworkUri?.toString())
                        if(lastHeard!=heard){lastHeard=heard;stateWorker.submit {runCatching {storage.state.recordPlayed(heard)}}}
                    }
                }
            })
        }
        queueShuffle=QueueShuffle(player){player.setShuffleOrder(it)}
        PlaybackTrace.serviceSnapshot = {
            val counters=player.audioDecoderCounters
            counters?.ensureUpdated()
            "Media3 service\nstate=${player.playbackState}\nisPlaying=${player.isPlaying}\npositionMs=${player.currentPosition}\nroute=${if(player.currentMediaItem?.mediaId?.startsWith("youtube:")==true) "NewPipe / native HTTP" else "local"}\nrenderedAudioBuffers=${counters?.renderedOutputBufferCount ?: 0}\nerrorCode=${player.playerError?.errorCode ?: 0}\npersistence=$stateStatus\n${PlaybackTrace.onlineFailureReport(player.currentMediaItem?.mediaId)}"
        }
        session = MediaSession.Builder(this, player).setCallback(object : MediaSession.Callback {
            override fun onPlayerCommandRequest(session:MediaSession,controller:MediaSession.ControllerInfo,playerCommand:Int):Int {
                if(controller.uid==Process.myUid() && playerCommand==Player.COMMAND_CHANGE_MEDIA_ITEMS)persistenceGate.edited()
                return super.onPlayerCommandRequest(session,controller,playerCommand)
            }
            @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                if(controller.uid==Process.myUid())return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(androidx.media3.session.SessionCommand(PlaybackTools.COMMAND,android.os.Bundle.EMPTY)).build()).build()
                return if(controller.isTrusted)super.onConnect(session,controller) else MediaSession.ConnectionResult.reject()
            }
            override fun onCustomCommand(session:MediaSession,controller:MediaSession.ControllerInfo,command:androidx.media3.session.SessionCommand,args:android.os.Bundle):ListenableFuture<androidx.media3.session.SessionResult>{
                if(controller.uid==Process.myUid() && command.customAction==PlaybackTools.COMMAND && args.getString("action") in setOf("remove","undo","next","move"))persistenceGate.edited()
                val result=if(controller.uid!=Process.myUid())androidx.media3.session.SessionResult(androidx.media3.session.SessionError.ERROR_PERMISSION_DENIED)
                    else if(command.customAction==PlaybackTools.COMMAND)playbackTools?.command(args) ?: androidx.media3.session.SessionResult(androidx.media3.session.SessionError.ERROR_INVALID_STATE)
                    else androidx.media3.session.SessionResult(androidx.media3.session.SessionError.ERROR_NOT_SUPPORTED)
                return Futures.immediateFuture(result)
            }
            override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
                // External controllers can transport-control the session, not inject streams/URIs.
                if (controller.uid != Process.myUid()) return Futures.immediateFailedFuture(SecurityException("Queue modification is private"))
                val grants = contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
                // Media3 IPC may omit localConfiguration. Reconstruct only our strict logical ID,
                // never a URL supplied through metadata/extras by a controller.
                val normalized=mediaItems.mapNotNull {storage.normalize(it,grants,packageName)}
                val valid=normalized.size==mediaItems.size
                return if (valid) Futures.immediateFuture(normalized.distinctBy {tasteId(it.mediaId)})
                else Futures.immediateFailedFuture(SecurityException("Source has no retained document grant"))
            }
        }).build()
        playbackTools=PlaybackTools(this,player,queueShuffle!!){session?.setSessionExtras(it)}.also {it.emit()}
        val generation=queueGeneration
        stateWorker.submit {
            val result=runCatching {
                val saved=storage.state.queue()
                val grants=contentResolver.persistedUriPermissions.filter {it.isReadPermission}.map {it.uri.toString()}.toSet()
                saved to (saved?.tracks?.mapNotNull {storage.normalize(storage.item(it),grants,packageName)}?.distinctBy {tasteId(it.mediaId)} ?: emptyList())
            }
            main.post {
                if(destroyed)return@post
                val saved=result.getOrNull()?.first;val items=result.getOrNull()?.second.orEmpty()
                var restored=false
                if(!destroyed && persistenceGate.canRestore() && queueGeneration==generation && player.mediaItemCount==0 && saved!=null && items.isNotEmpty()) {
                    val index=items.indexOfFirst {tasteId(it.mediaId)==saved.currentId?.let(::tasteId)}
                    queueShuffle?.restore(saved,items,index);player.repeatMode=saved.repeat
                    player.setPlaybackParameters(androidx.media3.common.PlaybackParameters(saved.speed,1f))
                    stateStatus=if(items.size==saved.tracks.size)"restored_paused" else "restored_paused_skipped_${saved.tracks.size-items.size}"
                    restored=true
                } else stateStatus=when {result.isFailure->"restore_failed_preserved";saved?.tracks?.isNotEmpty()==true && player.mediaItemCount==0->"restore_skipped_preserved";else->"ready"}
                persistenceGate.loaded(result.isSuccess && (saved==null || saved.tracks.isEmpty() || restored))
                if(!destroyed)main.postDelayed(periodicSave,15_000)
            }
        }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() {
        destroyed=true
        session?.player?.let(::sampleTaste)
        listening.finish(android.os.SystemClock.elapsedRealtime(),System.currentTimeMillis(),false)
        session?.player?.let(::persist)
        main.removeCallbacksAndMessages(null)
        PlaybackTrace.serviceSnapshot=null
        playbackTools?.release();playbackTools=null
        queueShuffle?.release();queueShuffle=null
        session?.run { player.release(); release() }
        session = null
        stateWorker.shutdown()
        super.onDestroy()
    }
}




