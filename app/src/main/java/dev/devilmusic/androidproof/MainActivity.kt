package dev.devilmusic.androidproof

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ArrayAdapter
import android.view.KeyEvent
import android.window.OnBackInvokedDispatcher
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.devilmusic.proof.PlaybackPolicy
import dev.devilmusic.proof.QueuePresentation
import dev.devilmusic.proof.queuePositionForId
import dev.devilmusic.proof.QueueTrace
import androidx.media3.common.Timeline
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Local music UI; the service remains the only playback owner. */
class MainActivity : Activity() {
    private var controller: MediaController? = null
    private var connection: ListenableFuture<MediaController>? = null
    private lateinit var screen: MusicProofScreen
    private lateinit var metadata: LocalAudioMetadata
    private lateinit var storage:MusicStorage
    private val catalogWorker=Executors.newSingleThreadExecutor()
    private var catalogTask:Future<*>?=null
    private var catalogGeneration=0
    private var catalogNext:(()->CatalogPage)?=null
    private var catalogPages=emptyList<MusicShelf>()
    private var moreBusy=false
    private var homeShelves:List<MusicShelf> = emptyList()
    private var homeVisit=System.currentTimeMillis()/1_800_000L
    private var lastHomeNetwork=0L
    private var section="home"
    private var activeCollection:ImportedCollection?=null
    private var artistReturnSection:String?=null
    private var artistReturnCollection:String?=null
    private var artistCard:MusicCard?=null
    private var favoriteIds=emptySet<String>()
        set(value){field=value;if(::screen.isInitialized)screen.favorites(value)}
    private val favorites by lazy {FavoritesStore(java.io.File(filesDir,"music-state"))}
    private var catalogLoading=false
    private var lastCatalogRequest=Triple("home","","")
    private val personalWorker=Executors.newSingleThreadExecutor()
    private var personalGeneration=0
    private val suggestionWorker=Executors.newSingleThreadExecutor()
    private var suggestionTask:Future<*>?=null
    private var suggestionGeneration=0
    private var pendingSuggestion:Runnable?=null
    private val suggestionCache=object:LinkedHashMap<String,List<MusicCard>>(8,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,List<MusicCard>>?)=size>8}
    private var recentTracks=emptyList<SavedTrack>()
    private var sessionRecent=emptyList<SavedTrack>()
    private var pinnedAlbums=emptyList<MusicCard>()
    private fun personalCard(t:SavedTrack)=MusicCard(t.id,when {t.id.startsWith("download:")->"download";t.id.startsWith("youtube:")->"online";else->"local"},t.title,t.artist,t.artwork)
    private fun showPersonal(){screen.personal((sessionRecent+recentTracks).distinctBy {tasteId(it.id)}.take(80).map(::personalCard),pinnedAlbums)}
    private fun loadPersonal() {
        val generation=++personalGeneration
        personalWorker.submit {
            val recent=runCatching {storage.state.recent()}.getOrDefault(emptyList())
            val pins=runCatching {storage.state.pinnedAlbums()}.getOrDefault(emptyList())
            val liked=runCatching {favorites.all().map {tasteId(it.id)}.toSet()}.getOrDefault(emptySet())
            val arrangement=storage.ui.home();val history=storage.ui.history()
            val grants=contentResolver.persistedUriPermissions.filter {it.isReadPermission}.map {it.uri.toString()}.toSet()
            val available=recent.filter {storage.normalize(storage.item(it),grants,packageName)!=null}
            runOnUiThread {if(active && generation==personalGeneration){recentTracks=available;pinnedAlbums=pins;favoriteIds=liked;showPersonal();screen.preferences(arrangement,history)}}
        }
    }
    private val lyricsWorker=Executors.newSingleThreadExecutor()
    private var lyricsTask:Future<*>?=null
    private var lyricsKey=""
    private var lyricsGeneration=0
    private var lyricsImportTrack:String?=null
    private var downloadJob:DownloadJob?=null
    private val namesWorker = Executors.newSingleThreadExecutor()
    private val artWorker = Executors.newSingleThreadExecutor()
    private val searchWorker = Executors.newSingleThreadExecutor()
    private var searchTask: Future<*>? = null
    private var searchGeneration = 0
    private var searchDialog: AlertDialog? = null
    private var namesTask: Future<*>? = null
    private var artTask: Future<*>? = null
    private var queryCancellation: CancellationSignal? = null
    private var selectionGeneration = 0
    private var artGeneration = 0
    private var currentId: String? = null
    private var currentArtworkUri:String?=null
    private var currentTags: LocalAudioMetadata.Current? = null
    private val queuePresentation = QueuePresentation()
    private var queueIndexes=emptyMap<String,Int>()
    private var pendingItems: List<MediaItem> = emptyList()
    private var active = false
    private var updateChecking=false
    private var availableUpdate:UpdateRelease?=null
    private val installedVersion by lazy {packageManager.getPackageInfo(packageName,0).versionName ?: "unknown"}
    private fun checkAppUpdate(manual:Boolean=false){
        if(updateChecking)return
        updateChecking=true
        if(manual)android.widget.Toast.makeText(this,"Checking for updates…",android.widget.Toast.LENGTH_SHORT).show()
        AppUpdates.check(applicationContext,installedVersion,manual).whenComplete {result,error->runOnUiThread {
            updateChecking=false
            if(!isDestroyed && !isFinishing){
                availableUpdate=result?.available;screen.updateAvailable(availableUpdate?.label)
                if(manual && active)showAppUpdate(error!=null || result?.failed==true)
            }
        }}
    }
    private fun showAppUpdate(failed:Boolean=false){
        UpdateSheet.show(this,installedVersion,availableUpdate,failed,{url->
            runCatching {startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)))}.onFailure {android.widget.Toast.makeText(this,"No browser available to download the APK",android.widget.Toast.LENGTH_LONG).show()}
        },{checkAppUpdate(true)})
    }
    private var runtimeProbe: AndroidRuntimeProbe? = null
    private var fixtureCheck: Future<*>? = null
    private var fixtureCheckRunning = false
    private var fixtureGeneration = 0
    private val handler = Handler(Looper.getMainLooper())
    private var retainedPresentation:PlayerPresentation?=null
    private var pendingPresentation:PlayerPresentation?=null
    private var expandAfterRotation=false
    private val progress = object : Runnable {
        override fun run() { if (active) { controller?.let {screen.tools(it.sessionExtras)};screen.updateProgress(controller); updateDownload();if(screen.wantsLyrics())loadLyrics(); handler.postDelayed(this, if(screen.wantsLyrics())100 else 500) } }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OnlineArtwork.disk=ArtworkDiskCache(java.io.File(cacheDir,"covers-v1"))
        OnlineArtwork.debugTracing=(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0
        pendingPresentation=(lastNonConfigurationInstance as? PlayerPresentation) ?: savedInstanceState?.let {PlayerPresentation(it.getBoolean("playerOpen"),it.getBoolean("playerWords"),null,null,null,immersed=it.getBoolean("playerImmersed"))}
        section=savedInstanceState?.getString("visibleSection") ?: "home"
        expandAfterRotation=savedInstanceState?.getInt("orientation")==android.content.res.Configuration.ORIENTATION_PORTRAIT && resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE && section!="queue"
        metadata = LocalAudioMetadata(applicationContext)
        storage=MusicStorage(applicationContext)
        screen = MusicProofScreen(this, ::action, { id -> controller?.apply {
            prioritizePlayback()
            val index = queueIndexes[id]?.takeIf {it<mediaItemCount && getMediaItemAt(it).mediaId==id}
            PlaybackTrace.record(QueueTrace.Scope.CONTROLLER, QueueTrace.Event.SELECT, this, id, index)
            if (index != null) { seekToDefaultPosition(index); prepare(); play(); PlaybackTrace.record(QueueTrace.Scope.CONTROLLER, QueueTrace.Event.AFTER_SELECT, this, id, index) }
        } }, { position -> controller?.seekTo(position) })
        screen.onMusicOpen={card->when(card.kind){"liked","saved_playlist","all_tracks"->openSavedCollection(card.id);"artist"->openArtist(card.title,card);"song"->playOnline(card.track());"online","local","download"->playSaved(SavedTrack(card.id,card.title,card.subtitle,card.artwork));else->loadCatalog("browse",card.id,card.title,params=card.params)}}
        screen.onMusicSearch={q,k->cancelSuggestions();personalWorker.submit {storage.ui.remember(q);val home=storage.ui.home();val history=storage.ui.history();runOnUiThread {if(active)screen.preferences(home,history)}};val id=YouTubeInput.fromSearchInput(q);if(id!=null)playOnline(CatalogTrack(id,"YouTube audio","Loading details",null))else loadCatalog(k,q)}
        screen.onSuggest=::suggest
        screen.onArtist={openArtist(it)}
        screen.onTrackMenu=::trackMenu
        screen.onQueueMenu={id->controller?.let {p->(0 until p.mediaItemCount).firstOrNull {p.getMediaItemAt(it).mediaId==id}?.let {i->itemCard(p.getMediaItemAt(i))?.let(::trackMenu)}}}
        screen.onHomeChange={value->personalWorker.submit {val ok=runCatching {storage.ui.home(value)}.isSuccess;runOnUiThread {if(active && !ok)screen.message("Could not save Home layout")}}}
        screen.onClearSearch={personalWorker.submit {storage.ui.clearHistory()}}
        screen.onQueueMove={source,target->toolCommand("move"){putString("id",source);putString("target",target)}}
        screen.onMusicPin={card->personalWorker.submit {
            val result=runCatching {storage.state.toggleAlbum(card)}
            runOnUiThread {if(active){android.widget.Toast.makeText(this,if(result.isFailure)"Could not save collection" else if(result.getOrDefault(false))"Pinned to Home" else "Unpinned",android.widget.Toast.LENGTH_SHORT).show();loadPersonal()}}
        }}
        val root = FrameLayout(this).apply { setBackgroundColor(MusicStyle.background);addView(screen, FrameLayout.LayoutParams(-1, -1)) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)
        if(section!="home")screen.showSection(section)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (!screen.closePlayer()) {if(artistReturnSection!=null)catalogBack() else finish()}
            }
        }
    }
    override fun onSaveInstanceState(outState:Bundle){
        if(retainedPresentation==null)retainedPresentation=screen.presentation()
        outState.putString("visibleSection",section)
        outState.putInt("orientation",resources.configuration.orientation)
        outState.putBoolean("playerOpen",retainedPresentation?.open==true)
        outState.putBoolean("playerWords",retainedPresentation?.words==true)
        outState.putBoolean("playerImmersed",retainedPresentation?.immersed==true)
        super.onSaveInstanceState(outState)
    }
    @Deprecated("Configuration-only presentation snapshot")
    override fun onRetainNonConfigurationInstance():Any?=retainedPresentation
    private fun action(name: String) {
        if(name.startsWith("artist:")){openArtist(name.substringAfter(':'));return}
        val player = controller
        if(name in setOf("toggle","previous","next"))prioritizePlayback()
        if(name.startsWith("section:")) {artistReturnSection=null;artistReturnCollection=null;section=name.substringAfter(':');screen.showSection(section);catalogTask?.cancel(true);catalogGeneration++;catalogLoading=false;catalogNext=null;moreBusy=false;screen.catalogMore(false);when(section){"home"->loadCatalog("home");"library","downloads"->loadLibrary(section=="downloads")};return}
        when (name) {
            "catalog_more" -> loadMore()
            "home" -> {artistReturnSection=null;artistReturnCollection=null;loadCatalog("home")}
            "catalog_back" -> catalogBack()
            "refresh_home" -> loadCatalog("home",force=true)
            "retry_catalog" -> {
                if(section=="library" || section=="downloads")loadLibrary(section=="downloads")
                else {val request=lastCatalogRequest;if(request.first=="home")homeShelves=emptyList();loadCatalog(request.first,request.second,request.third)}
            }
            "lyrics" -> {lyricsKey="";loadLyrics()}
            "download" -> downloadCurrent()
            "online" -> openOnline()
            "choose" -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, 10)
            "library_tools" -> libraryTools()
            "playlists" -> activeCollection?.takeIf {it.id.startsWith("android-")}?.let {playlistUi.edit(it.id)} ?: playlistUi.create()
            "save_queue_playlist" -> playlistUi.saveQueue()
            "play_collection" -> activeCollection?.let(::playCollection)
            "like_current" -> player?.currentMediaItem?.let {item->toggleFavorite(SavedTrack(item.mediaId,item.mediaMetadata.title?.toString() ?: "Track",item.mediaMetadata.artist?.toString().orEmpty(),item.mediaMetadata.artworkUri?.toString()))}
            "import_lyrics" -> controller?.currentMediaItem?.mediaId?.let {id->lyricsImportTrack=id;startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {addCategory(Intent.CATEGORY_OPENABLE);type="*/*";addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},21)}
            "clear" -> AlertDialog.Builder(this).setTitle("Clear the queue?").setMessage("Playback will stop. Your library and downloads stay saved.").setNegativeButton("Cancel",null).setPositiveButton("Clear"){_,_->cancelSelection();pendingItems=emptyList();player?.stop();player?.clearMediaItems();refresh(true)}.show()
            "undo" -> toolCommand("undo")
            "sleep" -> sleepMenu()
            "toggle" -> player?.apply { if (isPlaying || (playWhenReady && playerError==null && playbackState!=Player.STATE_ENDED)) pause() else {
                if(playerError!=null && mediaItemCount>0) {
                    // Explicit user retry creates new source bindings. Automatic load recovery
                    // never renews an expired lease; denied IDs remain blocked by the resolver.
                    val items=(0 until mediaItemCount).map(::getMediaItemAt)
                    setMediaItems(items,currentMediaItemIndex.coerceAtLeast(0),currentPosition.coerceAtLeast(0))
                }
                if (playbackState == Player.STATE_ENDED) seekToDefaultPosition(); prepare(); play()
            } }
            "previous" -> player?.apply { PlaybackTrace.record(QueueTrace.Scope.CONTROLLER, QueueTrace.Event.PREVIOUS, this); seekToPrevious() }
            "next" -> player?.apply { PlaybackTrace.record(QueueTrace.Scope.CONTROLLER, QueueTrace.Event.NEXT, this); seekToNextMediaItem() }
            "shuffle" -> player?.apply { shuffleModeEnabled = !shuffleModeEnabled }
            "repeat" -> player?.apply { repeatMode = when (repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } }
            "options" -> playbackMenu()
            "app_update" -> if(availableUpdate!=null)showAppUpdate() else checkAppUpdate(true)
            "advanced_options" -> AlertDialog.Builder(this).setTitle("Playback options")
                .setItems(arrayOf("Speed: 1×", "Speed: 1.5× (pitch unchanged)", "More like this", "Less like this", "Refresh recommendations", "Reset listening taste", "Diagnostics")) { _, which ->
                    when (which) {
                        0, 1 -> controller?.setPlaybackParameters(PlaybackParameters(PlaybackPolicy.speed(if (which == 0) 1f else 1.5f), PlaybackPolicy.PITCH))
                        2, 3 -> feedbackCurrent(if(which==2)1 else -1)
                        4 -> loadCatalog("home",force=true)
                        5 -> AlertDialog.Builder(this).setTitle("Reset listening taste?").setMessage("This clears recommendation preferences only. Your library, downloads and playback settings stay saved.").setNegativeButton("Cancel",null).setPositiveButton("Reset") {_,_->personalWorker.submit {val ok=runCatching {storage.taste.reset();storage.recommendations.reset()}.isSuccess;runOnUiThread {if(active){if(ok)loadCatalog("home")else android.widget.Toast.makeText(this,"Could not reset preferences",android.widget.Toast.LENGTH_SHORT).show()}}}}.show()
                        else -> diagnostics()
                    }
                }.setNegativeButton("Close", null).show()
        }
    }
    private fun toolCommand(action:String,configure:Bundle.()->Unit={}) {
        val p=controller ?: return
        val result=p.sendCustomCommand(androidx.media3.session.SessionCommand(PlaybackTools.COMMAND,Bundle.EMPTY),Bundle().apply {putString("action",action);configure()})
        result.addListener({if(active){val ok=runCatching {result.get().resultCode==androidx.media3.session.SessionResult.RESULT_SUCCESS}.getOrDefault(false);if(!ok)screen.message("Action unavailable. The queue may have changed.");screen.tools(p.sessionExtras);screen.bind(p)}},java.util.concurrent.Executor {handler.post(it)})
    }
    private fun sleepMenu(){
        val p=controller ?: return
        val rows=listOf(1,5,15,30,60).map {minutes->MusicAction(if(minutes==1)"1 minute" else "$minutes minutes","Fade out during the final 3 seconds"){toolCommand("sleep"){putInt("minutes",minutes)}}}+
            listOf(MusicAction("End of this track","Pause before the next song",p.currentMediaItem!=null){toolCommand("sleep"){putBoolean("end",true)}},MusicAction("Turn timer off"){toolCommand("sleep")})
        MusicActionSheet.show(this,"Sleep timer","Works while the player is in the background",listOf("Stop playback" to rows))
    }
    private fun playbackMenu(){
        val p=controller ?: return
        val e=p.sessionExtras;val deadline=e.getLong("sleepDeadline")
        val timer=when {e.getBoolean("sleepEnd")->"End of track";deadline>0->"${((deadline-android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0)+59999)/60000} min remaining";else->"Off"}
        val current=p.currentMediaItem?.let(::itemCard)
        MusicActionSheet.show(this,current?.title ?: "Your player",current?.subtitle.orEmpty(),listOf(
            "This song" to listOf(MusicAction("Song actions",enabled=current!=null){current?.let(::trackMenu)},MusicAction("Open queue"){screen.showSection("queue");action("section:queue")}),
            "Playback" to listOf(MusicAction("Crossfade · local & downloaded",if(e.getBoolean("crossfade"))"On · 3 seconds · normal speed only" else "Off · online songs use preloading"){toolCommand("crossfade"){putBoolean("enabled",!e.getBoolean("crossfade"))}},MusicAction("Sleep timer",timer){sleepMenu()},MusicAction("Load next song's start",if(e.getBoolean("preload",true))"On · target 3 seconds · uses network data" else "Off"){toolCommand("preload"){putBoolean("enabled",!e.getBoolean("preload",true))}},MusicAction("Playback speed","Current: ${p.playbackParameters.speed}×"){MusicActionSheet.show(this,"Playback speed",groups=listOf("Pitch stays unchanged" to listOf(.75f,1f,1.25f,1.5f,2f).map {speed->MusicAction("${speed}×"){p.setPlaybackParameters(PlaybackParameters(speed,1f))}}))}),
            "Lyrics" to listOf(MusicAction("Choose lyrics file","Use real word timings from LRC or Lyricsfile",current!=null){action("import_lyrics")},MusicAction("Use online lyrics","Remove the local override for this song",current!=null){val id=p.currentMediaItem?.mediaId ?: return@MusicAction;lyricsWorker.submit {LocalLyrics(java.io.File(filesDir,"local-lyrics")).remove(id);runOnUiThread {if(active){lyricsKey="";loadLyrics()}}}}),
            "Downloads" to listOf(MusicAction("Retry interrupted download","Restarts the last incomplete file; saved files are preserved",downloadJob?.state in setOf("failed","interrupted","cancelled")){downloadJob?.track?.let {DownloadJobs.start(this,it)}}),
            "More" to listOf(MusicAction(if(availableUpdate!=null)"Update available" else "Check for updates","Installed: $installedVersion"){action("app_update")},MusicAction("Recommendation preferences & diagnostics"){action("advanced_options")})
        ))
    }
    private fun itemCard(item:MediaItem):MusicCard? {
        if(item.mediaId.startsWith("fixture:"))return null
        return MusicCard(item.mediaId,when {item.mediaId.startsWith("youtube:")->"online";item.mediaId.startsWith("download:")->"download";else->"local"},item.mediaMetadata.title?.toString() ?: "Audio",item.mediaMetadata.artist?.toString().orEmpty(),item.mediaMetadata.artworkUri?.toString())
    }
    private fun mediaItem(card:MusicCard):MediaItem? {
        val id=if(card.kind=="song")"youtube:${card.id}" else card.id
        if(!storage.state.validId(id))return null
        return storage.item(SavedTrack(id,card.title,card.subtitle,card.artwork))
    }
    private fun trackMenu(card:MusicCard){
        if(card.kind=="saved_playlist"){if(card.id.startsWith("android-"))playlistUi.edit(card.id) else screen.message("Imported playlist · edit it on your PC");return}
        val p=controller ?: return;val requested=mediaItem(card) ?: return
        val item=(0 until p.mediaItemCount).map {p.getMediaItemAt(it)}.firstOrNull {tasteId(it.mediaId)==tasteId(requested.mediaId)} ?: requested
        val current=p.currentMediaItem?.mediaId==item.mediaId
        val queued=(0 until p.mediaItemCount).any {p.getMediaItemAt(it).mediaId==item.mediaId}
        val saved=SavedTrack(item.mediaId,card.title,card.subtitle,card.artwork)
        fun enqueue(next:Boolean){
            val hadCurrent=p.currentMediaItem!=null
            if((0 until p.mediaItemCount).none {p.getMediaItemAt(it).mediaId==item.mediaId})p.addMediaItem(item)
            if(next && hadCurrent)toolCommand("next"){putString("id",item.mediaId)}
            screen.message(if(next)"Added to play next" else "Added to queue")
        }
        val queue=listOf(MusicAction("Play next",enabled=!current){enqueue(true)},MusicAction("Add to queue",enabled=!queued){enqueue(false)},MusicAction("Remove from queue","Undo is available for 10 seconds",queued && !current){screen.showSection("queue");action("section:queue");toolCommand("remove"){putString("id",item.mediaId)}})
        MusicActionSheet.show(this,card.title,card.subtitle,listOf(
            "Queue" to queue,
            "Library" to listOf(MusicAction("Open artist",enabled=ArtistNavigation.name(card.subtitle).length>=2){openArtist(card.subtitle)},MusicAction(if(tasteId(saved.id) in favoriteIds)"Remove from Liked songs" else "Add to Liked songs"){toggleFavorite(saved)},MusicAction("Add to playlist"){playlistUi.add(saved)},MusicAction("Save to library","Keep the track without starting playback"){personalWorker.submit {val ok=runCatching {storage.state.addToLibrary(saved)}.isSuccess;runOnUiThread {if(active)screen.message(if(ok)"Saved to library" else "Could not save track")}}},MusicAction(if(downloadJob?.state=="running")"Cancel active download" else "Save offline","Continues in the background · progress in notifications",downloadJob?.state=="running" || item.mediaId.startsWith("youtube:")){downloadCurrent(item)}),
            "Your listening" to listOf(MusicAction("More like this"){feedbackTrack(saved,1)},MusicAction("Less like this"){feedbackTrack(saved,-1)},MusicAction("Sleep timer"){sleepMenu()})
        ))
    }
    private fun feedbackCurrent(value:Int) {
        val item=controller?.currentMediaItem ?: return
        val track=SavedTrack(item.mediaId,item.mediaMetadata.title?.toString() ?: "Audio",item.mediaMetadata.artist?.toString() ?: "",item.mediaMetadata.artworkUri?.toString())
        feedbackTrack(track,value)
    }
    private fun feedbackTrack(track:SavedTrack,value:Int){
        personalWorker.submit {val ok=runCatching {storage.taste.feedback(track,value,System.currentTimeMillis())}.isSuccess
            runOnUiThread {if(active){android.widget.Toast.makeText(this,if(!ok)"Could not save preference" else if(value>0)"More like this in your recommendations" else "Hidden from your recommendations",android.widget.Toast.LENGTH_SHORT).show();if(ok && section=="home" && lastCatalogRequest.first=="home")loadCatalog("home")}}}
    }
    private fun cancelSuggestions(){suggestionGeneration++;pendingSuggestion?.let(handler::removeCallbacks);pendingSuggestion=null;suggestionTask?.cancel(true);suggestionTask=null}
    private fun suggest(query:String){
        cancelSuggestions();if(query.length !in 2..200 || !active)return
        val generation=suggestionGeneration
        suggestionCache[query.lowercase()]?.let {screen.suggest(query,it);return}
        val request=Runnable {
            if(generation!=suggestionGeneration || !active)return@Runnable
            suggestionTask=suggestionWorker.submit {
                val found=runCatching {MusicCatalog.search(query,"songs").flatMap {it.items}.take(4)}.getOrDefault(emptyList())
                runOnUiThread {if(active && generation==suggestionGeneration){if(found.isNotEmpty())suggestionCache[query.lowercase()]=found;screen.suggest(query,found)}}
            }
        };pendingSuggestion=request;handler.postDelayed(request,550)
    }
    private fun prioritizePlayback(){cancelSuggestions();catalogTask?.cancel(true);catalogGeneration++;if(catalogLoading)screen.catalogPaused();catalogLoading=false}
    private fun loadHome(generation:Int,force:Boolean) {
        val rotation=++homeVisit
        val current=controller?.currentMediaItem?.mediaId
        val enrich=controller!=null && (controller?.playWhenReady!=true || controller?.playbackState==Player.STATE_READY)
        val now=System.currentTimeMillis()
        val allowNetwork=now-lastHomeNetwork>60_000
        if(allowNetwork)lastHomeNetwork=now
        catalogLoading=true
        if(homeShelves.isEmpty())screen.catalogStatus("Finding your music…")
        catalogTask=catalogWorker.submit {
            val learned=storage.taste.read().associateBy {it.track.id}
            val explicit=runCatching {favorites.all()}.getOrDefault(emptyList()).map {t->val old=learned[tasteId(t.id)];(old ?: TasteEntry(t.copy(id=tasteId(t.id)),updated=now)).let {it.copy(feedback=if(it.feedback<0)-1 else 1)}}
            val profile=(explicit+learned.values).distinctBy {it.track.id};val shown=storage.recommendations.read(now)
            val artists=profile.filter {it.feedback>=0 && HomeRanking.weight(it,now)>.3}.map {artistKey(it.track.artist)}.filter {it.isNotBlank()}.toSet()
            var published=false
            var lastPublished=emptyList<MusicShelf>()
            fun publish() {
                if(Thread.currentThread().isInterrupted)return
                val result=HomeRanking.shelves(profile,storage.discovery.candidatesFor(artists),now,rotation,current,shown)
                if(published && result==lastPublished)return
                lastPublished=result
                published=true
                runOnUiThread {if(active && catalogGeneration==generation){homeShelves=result;catalogLoading=false;screen.catalog(result,
                    if(result.isNotEmpty())"Shaped by your listening · refresh for another selection" else "Start with music you love · your listening shapes this page",heading="Discover",mode="discover");personalWorker.submit {runCatching {storage.recommendations.record(result.flatMap {it.items},now)}}}}
            }
            publish()
            // Two cached artist searches only; transport controls/buffering cancel this work promptly.
            if(enrich && allowNetwork)for(artist in HomeRanking.seeds(profile,now,rotation)) {
                if(Thread.currentThread().isInterrupted)return@submit
                if(!storage.discovery.due(artist,now))continue
                val items=runCatching {MusicCatalog.search(artist,"songs").flatMap {it.items}}.getOrNull()
                if(Thread.currentThread().isInterrupted)return@submit
                if(items!=null)runCatching {storage.discovery.saveArtist(artist,items,now)}
            }
            publish()
            runOnUiThread {if(active && catalogGeneration==generation){catalogLoading=false;if(!published)screen.catalogStatus("Music unavailable. Use Search or Home to try again.")}}
        }
    }
    private fun openArtist(credit:String,known:MusicCard?=null){
        val name=ArtistNavigation.name(credit)
        if(name.length<2){screen.message("Artist information is unavailable for this track");return}
        if(artistReturnSection==null){artistReturnSection=section;artistReturnCollection=activeCollection?.id.takeIf {section=="library"}}
        screen.closePlayer()
        loadCatalog(if(known==null)"artist" else "browse",known?.id ?: name,known?.title ?: name,params=known?.params,knownArtist=known)
    }
    private fun catalogBack(){
        val previous=artistReturnSection;val collection=artistReturnCollection
        artistReturnSection=null;artistReturnCollection=null;artistCard=null
        if(previous=="library" && collection!=null)openSavedCollection(collection)
        else if(previous=="library" || previous=="downloads"){section=previous;screen.showSection(previous);loadLibrary(previous=="downloads")}
        else loadCatalog("home")
    }
    private fun loadCatalog(kind:String,value:String="",title:String="",force:Boolean=false,params:String?=null,knownArtist:MusicCard?=null) {
        artistCard=knownArtist
        catalogNext=null;moreBusy=false;screen.catalogMore(false)
        section="home";screen.showSection("home")
        if(kind=="home")loadPersonal()
        catalogTask?.cancel(true);val generation=++catalogGeneration
        lastCatalogRequest=Triple(kind,value,title)
        if(kind=="home"){loadHome(generation,force);return}
        catalogLoading=true
        screen.catalogStatus(if(kind=="browse")"Opening $title…" else "Loading music…")
        catalogTask=catalogWorker.submit {
            var resolvedArtist=knownArtist
            val result=runCatching {when(kind){"artist"->{val found=CatalogPaging.search(value,"artists");resolvedArtist=ArtistNavigation.exact(value,found.shelves.flatMap {it.items});resolvedArtist?.let {CatalogPaging.browse(it.id,it.params)} ?: found};"browse"->CatalogPaging.browse(value,params);"videos"->CatalogPage(listOf(MusicShelf("Video results",NewPipeCatalog.search(value).map {MusicCard(it.id,"song",it.title,it.artist,it.artwork)})));else->CatalogPaging.search(value,kind)}}
            runOnUiThread {
                if(!active || catalogGeneration!=generation)return@runOnUiThread
                catalogLoading=false
                val page=result.getOrNull();val shelves=page?.shelves
                if(shelves==null){screen.catalogStatus("Music unavailable. Use Search or Home to try again.");return@runOnUiThread}
                artistCard=resolvedArtist
                catalogPages=shelves;catalogNext=page.next
                val count=shelves.sumOf {it.items.size}
                val caption=when(kind){"home"->"YouTube Music · public discovery";"browse"->"$title · $count loaded";"artist"->if(artistCard==null)"Choose an artist · $count results" else "$title · $count loaded";else->"$count $kind"}
                screen.catalog(shelves,if(count==0)"No music found. Try another search." else caption,kind!="home",
                    heading=when(kind){"home"->"Discover";"browse","artist"->title.ifBlank {"Collection"};else->"Search results"},
                    mode=when(kind){"home"->"discover";"browse","artist"->"collection";else->"search"})
                artistCard?.let {screen.artistHeader(it)}
                screen.catalogMore(catalogNext!=null)
            }
        }
    }
    private fun loadMore(){
        if(moreBusy)return;val next=catalogNext ?: return;val generation=catalogGeneration
        moreBusy=true;screen.catalogMore(true,true)
        catalogTask=catalogWorker.submit {val result=runCatching {next()};runOnUiThread {
            if(!active || generation!=catalogGeneration)return@runOnUiThread
            moreBusy=false;val page=result.getOrNull()
            if(page==null){screen.catalogMore(true);screen.message("Could not load the next page · try Show more again");return@runOnUiThread}
            catalogPages=CatalogPaging.merge(catalogPages,page.shelves);catalogNext=page.next
            screen.catalog(catalogPages,"${catalogPages.sumOf {it.items.size}} loaded",true,heading=lastCatalogRequest.third.ifBlank {"Search results"},mode=if(lastCatalogRequest.first in setOf("browse","artist"))"collection" else "search")
            artistCard?.let {screen.artistHeader(it)}
            screen.catalogMore(catalogNext!=null)
        }}
    }
    private fun toggleFavorite(track:SavedTrack){
        personalWorker.submit {val result=runCatching {val adding=favorites.toggle(track);adding to favorites.all().map {tasteId(it.id)}.toSet()}
            runOnUiThread {if(active)result.fold({(adding,ids)->favoriteIds=ids;screen.message(if(adding)"Saved to Liked songs" else "Removed from Liked songs");if(section=="library"){if(activeCollection?.id=="liked")openSavedCollection("liked") else if(activeCollection==null)loadLibrary(false)}},{screen.message("Could not save like · your existing favorites were kept")})}
        }
    }
    private fun readCollections()=PlaylistStore(java.io.File(filesDir,"music-state")).all()+DesktopLibraryImport(java.io.File(filesDir,"music-state")).collections().filter {it.id!="desktop:favorites"}
    private fun loadLibrary(downloadsOnly:Boolean) {
        activeCollection=null;catalogTask?.cancel(true);val generation=++catalogGeneration
        val current=controller?.let {p->(0 until p.mediaItemCount).map {i->val item=p.getMediaItemAt(i);SavedTrack(item.mediaId,item.mediaMetadata.title?.toString() ?: "Audio",item.mediaMetadata.artist?.toString() ?: "",item.mediaMetadata.artworkUri?.toString())}}.orEmpty()
        screen.catalogStatus("Opening your music…")
        catalogTask=catalogWorker.submit {
            val result=runCatching {
                val liked=favorites.all();val collections=readCollections()
                val saved=if(downloadsOnly)storage.downloaded() else (storage.state.library()+liked+collections.flatMap {it.tracks}+current+storage.downloaded()).distinctBy {tasteId(it.id)}
                val grants=contentResolver.persistedUriPermissions.filter {it.isReadPermission}.map {it.uri.toString()}.toSet()
                val available=saved.filter {storage.normalize(storage.item(it),grants,packageName)!=null}
                Triple(liked,collections,available)
            }
            runOnUiThread {if(active && generation==catalogGeneration)result.fold({(liked,collections,tracks)->
                favoriteIds=liked.map {tasteId(it.id)}.toSet()
                if(downloadsOnly)screen.catalog(listOf(MusicShelf("Downloaded",tracks.map(::personalCard))),"${tracks.size} tracks · ready offline",true,heading="Downloads",mode="downloads")
                else {
                    libraryTracks=tracks
                    val cards=listOf(MusicCard("liked","liked","Liked songs",songCount(liked.size),null),MusicCard("all","all_tracks","All songs",songCount(tracks.size),null))+collections.map {p->MusicCard(p.id,"saved_playlist",p.title,songCount(p.tracks.size),p.tracks.firstOrNull {it.artwork!=null}?.artwork)}
                    screen.collections(cards,"Your favorites and playlists · saved on this device")
                }
            },{screen.catalogStatus("Library unavailable · saved data was kept")})}
        }
    }
    private fun songCount(n:Int)="$n ${if(n==1)"song" else "songs"}"
    private var libraryTracks=emptyList<SavedTrack>()
    private fun showCollection(p:ImportedCollection){
        catalogTask?.cancel(true);catalogGeneration++;activeCollection=p;section="library";screen.showSection("library")
        screen.catalog(listOf(MusicShelf(p.title,p.tracks.map(::personalCard))),"${songCount(p.tracks.size)} · saved on this device",true,heading=p.title,mode="playlist")
        screen.editableCollection(p.id.startsWith("android-"))
    }
    private fun openSavedCollection(id:String){
        personalWorker.submit {val result=runCatching {when(id){"liked"->ImportedCollection("liked","Liked songs",favorites.all());"all"->ImportedCollection("all","All songs",libraryTracks);else->readCollections().first {it.id==id}}}
            runOnUiThread {if(active)result.fold(::showCollection){loadLibrary(false)}}
        }
    }
    private fun playCollection(p:ImportedCollection){
        prioritizePlayback();val player=controller ?: return
        val grants=contentResolver.persistedUriPermissions.filter {it.isReadPermission}.map {it.uri.toString()}.toSet()
        val items=p.tracks.mapNotNull {storage.normalize(storage.item(it),grants,packageName)}
        if(items.isEmpty())screen.message("These tracks are unavailable · reconnect local files")
        else {player.setMediaItems(items);player.prepare();player.play();screen.message("Playing ${p.title}")}
    }
    private fun playSaved(track:SavedTrack) {
        prioritizePlayback()
        val player=controller ?: return
        val index=(0 until player.mediaItemCount).firstOrNull {tasteId(player.getMediaItemAt(it).mediaId)==tasteId(track.id)} ?: player.mediaItemCount.also {player.addMediaItem(storage.item(track))}
        if(track.id.startsWith("download:") && player.getMediaItemAt(index).mediaId!=track.id)player.replaceMediaItem(index,storage.item(track))
        player.seekToDefaultPosition(index);player.prepare();player.play()
    }
    private fun loadLyrics() {
        val p=controller ?: return;val item=p.currentMediaItem ?: return
        val title=currentTags?.title ?: item.mediaMetadata.title?.toString().orEmpty()
        val artist=currentTags?.artist ?: item.mediaMetadata.artist?.toString().orEmpty()
        val seconds=(p.duration/1000).takeIf {it in 1..86400}?.toInt() ?: 0
        val key="${item.mediaId}|$title|$artist|$seconds"
        if(key==lyricsKey)return
        lyricsKey=key;lyricsTask?.cancel(true);val generation=++lyricsGeneration
        if(seconds==0 || title.isBlank() || artist.isBlank()){screen.lyricsStatus("Lyrics need the track details · start playback first");return}
        screen.lyricsStatus("Finding the words…")
        lyricsTask=lyricsWorker.submit {
            val repo=LyricsRepository(java.io.File(cacheDir,"lyrics"));val result=runCatching {LocalLyrics(java.io.File(filesDir,"local-lyrics")).load(item.mediaId) ?: repo.fetch(title,artist,seconds)}
            runOnUiThread {if(active && generation==lyricsGeneration){result.getOrNull()?.let {screen.lyrics(it,repo.key(title,artist,seconds))} ?: screen.lyricsStatus("Lyrics unavailable · switch to artwork and back to retry");}}
        }
    }
    private fun updateDownload(){
        val job=DownloadJobs.live ?: downloadJob
        if(job!=downloadJob){downloadJob=job;screen.downloadState(job?.state=="running");if(job!=null)screen.message(when(job.state){"running"->"Downloading ${job.percent}% · continues in background";"complete"->"Saved offline · open Downloads";"cancelled"->"Download cancelled";else->"Download interrupted · Playback options → Retry download"});if(job?.state=="complete" && section=="downloads")loadLibrary(true)}
    }
    private fun downloadCurrent(selectedItem:MediaItem?=controller?.currentMediaItem) {
        if(DownloadJobs.live?.state=="running"){DownloadJobs.cancel(this);return}
        val item=selectedItem ?: return;if(!item.mediaId.startsWith("youtube:"))return
        val track=SavedTrack(item.mediaId,item.mediaMetadata.title?.toString() ?: "Audio",item.mediaMetadata.artist?.toString().orEmpty(),item.mediaMetadata.artworkUri?.toString())
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),22)
        runCatching {DownloadJobs.start(this,track)}.onSuccess {screen.message("Preparing background download…")}.onFailure {screen.message("Could not start download. Keep the app visible and retry.")}
    }
    private fun openOnline() {
        val field=EditText(this).apply { hint="Song, artist or YouTube link"; setSingleLine(true); inputType=android.text.InputType.TYPE_CLASS_TEXT }
        val status=TextView(this).apply { text="Find a song, then tap it to add to your queue.";setPadding(16,8,16,8) }
        val results=ListView(this)
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(16,0,16,0);addView(field);addView(status);addView(results,LinearLayout.LayoutParams(-1,(320*resources.displayMetrics.density).toInt())) }
        val dialog=AlertDialog.Builder(this).setTitle("Search YouTube")
            .setView(layout).setPositiveButton("Find",null).setNegativeButton("Close",null).create()
        searchDialog=dialog
        dialog.setOnDismissListener { searchGeneration++;searchTask?.cancel(true);searchTask=null;searchDialog=null }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val query=field.text.toString().trim()
            val id=YouTubeInput.fromSearchInput(query)
            if(id!=null) { playOnline(CatalogTrack(id,"YouTube audio","Online · loading details",null));dialog.dismiss();return@setOnClickListener }
            if(query.length !in 2..200 || query.contains("://")) { field.error="Enter a song name or valid YouTube link";return@setOnClickListener }
            searchTask?.cancel(true);val generation=++searchGeneration
            status.text="Searching…";results.adapter=null
            searchTask=searchWorker.submit {
                val found=runCatching { NewPipeCatalog.search(query) }
                runOnUiThread {
                    if(!active || searchGeneration!=generation || !dialog.isShowing)return@runOnUiThread
                    val tracks=found.getOrNull()
                    status.text=when {tracks==null->"Search unavailable. Please try later.";tracks.isEmpty()->"No tracks found. Try another name.";else->"${tracks.size} results · tap to play"}
                    if(tracks!=null) {
                        results.adapter=ArrayAdapter(this,android.R.layout.simple_list_item_1,tracks.map { "${it.title}\n${it.artist}" })
                        results.setOnItemClickListener { _,_,position,_->playOnline(tracks[position]);dialog.dismiss() }
                    }
                }
            }
        } }
        dialog.show()
    }
    private fun playOnline(track: CatalogTrack) {
        prioritizePlayback()
        val player=controller ?: return
        val mediaId="youtube:${track.id}"
        val existing=(0 until player.mediaItemCount).firstOrNull {tasteId(player.getMediaItemAt(it).mediaId)==tasteId(mediaId)}
        val index=existing ?: player.mediaItemCount
        if(existing==null)player.addMediaItem(MediaItem.Builder().setMediaId(mediaId).setUri(YouTubeInput.logical(track.id))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
                .setArtworkUri(track.artwork?.let(android.net.Uri::parse)).build()).build())
        player.seekToDefaultPosition(index);player.prepare();player.play()
    }
    private fun diagnostics() {
        AlertDialog.Builder(this).setTitle("Android preview diagnostics")
            .setItems(arrayOf("Play generated test tone (replaces queue)", "Check JS capabilities (offline)", "Check pinned parser (offline)", "Queue trace (anonymous)", "Check bundled artwork fixtures", "About this preview")) { _, which ->
                when (which) {
                    0 -> { cancelSelection(); pendingItems = listOf(MediaItem.Builder().setMediaId("fixture:440")
                        .setUri("android.resource://$packageName/${R.raw.test_tone}")
                        .setMediaMetadata(MediaMetadata.Builder().setTitle("Generated test tone · 440 Hz").build()).build()); playPending() }
                    1, 2 -> probe(which == 2)
                    3 -> {
                        val text = TextView(this).apply { this.text = (PlaybackTrace.serviceSnapshot?.invoke() ?: "Playback service unavailable")+"\n\n"+PlaybackTrace.trace.report(); setTextIsSelectable(true); setPadding(24, 16, 24, 16); textSize = 12f }
                        AlertDialog.Builder(this).setTitle("Playback diagnostics")
                            .setView(ScrollView(this).apply { addView(text) }).setPositiveButton("Close", null).show()
                    }
                    4 -> checkArtworkFixtures()
                    else -> AlertDialog.Builder(this).setTitle("Android music preview · ${packageManager.getPackageInfo(packageName,0).versionName}")
                        .setMessage("Public music discovery, local listening preferences, search, local files and verified downloads share one Media3 player. Taste stays on this device; artist names are used for public searches to find new candidates. No account is needed. Queue, library and settings stay separate. Downloads continue in a foreground service. Lyrics use LRCLIB; availability varies by recording. NewPipeExtractor v0.26.5, Android GPL-3.0-or-later; desktop unchanged.")
                        .setPositiveButton("OK", null).show()
                }
            }.setNegativeButton("Close", null).show()
    }
    private fun checkArtworkFixtures() {
        if (fixtureCheckRunning) { screen.message("An artwork check is already running"); return }
        fixtureCheckRunning = true; val generation = ++fixtureGeneration
        fixtureCheck = artWorker.submit {
            val report = runCatching { metadata.checkBundledFixtures() }.getOrElse { "Artwork fixture check failed. No user files were read." }
            runOnUiThread {
                if (active && !isDestroyed && generation == fixtureGeneration) {
                    fixtureCheckRunning = false; fixtureCheck = null
                    AlertDialog.Builder(this).setTitle("Bundled artwork fixtures").setMessage(report).setPositiveButton("OK", null).show()
                }
            }
        }
    }
    private fun probe(bridge: Boolean) {
        if (runtimeProbe != null) { screen.message("A diagnostic check is already running"); return }
        val probe = AndroidRuntimeProbe(this)
        runtimeProbe = probe; screen.message("Checking offline runtime…")
        probe.start(pinnedFixture = bridge).whenComplete { result, _ -> runOnUiThread {
            if (active && runtimeProbe === probe) {
                runtimeProbe = null; screen.message("Local playback · Android preview")
                AlertDialog.Builder(this).setTitle(if (bridge) "Offline pinned parser" else "JS capabilities")
                    .setMessage(result ?: "Check cancelled").setPositiveButton("OK", null).show()
            }
        } }
    }
    override fun onStart() {
        super.onStart(); active = true; downloadJob=DownloadJobs.read(this);screen.downloadState(downloadJob?.state=="running");handler.post(progress)
        sessionRecent=emptyList();loadPersonal();checkAppUpdate()
        screen.resumeArtwork()
        if(section=="library" || section=="downloads")loadLibrary(section=="downloads")
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        connection = future
        future.addListener({
            if (connection !== future) return@addListener
            try {
                controller = future.get()
                controller?.addListener(object : Player.Listener {
                    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                        controller?.let { PlaybackTrace.record(QueueTrace.Scope.CONTROLLER, QueueTrace.Event.TIMELINE, it, reason = reason) }
                    }
                    override fun onEvents(player: Player, events: Player.Events) {
                        if(player.playWhenReady && player.playbackState==Player.STATE_BUFFERING)prioritizePlayback()
                        refresh(events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED))
                    }
                })
                playPending(); refresh(true)
                if(section=="home" && (catalogLoading || homeShelves.isEmpty() || lastCatalogRequest.first=="home"))loadCatalog(lastCatalogRequest.first,lastCatalogRequest.second,lastCatalogRequest.third)
                pendingPresentation?.let {value->pendingPresentation=null;screen.restorePresentation(value,expandAfterRotation)}
                expandAfterRotation=false
            } catch (_: Exception) { screen.message("Could not connect to playback service") }
        }, java.util.concurrent.Executor { command -> runOnUiThread(command) })
    }
    private fun refresh(queueChanged: Boolean) {
        val player = controller ?: return
        val id = player.currentMediaItem?.mediaId
        if(player.isPlaying && id!=null) {
            val item=player.currentMediaItem!!
            val heard=SavedTrack(id,item.mediaMetadata.title?.toString() ?: "Audio",item.mediaMetadata.artist?.toString() ?: "",item.mediaMetadata.artworkUri?.toString())
            if(sessionRecent.firstOrNull()!=heard){sessionRecent=(listOf(heard)+sessionRecent.filter {it.id!=id}).take(6);showPersonal()}
        }
        val artworkUri=player.currentMediaItem?.mediaMetadata?.artworkUri?.toString()
        if(currentId!=id){lyricsKey="";lyricsGeneration++;lyricsTask?.cancel(true);screen.lyricsStatus("Finding the words…")}
        if (currentId != id || currentArtworkUri!=artworkUri) {
            currentArtworkUri=artworkUri
            currentId = id; currentTags = null; artGeneration++; artTask?.cancel(true); screen.prepareArtwork()
            val generation = artGeneration
            val uri = player.currentMediaItem?.let(metadata::sourceUri)
            if (uri?.scheme == "content") artTask = artWorker.submit {
                val result = runCatching { metadata.current(uri) }.getOrNull()
                runOnUiThread {
                    if (!isDestroyed && active && artGeneration == generation && currentId == id) {
                        currentTags = result; screen.setArtwork(result?.artwork); refresh(true)
                    }
                }
            }
            else if(id?.startsWith("download:")==true)artTask=artWorker.submit {
                val bitmap=runCatching {storage.cover(id)?.takeIf {it.length()<=1024*1024}?.let {android.graphics.BitmapFactory.decodeFile(it.path)}}.getOrNull()
                runOnUiThread {if(!isDestroyed && active && artGeneration==generation && currentId==id)screen.setArtwork(bitmap)}
            }
            else player.currentMediaItem?.mediaMetadata?.artworkUri?.toString()?.takeIf(NewPipeCatalog::validArtwork)?.let { image ->
                artTask=artWorker.submit {
                    val bitmap=runCatching { OnlineArtwork.load(image) }.getOrNull()
                    runOnUiThread { if(!isDestroyed && active && artGeneration==generation && currentId==id)screen.setArtwork(bitmap) }
                }
            } ?: screen.setArtwork(null)
        }
        screen.bind(player, currentTags?.title, currentTags?.artist)
        if (queueChanged) {
            queueIndexes=(0 until player.mediaItemCount).associateBy {player.getMediaItemAt(it).mediaId}
            val order=playbackIndices(player)
            queuePresentation.update(order.map { index ->
                val item = player.getMediaItemAt(index)
                QueuePresentation.Item(item.mediaId, item.mediaMetadata.displayTitle?.toString() ?: item.mediaMetadata.title?.toString() ?: "Audio file",
                    item.mediaMetadata.title?.toString(), item.mediaMetadata.artist?.toString())
            })
            if (id != null) queuePresentation.remember(id, currentTags?.title, currentTags?.artist)
            val rows = queuePresentation.rows(order.indexOf(player.currentMediaItemIndex))
            screen.setQueue(rows)
            PlaybackTrace.trace.record(QueueTrace.Scope.UI, QueueTrace.Event.RENDER, rows.map { it.id }, player.currentMediaItemIndex)
        }
        screen.selectQueue(id)
        if(downloadJob?.state!="running")screen.message(when {
            player.playerError != null -> if(id?.startsWith("youtube:")==true) "Online audio unavailable · no automatic fallback" else "Unable to play this file · choose another file or retry"
            player.playbackState == Player.STATE_BUFFERING -> "Preparing audio…"
            else -> if(id?.startsWith("youtube:")==true) "Online · NewPipe / Media3" else "Local playback · Android preview"
        })
    }
    override fun onStop() {
        // Android 9+ may save state after onStop; retain artwork before clearing UI resources.
        if(isChangingConfigurations)retainedPresentation=screen.presentation()
        cancelSuggestions()
        active = false; handler.removeCallbacks(progress);lyricsGeneration++;lyricsTask?.cancel(true);lyricsKey=""
        personalGeneration++
        catalogGeneration++;catalogTask?.cancel(true);screen.pauseArtwork()
        searchGeneration++;searchTask?.cancel(true);searchDialog?.dismiss();searchDialog=null
        runtimeProbe?.close(); runtimeProbe = null
        fixtureGeneration++; fixtureCheck?.cancel(true); fixtureCheck = null; fixtureCheckRunning = false
        artGeneration++; artTask?.cancel(true); currentId = null; currentTags = null; screen.setArtwork(null)
        controller = null; connection?.let { MediaController.releaseFuture(it) }; connection = null
        super.onStop()
    }
    override fun onDestroy() { cancelSelection();cancelSuggestions();suggestionWorker.shutdownNow();screen.release();lyricsWorker.shutdownNow();personalWorker.shutdown();catalogWorker.shutdownNow();artWorker.shutdownNow(); namesWorker.shutdownNow();searchWorker.shutdownNow(); super.onDestroy() }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT < 33 && keyCode == KeyEvent.KEYCODE_BACK && screen.closePlayer()) return true
        return super.onKeyUp(keyCode, event)
    }
    private fun cancelSelection() { selectionGeneration++; queryCancellation?.cancel(); namesTask?.cancel(true) }
    @Deprecated("Platform document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(requestCode==20 && resultCode==RESULT_OK && data?.data!=null){previewDesktopLibrary(data.data!!);return}
        if(requestCode==23 && resultCode==RESULT_OK && data?.data!=null){
            val uri=data.data!!;personalWorker.submit {
                val result=runCatching {
                    val prefs=getSharedPreferences("collection-transfer",MODE_PRIVATE)
                    val id=prefs.getString("device-id",null) ?: java.util.UUID.randomUUID().toString().also {prefs.edit().putString("device-id",it).apply()}
                    val collections=readCollections()+ImportedCollection("desktop:favorites","Liked songs",favorites.all())
                    val json=CollectionExport.encode(storage.state.library()+storage.downloaded(),collections,id)
                    contentResolver.openOutputStream(uri,"wt")?.use {it.write(json.toByteArray(Charsets.UTF_8))} ?: error("Cannot write file")
                }
                runOnUiThread {if(active)screen.message(if(result.isSuccess)"Collection exported. Import it in Music profile on your updated PC app. Audio and listening preferences were not included." else "Could not export collection")}
            };return
        }
        if(requestCode==21){
            val id=lyricsImportTrack;lyricsImportTrack=null
            if(resultCode==RESULT_OK && data?.data!=null && id!=null){val uri=data.data!!;lyricsWorker.submit {
                val result=runCatching {contentResolver.openInputStream(uri)?.use {LocalLyrics(java.io.File(filesDir,"local-lyrics")).save(id,it)} ?: error("Cannot read file")}
                runOnUiThread {if(active){screen.message(if(result.isSuccess)"Lyrics saved for the selected song" else "Could not read lyrics; existing words were kept");if(result.isSuccess){lyricsKey="";loadLyrics()}}}
            }}
            return
        }
        if (requestCode != 10 || resultCode != RESULT_OK || data == null) return
        cancelSelection(); val generation = selectionGeneration
        val cancellation = CancellationSignal(); queryCancellation = cancellation
        val uris = (data.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } } ?: listOfNotNull(data.data)).distinct()
        screen.message("Reading selected file names…")
        namesTask = namesWorker.submit {
            val items = mutableListOf<MediaItem>()
            for (uri in uris) {
                if (cancellation.isCanceled || Thread.currentThread().isInterrupted) return@submit
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    items.add(metadata.item(uri, cancellation))
                }
            }
            runOnUiThread {
                if (!isDestroyed && generation == selectionGeneration) {
                    pendingItems = items; playPending()
                    if (items.size < uris.size) screen.message("${uris.size - items.size} files could not be opened. Other files were kept.")
                }
            }
        }
    }
    private fun playPending() {
        val player = controller ?: return
        if (pendingItems.isEmpty()) return
        player.setMediaItems(pendingItems); pendingItems = emptyList(); player.prepare(); player.play()
    }

    private val playlistUi by lazy {PlaylistUi(this,personalWorker,{active},{controller?.let {p->(0 until p.mediaItemCount).map {i->val item=p.getMediaItemAt(i);SavedTrack(item.mediaId,item.mediaMetadata.title?.toString() ?: "Track",item.mediaMetadata.artist?.toString() ?: "",item.mediaMetadata.artworkUri?.toString())}}.orEmpty()},::showCollection,::playCollection).apply {
        onChanged={if(section=="library"){val id=activeCollection?.id;if(id!=null)openSavedCollection(id) else loadLibrary(false)}}
    }}
    private fun libraryTools(){
        personalWorker.submit {
            val saved=runCatching {DesktopLibraryImport(java.io.File(filesDir,"music-state")).collections()}
            runOnUiThread {if(active)MusicActionSheet.show(this,"Your collection",groups=listOf(
                "Playlists" to listOf(MusicAction("Your playlists","Create, rename and organize your own playlists"){section="library";screen.showSection("library");loadLibrary(false)}),
                "Add music" to listOf(MusicAction("Choose audio files"){action("choose")},MusicAction("Import from PC","Export your music profile on PC, then choose its JSON file here"){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {addCategory(Intent.CATEGORY_OPENABLE);type="*/*";addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},20)}),
                "Transfer" to listOf(MusicAction("Export collection for PC","Adds music on PC; does not replace its taste or settings"){startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {addCategory(Intent.CATEGORY_OPENABLE);type="application/json";putExtra(Intent.EXTRA_TITLE,"devil-music-collection.json")},23)}),
                "Imported collections" to saved.getOrDefault(emptyList()).map {c->MusicAction(c.title,"${c.tracks.size} tracks"){
                    catalogTask?.cancel(true);catalogGeneration++;section="library";screen.showSection("library")
                    screen.catalog(listOf(MusicShelf(c.title,c.tracks.map(::personalCard))),"${c.tracks.size} tracks · imported from PC",true,heading=c.title,mode="library")
                }}
            ))}
        }
    }
    private fun previewDesktopLibrary(uri:android.net.Uri){
        screen.message("Reading PC collection…")
        personalWorker.submit {
            val result=runCatching {contentResolver.openInputStream(uri)?.use {DesktopLibraryImport.preview(DesktopLibraryImport.read(it))} ?: error("Cannot read file")}
            runOnUiThread {if(active)result.fold(onSuccess={preview->
                AlertDialog.Builder(this).setTitle("Import PC collection?")
                    .setMessage("${preview.tracks.size} unique online tracks in ${preview.collections.size} collections. ${preview.skipped} local or unsupported entries will stay on PC.\n\nAdds music without replacing your library, queue, listening preferences or settings. Repeated imports do not duplicate tracks.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Import"){_,_->personalWorker.submit {
                        val applied=runCatching {DesktopLibraryImport(java.io.File(filesDir,"music-state")).apply(preview)}
                        runOnUiThread {if(active){screen.message(applied.fold({"Added $it tracks. Open the collection menu to view playlists."},{"Import failed. Your collection was kept."}));if(applied.isSuccess){section="library";screen.showSection("library");loadLibrary(false)}}}
                    }}.show()
            },onFailure={screen.message("Could not import: ${it.message ?: "invalid profile"}")})}
        }
    }
}




