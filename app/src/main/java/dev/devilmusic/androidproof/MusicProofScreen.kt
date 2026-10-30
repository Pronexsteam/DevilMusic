package dev.devilmusic.androidproof

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.TransitionDrawable
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.animation.DecelerateInterpolator
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.media3.common.C
import androidx.media3.common.Player
import dev.devilmusic.proof.QueuePresentation
import java.util.Locale

internal data class PlayerPresentation(val open:Boolean,val words:Boolean,val artwork:Bitmap?,val lyrics:Pair<Lyrics,String>?,val origin:android.graphics.RectF?,val phase:Float=0f,val immersed:Boolean=false)

/** Cover-led native shell. All playback and source ownership stays in the existing service. */
class MusicProofScreen(context:Context,private val action:(String)->Unit,private val select:(String)->Unit,private val seek:(Long)->Unit):LinearLayout(context) {
    private val wide=resources.configuration.screenWidthDp>=960 && resources.configuration.screenHeightDp>=480
    private fun dp(n:Int)=MusicStyle.dp(context,n)
    private fun label(size:Float,color:Int=MusicStyle.ink,bold:Boolean=false,lines:Int=1)=MusicStyle.text(context,size,color,bold,lines)
    private fun icon(symbol:String,description:String,primary:Boolean=false,click:()->Unit)=MusicGlyph(context,symbol,if(primary)MusicStyle.background else MusicStyle.ink).apply {
        contentDescription=description;isClickable=true;isFocusable=true;background=MusicStyle.touch(context,if(primary)MusicStyle.accent else Color.TRANSPARENT,32);setOnClickListener {click()}
    }
    private fun button(title:String,click:()->Unit)=MusicStyle.text(context,13f,bold=true).apply {
        text=title;textSize=13f;setTextColor(MusicStyle.ink);gravity=Gravity.CENTER;isClickable=true;isFocusable=true;setPadding(dp(16),dp(10),dp(16),dp(10));background=MusicStyle.touch(context,Color.argb(120,27,34,42),16,true);setOnClickListener {click()}
    }
    internal var onMusicOpen:(MusicCard)->Unit={}
    internal var onMusicSearch:(String,String)->Unit={_,_->}
    internal var onMusicPin:(MusicCard)->Unit={}
    internal var onQueueMove:(String,String)->Unit={_,_->}
    internal var onQueueMenu:(String)->Unit={}
    internal var onArtist:(String)->Unit
        get()=browser.onArtist
        set(value){browser.onArtist=value}
    internal fun artistHeader(card:MusicCard){browser.artistHeader(card)}
    internal var onTrackMenu:(MusicCard)->Unit
        get()=browser.onTrackMenu
        set(value){browser.onTrackMenu=value}
    internal var onSuggest:(String)->Unit
        get()=browser.onSuggest
        set(value){browser.onSuggest=value}
    internal var onHomeChange:(HomeArrangement)->Unit
        get()=browser.onHomeChange
        set(value){browser.onHomeChange=value}
    internal var onClearSearch:()->Unit
        get()=browser.onClearSearch
        set(value){browser.onClearSearch=value}
    private val browser=MusicBrowser(context,{onMusicOpen(it)},{q,k->onMusicSearch(q,k)},{action("home")},{action("choose")},{action("retry_catalog")},{onMusicPin(it)},{action("refresh_home")}).apply {onCatalogBack={action("catalog_back")};onPlaylists={action("playlists")};onLibraryHome={action("section:library")};onPlayCollection={action("play_collection")}}
    private val header=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;background=MusicStyle.panel(context,22);setPadding(dp(7),0,dp(4),0)}
    private val brand=WaterMusicMark(context)
    private val workspace=FrameLayout(context)
    private val library=LinearLayout(context).apply {orientation=VERTICAL;background=MusicStyle.panel(context);setPadding(dp(12),dp(4),dp(12),dp(8))}
    private val nav=LinearLayout(context).apply {orientation=if(wide)VERTICAL else HORIZONTAL;gravity=Gravity.CENTER}
    private val sections=mutableMapOf<String,Pair<MusicGlyph,TextView>>()
    private val sectionViews=mutableMapOf<String,View>()
    private val sectionPills=mutableMapOf<String,View>()
    private val playerPane=LinearLayout(context).apply {orientation=VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(20))}
    private val playerScroll=ScrollView(context).apply {isFillViewport=true;addView(playerPane,FrameLayout.LayoutParams(-1,-2));isVerticalScrollBarEnabled=false;background=MusicStyle.panel(context,26);clipToOutline=true}
    private val playerState=label(10f,MusicStyle.muted,true).apply {letterSpacing=.16f;gravity=Gravity.CENTER}
    private val bigCover=VinylArtwork(context)
    private val lyrics=LyricsPanel(context,seek)
    private val artworkSlot=FrameLayout(context)
    private var lyricsVisible=false
    private var favoriteIds=emptySet<String>()
    fun favorites(ids:Set<String>){favoriteIds=ids;immersive?.liked(lastPlayer?.currentMediaItem?.mediaId?.let(::tasteId) in favoriteIds)}
    private val lyricsToggle=button("Lyrics"){toggleLyrics()}
    private val upNext=button("Up next · manage queue"){showSection("queue");action("section:queue")}
    private val miniCover=VinylArtwork(context)
    private val trackTitle=label(if(wide)22f else 30f,bold=true,lines=2)
    private val artist=label(if(wide)13f else 16f,MusicStyle.muted,lines=if(wide)1 else 2)
    private val sourceLabel=label(11f,MusicStyle.muted).apply {letterSpacing=.06f}
    private val miniTitle=label(14f,bold=true)
    private val miniArtist=label(12f,MusicStyle.muted)
    private val time=label(11f,MusicStyle.muted)
    private val duration=label(11f,MusicStyle.muted)
    private val notice=label(12f,MusicStyle.muted,lines=2).apply {visibility=GONE;setPadding(dp(6),dp(6),dp(6),dp(6))}
    private val play=icon("play","Play or pause",true){action("toggle")}
    private val miniPlay=icon("play","Play or pause",true){action("toggle")}
    private val previous=icon("previous","Previous track"){action("previous")}
    private val next=icon("next","Next track"){action("next")}
    private val miniNext=icon("next","Next track"){action("next")}
    private val shuffle=icon("shuffle","Shuffle"){action("shuffle")}
    private val repeat=icon("repeat","Repeat"){action("repeat")}
    private val offline=button("Save offline"){action("download")}
    private var downloading=false
    private var selected=false
    private var fullPlayer=false
    private var immersive:ImmersivePlayer?=null
    private var lastPlayer:Player?=null
    private var lastLyrics:Pair<Lyrics,String>?=null
    private var art:Bitmap?=null
    private var palette=AmbientPalette.resting()
    private var currentSurface=AmbientSurface(palette)
    private var effectsActive=true
    private var rhythmReady=false
    private var rhythmPlaying=false
    private var rhythmClock:ValueAnimator?=null
    private val rhythmVisuals=RhythmVisuals()
    private var motion:ValueAnimator?=null
    private var flight:CoverTransitionDrawable?=null
    private var motionSerial=0
    private var fallbackKey=""
    private val mini=LinearLayout(context).apply {orientation=VERTICAL;background=MusicStyle.panel(context,22);clipToOutline=true}
    private val miniProgress=ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply {max=1000;progressTintList=ColorStateList.valueOf(MusicStyle.accent);progressBackgroundTintList=ColorStateList.valueOf(Color.argb(22,255,255,255));importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    private var durationMs=0L
    private var dragging=false
    private val timeline=SeekBar(context).apply {
        max=1000;progressTintList=ColorStateList.valueOf(MusicStyle.accent);thumbTintList=ColorStateList.valueOf(MusicStyle.accent);progressBackgroundTintList=ColorStateList.valueOf(Color.argb(40,255,255,255));setPadding(dp(4),0,dp(4),0);contentDescription="Playback position"
        setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar:SeekBar){dragging=true}
            override fun onStopTrackingTouch(bar:SeekBar){seek(durationMs*bar.progress/1000);dragging=false}
            override fun onProgressChanged(bar:SeekBar,progress:Int,fromUser:Boolean){if(fromUser)time.text=formatTime(durationMs*progress/1000)}
        })
    }
    private val queueSummary=label(12f,MusicStyle.muted)
    private val undoButton=button("Undo removal"){action("undo")}.apply {visibility=GONE}
    private var sleepDeadline=0L
    private var sleepEnd=false
    private val rows=mutableListOf<QueuePresentation.Row>()
    private var rowIndexes=emptyMap<String,Int>()
    private var selectedRow:Int?=null
    private data class QueueDrag(val id:String)
    private var dragTarget:String?=null
    private var dragY=0f
    private var draggingQueue=false
    private val dragScroll=object:Runnable {override fun run(){
        if(!draggingQueue)return
        val distance=when {dragY<dp(56)->-dp(14);dragY>queue.height-dp(56)->dp(14);else->0}
        if(distance!=0)queue.scrollListBy(distance)
        queue.postDelayed(this,32)
    }}
    private val queueAdapter=object:BaseAdapter() {
        override fun getCount()=rows.size
        override fun getItem(position:Int)=rows[position]
        override fun getItemId(position:Int)=position.toLong()
        override fun getView(position:Int,recycled:View?,parent:android.view.ViewGroup):View {
            val row=(recycled as? LinearLayout) ?: LinearLayout(context).apply {
                gravity=Gravity.CENTER_VERTICAL;setPadding(dp(6),dp(12),dp(8),dp(12))
                addView(label(12f,MusicStyle.muted).apply {gravity=Gravity.CENTER},LayoutParams(dp(38),dp(44)))
                addView(LinearLayout(context).apply {orientation=VERTICAL;addView(label(15f,bold=true));addView(label(12f,MusicStyle.muted).apply {setPadding(0,dp(5),0,0)})},LayoutParams(0,-2,1f))
                addView(MusicGlyph(context,"grip",MusicStyle.muted),LayoutParams(dp(48),dp(48)))
            }
            val item=rows[position];row.background=MusicStyle.box(context,if(item.id==dragTarget)Color.argb(75,155,183,218) else if(item.selected)Color.argb(55,98,161,143) else Color.TRANSPARENT,14)
            (row.getChildAt(0) as TextView).apply {text=item.number.toString();setTextColor(if(item.selected)MusicStyle.accent else MusicStyle.muted)}
            val texts=row.getChildAt(1) as LinearLayout;(texts.getChildAt(0) as TextView).text=item.title
            (texts.getChildAt(1) as TextView).text=item.subtitle.ifBlank {availability(item.id)}
            row.contentDescription="Queue item ${item.number}. ${if(item.selected)"Current track. " else ""}Play ${item.title}, ${item.subtitle}"
            row.setOnClickListener {select(item.id)}
            row.getChildAt(2).apply {
                contentDescription="Reorder ${item.title}. Hold and drag, or tap for options";isClickable=true;isFocusable=true
                setOnLongClickListener {startDragAndDrop(android.content.ClipData.newPlainText("queue",""),View.DragShadowBuilder(row),QueueDrag(item.id),0)}
                setOnClickListener {PopupMenu(context,this).apply {
                    menu.add("Move up").isEnabled=position>0;menu.add("Move down").isEnabled=position<rows.lastIndex
                menu.add("More actions")
                setOnMenuItemClickListener {entry->if(entry.title=="More actions")onQueueMenu(item.id) else {val target=rows.getOrNull(position+if(entry.title=="Move up")-1 else 1);target?.let {onQueueMove(item.id,it.id)}};true};show()
                }}
            }
            row.setOnLongClickListener {onQueueMenu(item.id);true}
            return row
        }
    }
    private val queue=ListView(context).apply {adapter=queueAdapter;divider=null;selector=MusicStyle.box(context,Color.argb(35,177,231,214),14);setOnItemClickListener {_,_,position,_->rows.getOrNull(position)?.let {select(it.id)}}}
    private val emptyQueue=LinearLayout(context).apply {
        orientation=VERTICAL;gravity=Gravity.CENTER;setPadding(dp(24),dp(24),dp(24),dp(24))
        addView(MusicGlyph(context,"queue",MusicStyle.accent),LayoutParams(dp(64),dp(64)))
        addView(label(23f,bold=true).apply {text="Make a little room for music";maxLines=2;gravity=Gravity.CENTER},LayoutParams(-1,-2).apply {topMargin=dp(18)})
        addView(label(14f,MusicStyle.muted,lines=3).apply {text="Play a song from Discover, or add files from your device.";gravity=Gravity.CENTER},LayoutParams(-1,-2).apply {topMargin=dp(12)})
        addView(button("Explore music"){action("home")},LayoutParams(-2,dp(48)).apply {topMargin=dp(24)})
    }
    init {
        queue.setOnDragListener {_,event->
            val payload=event.localState as? QueueDrag
            if(payload==null)false else {
                when(event.action){
                    android.view.DragEvent.ACTION_DRAG_STARTED->{dragY=queue.height/2f;draggingQueue=true;queue.post(dragScroll)}
                    android.view.DragEvent.ACTION_DRAG_LOCATION->{dragY=event.y;val target=rows.getOrNull(queue.pointToPosition(event.x.toInt(),event.y.toInt()))?.id;if(target!=dragTarget){dragTarget=target;queueAdapter.notifyDataSetChanged()}}
                    android.view.DragEvent.ACTION_DROP->{val target=rows.getOrNull(queue.pointToPosition(event.x.toInt(),event.y.toInt()))?.id;target?.let {onQueueMove(payload.id,it)}}
                    android.view.DragEvent.ACTION_DRAG_ENDED->{draggingQueue=false;queue.removeCallbacks(dragScroll);dragTarget=null;queueAdapter.notifyDataSetChanged()}
                };true
            }
        }
        orientation=VERTICAL;setPadding(dp(if(wide)24 else 16),dp(6),dp(if(wide)24 else 16),dp(8));background=currentSurface
        header.addView(brand,LayoutParams(dp(52),dp(52)).apply {rightMargin=dp(3)})
        header.addView(label(19f,bold=true).apply {text="Devil Music";letterSpacing=-.02f},LayoutParams(0,-2,1f))
        header.addView(icon("add","Collection: local files, PC import and playlists"){action("library_tools")},LayoutParams(dp(48),dp(48)))
        header.addView(icon("menu","Playback options and diagnostics"){action("options")},LayoutParams(dp(48),dp(48)))
        addView(header,LayoutParams(-1,dp(56)))
        for((id,title,symbol) in listOf(Triple("home","Discover","discover"),Triple("library","Library","collection"),Triple("downloads","Downloads","offline"),Triple("queue","Queue","upnext"))) {
            val glyph=MusicGlyph(context,symbol,MusicStyle.muted)
            val caption=label(10f,MusicStyle.muted,true).apply {text=title;gravity=Gravity.CENTER}
            val pill=FrameLayout(context).apply {addView(glyph,FrameLayout.LayoutParams(dp(36),dp(32),Gravity.CENTER));importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS}
            val item=LinearLayout(context).apply {orientation=VERTICAL;gravity=Gravity.CENTER;isClickable=true;isFocusable=true;contentDescription=title;background=MusicStyle.touch(context,radius=16);addView(pill,LayoutParams(dp(52),dp(32)));addView(caption,LayoutParams(-1,-2).apply {topMargin=dp(5)});setOnClickListener {showSection(id);action("section:$id")}}
            sections[id]=glyph to caption;sectionViews[id]=item
            sectionPills[id]=pill
            nav.addView(item,if(wide)LayoutParams(dp(76),dp(72)).apply {bottomMargin=dp(8)} else LayoutParams(0,dp(64),1f))
        }
        workspace.addView(browser,FrameLayout.LayoutParams(-1,-1));workspace.addView(library,FrameLayout.LayoutParams(-1,-1))
        library.addView(label(29f,bold=true).apply {text="Your queue"},LayoutParams(-1,-2).apply {topMargin=dp(12);bottomMargin=dp(8)})
        library.addView(queueSummary)
        library.addView(undoButton,LayoutParams(-1,dp(48)).apply {topMargin=dp(6)})
        val tools=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(18),0,dp(14))}
        tools.addView(button("Add local files"){action("choose")},LayoutParams(0,dp(48),1f))
        tools.addView(button("Save playlist"){action("save_queue_playlist")},LayoutParams(0,dp(48),1f).apply {leftMargin=dp(8)})
        tools.addView(icon("clear","Clear queue"){action("clear")},LayoutParams(dp(48),dp(48)).apply {leftMargin=dp(4)})
        library.addView(tools)
        library.addView(FrameLayout(context).apply {addView(queue,FrameLayout.LayoutParams(-1,-1));addView(emptyQueue,FrameLayout.LayoutParams(-1,-1));queue.emptyView=emptyQueue},LayoutParams(-1,0,1f))
        val playerHeader=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
        playerHeader.addView(if(wide)View(context) else icon("down","Back to your music"){showPlayer(false)},LayoutParams(dp(48),dp(48)))
        playerHeader.addView(playerState,LayoutParams(0,dp(48),1f));playerHeader.addView(icon("menu","Playback options"){action("options")},LayoutParams(dp(48),dp(48)))
        playerState.setOnClickListener {action("sleep")};playerState.contentDescription="Playback state and sleep timer"
        playerPane.addView(playerHeader,LayoutParams(-1,dp(if(wide)40 else 48)))
        val deck=LinearLayout(context).apply {orientation=VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(dp(4),dp(if(wide)6 else 20),dp(4),dp(if(wide)0 else 12))}
        val artSize=if(wide)(resources.configuration.screenHeightDp-520).coerceIn(96,230) else minOf(440,(resources.configuration.screenHeightDp*.43f).toInt().coerceAtLeast(100),resources.configuration.screenWidthDp-72)
        artworkSlot.addView(bigCover,FrameLayout.LayoutParams(-1,-1));artworkSlot.addView(lyrics,FrameLayout.LayoutParams(-1,-1));lyrics.visibility=GONE
        deck.addView(artworkSlot,LayoutParams(if(wide)-1 else dp(artSize),dp(artSize)).apply {bottomMargin=dp(if(wide)12 else 28)})
        deck.addView(lyricsToggle,LayoutParams(-1,dp(44)).apply {bottomMargin=dp(12)})
        deck.addView(trackTitle,LayoutParams(-1,-2));deck.addView(artist,LayoutParams(-1,-2).apply {topMargin=dp(if(wide)6 else 9)})
        deck.addView(sourceLabel,LayoutParams(-1,-2).apply {topMargin=dp(12);bottomMargin=dp(12)})
        if(wide)sourceLabel.visibility=GONE
        deck.addView(timeline,LayoutParams(-1,dp(if(wide)30 else 36)))
        deck.addView(LinearLayout(context).apply {addView(time,LayoutParams(0,-2,1f));addView(duration)},LayoutParams(-1,dp(if(wide)20 else 26)))
        val transport=LinearLayout(context).apply {gravity=Gravity.CENTER}
        transport.addView(previous,LayoutParams(dp(56),dp(56)));transport.addView(play,LayoutParams(dp(if(wide)64 else 76),dp(if(wide)64 else 76)).apply {leftMargin=dp(20);rightMargin=dp(20)});transport.addView(next,LayoutParams(dp(56),dp(56)))
        deck.addView(transport,LayoutParams(-1,dp(if(wide)72 else 96)))
        val secondary=LinearLayout(context).apply {gravity=Gravity.CENTER;addView(shuffle,LayoutParams(dp(48),dp(48)));addView(offline,LayoutParams(0,dp(48),1f).apply {leftMargin=dp(8);rightMargin=dp(8)});addView(repeat,LayoutParams(dp(48),dp(48)))}
        deck.addView(secondary,LayoutParams(-1,-2).apply {topMargin=dp(if(wide)6 else 18)})
        deck.addView(upNext,LayoutParams(-1,dp(44)).apply {topMargin=dp(if(wide)6 else 12)})
        playerPane.addView(deck,LayoutParams(-1,-2))
        val body=LinearLayout(context).apply {orientation=HORIZONTAL}
        if(wide)body.addView(nav,LayoutParams(dp(76),-1).apply {rightMargin=dp(22)})
        body.addView(workspace,LayoutParams(0,-1,1f))
        body.addView(playerScroll,if(wide)LayoutParams(dp(312),-1).apply {leftMargin=dp(24)} else LayoutParams(0,-1,1f))
        addView(body,LayoutParams(-1,0,1f).apply {topMargin=dp(10);bottomMargin=dp(10)})
        if(!wide)playerScroll.visibility=GONE
        val miniRow=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8))}
        miniRow.addView(miniCover,LayoutParams(dp(48),dp(48)))
        val textArea=LinearLayout(context).apply {orientation=VERTICAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),0,dp(6),0);addView(miniTitle);addView(miniArtist,LayoutParams(-1,-2).apply {topMargin=dp(4)});isFocusable=true;isClickable=true;contentDescription="Open player";setOnClickListener {showPlayer(true)}}
        titleSwipe(textArea){showPlayer(true)}
        trackTitle.isFocusable=true;trackTitle.contentDescription="Track title. Swipe left for next, right for previous"
        titleSwipe(trackTitle){}
        bigCover.setOnClickListener {showPlayer(true)};bigCover.isClickable=true;bigCover.isFocusable=true;bigCover.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES;bigCover.contentDescription="Open immersive player"
        miniCover.setOnClickListener {showPlayer(true)};miniCover.contentDescription="Open player";miniCover.isFocusable=true
        miniRow.addView(textArea,LayoutParams(0,-1,1f));miniRow.addView(miniPlay,LayoutParams(dp(48),dp(48)));miniRow.addView(miniNext,LayoutParams(dp(48),dp(48)))
        mini.addView(miniRow,LayoutParams(-1,dp(64)));mini.addView(miniProgress,LayoutParams(-1,dp(2)))
        addView(notice,LayoutParams(-1,-2));addView(mini,LayoutParams(-1,dp(66)).apply {bottomMargin=dp(5)})
        if(!wide)addView(nav,LayoutParams(-1,dp(64)))
        showSection("home");bind(null);setArtwork(null);rhythmReady=true
    }
    private fun toggleLyrics(){
        if(!wide){showPlayer(true);immersive?.showLyrics();return}
        lyricsVisible=!lyricsVisible;lyricsToggle.text=if(lyricsVisible)"Artwork" else "Lyrics"
        fun swap(){bigCover.visibility=if(lyricsVisible)GONE else VISIBLE;lyrics.visibility=if(lyricsVisible)VISIBLE else GONE}
        artworkSlot.animate().cancel()
        if(effectsActive && ValueAnimator.areAnimatorsEnabled()){artworkSlot.cameraDistance=dp(1600).toFloat();artworkSlot.animate().rotationY(85f).setDuration(160).withEndAction {swap();artworkSlot.rotationY=-85f;artworkSlot.animate().rotationY(0f).setDuration(210).start()}.start()}else{artworkSlot.rotationY=0f;swap()}
        if(lyricsVisible)action("lyrics")
    }
    fun wantsLyrics()=lyricsVisible || immersive?.isShowing==true
    internal fun lyrics(value:Lyrics,id:String){lastLyrics=value to id;lyrics.show(value,id);immersive?.lyrics(value,id)}
    fun lyricsStatus(value:String){lastLyrics=null;lyrics.status(value);immersive?.lyricsStatus(value)}
    private fun titleSwipe(view:View,tap:()->Unit){
        var x=0f;var y=0f;var claimed=false
        view.setOnClickListener {tap()}
        view.setOnTouchListener {v,event->
            when(event.actionMasked){
                android.view.MotionEvent.ACTION_DOWN->{x=event.x;y=event.y;claimed=false;true}
                android.view.MotionEvent.ACTION_MOVE->{if(TrackSwipe.direction(event.x-x,event.y-y,dp(12).toFloat())!=0){claimed=true;v.parent.requestDisallowInterceptTouchEvent(true)};true}
                android.view.MotionEvent.ACTION_UP->{
                    val direction=TrackSwipe.direction(event.x-x,event.y-y,dp(48).toFloat())
                    v.parent.requestDisallowInterceptTouchEvent(false)
                    if(direction!=0 && selected)action(if(direction>0)"next" else "previous")
                    else if(!claimed)v.performClick()
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL->{v.parent.requestDisallowInterceptTouchEvent(false);true}
                else->false
            }
        }
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();updateRhythmClock()}
    override fun onDetachedFromWindow(){stopRhythmClock();super.onDetachedFromWindow()}
    override fun onWindowVisibilityChanged(visibility:Int){super.onWindowVisibilityChanged(visibility);if(rhythmReady)updateRhythmClock()}
    private fun stopRhythmClock(){
        rhythmClock?.cancel();rhythmClock=null;MusicEnergy.requested=false
        if(rhythmReady){miniCover.tick(false);bigCover.tick(false);rhythmVisuals.clear();brand.pulse(VisualPulse.REST);currentSurface.pulse(0f);immersive?.pulse(VisualPulse.REST)}
    }
    private fun updateRhythmClock(){
        if(!rhythmReady)return
        val run=effectsActive && rhythmPlaying && isAttachedToWindow && windowVisibility==VISIBLE && ValueAnimator.areAnimatorsEnabled()
        if(!run){stopRhythmClock();return}
        MusicEnergy.requested=true
        if(rhythmClock!=null)return
        rhythmClock=ValueAnimator.ofFloat(0f,1f).apply {
            duration=1000;repeatCount=ValueAnimator.INFINITE
            addUpdateListener {
                if(!ValueAnimator.areAnimatorsEnabled()){stopRhythmClock();return@addUpdateListener}
                val pulse=rhythmVisuals.update(MusicRhythm.read());miniCover.tick(true);bigCover.tick(true);bigCover.pulse(pulse);brand.pulse(pulse);currentSurface.pulse(pulse.ambient);immersive?.pulse(pulse)
            };start()
        }
    }
    private fun availability(id:String?)=when {id?.startsWith("youtube:")==true->"Online";id?.startsWith("download:")==true->"Downloaded · available offline";id?.startsWith("content:")==true->"Local file";else->"Audio"}
    private fun coverRect(view:View):Rect? {
        if(view.visibility!=VISIBLE || view.width==0 || !view.isShown)return null
        val rootPos=IntArray(2);val viewPos=IntArray(2);getLocationInWindow(rootPos);view.getLocationInWindow(viewPos)
        return Rect(viewPos[0]-rootPos[0],viewPos[1]-rootPos[1],viewPos[0]-rootPos[0]+view.width,viewPos[1]-rootPos[1]+view.height)
    }
    private fun stopMotion(){artworkSlot.animate().cancel();artworkSlot.rotationY=0f;motion?.cancel();motion=null;flight?.let {overlay.remove(it)};flight=null;bigCover.alpha=1f;miniCover.alpha=1f;playerScroll.animate().cancel();playerScroll.alpha=1f;playerScroll.translationY=0f}
    fun showPlayer(show:Boolean){
        if(!show){immersive?.dismiss();return}
        if(immersive?.isShowing==true)return
        immersive=ImmersivePlayer(context,action,seek).also {dialog->dialog.bind(lastPlayer);dialog.artwork(art);dialog.origin(recordOrigin());dialog.restorePhase(if(wide)bigCover.rotationPhase() else miniCover.rotationPhase());lastLyrics?.let {dialog.lyrics(it.first,it.second)};dialog.setOnDismissListener {immersive=null};dialog.show()}
    }
    private fun recordOrigin():android.graphics.RectF? {
        immersive?.coverOrigin()?.let {return it}
        val v=if(wide)bigCover else miniCover
        if(!v.isShown || v.width==0)return null
        val p=IntArray(2);v.getLocationOnScreen(p);val m=resources.displayMetrics
        return android.graphics.RectF(p[0].toFloat()/m.widthPixels,p[1].toFloat()/m.heightPixels,(p[0]+v.width).toFloat()/m.widthPixels,(p[1]+v.height).toFloat()/m.heightPixels)
    }
    internal fun presentation()=PlayerPresentation(immersive?.isShowing==true,immersive?.lyricsVisible() ?: lyricsVisible,art,lastLyrics,recordOrigin(),immersive?.rotationPhase() ?: if(wide)bigCover.rotationPhase() else miniCover.rotationPhase(),immersive?.isImmersive()==true)
    internal fun restorePresentation(value:PlayerPresentation,expand:Boolean){
        setArtwork(value.artwork);bigCover.restorePhase(value.phase);miniCover.restorePhase(value.phase);value.lyrics?.let {lyrics(it.first,it.second)}
        if((value.open || expand) && lastPlayer?.currentMediaItem!=null){
            showPlayer(true);immersive?.restoreWords(if(expand && !value.open)true else value.words);immersive?.restoreImmersive(value.immersed);immersive?.origin(value.origin);immersive?.restorePhase(value.phase)
        }
    }
    fun closePlayer():Boolean {val dialog=immersive ?: return false;dialog.dismiss();return true}
    fun showSection(id:String){library.visibility=if(id=="queue")VISIBLE else GONE;browser.visibility=if(id=="queue")GONE else VISIBLE;browser.section(id);sections.forEach {(key,pair)->val selected=key==id;pair.first.update(pair.first.symbol,if(selected)MusicStyle.accent else MusicStyle.muted);pair.second.setTextColor(if(selected)MusicStyle.accent else MusicStyle.muted);sectionViews[key]?.isSelected=selected;sectionPills[key]?.background=if(selected)MusicStyle.control(context) else null};showPlayer(false)}
    internal fun catalog(items:List<MusicShelf>,caption:String,asList:Boolean=false,heading:String?=null,mode:String="discover"){browser.show(items,caption,asList,heading,mode)}
    internal fun collections(items:List<MusicCard>,caption:String){browser.collections(items,caption)}
    internal fun editableCollection(value:Boolean){browser.editableCollection(value)}
    fun catalogMore(available:Boolean,busy:Boolean=false){browser.onMore={action("catalog_more")};browser.more(available,busy)}
    internal fun personal(recent:List<MusicCard>,pinned:List<MusicCard>){browser.personal(recent,pinned)}
    fun catalogStatus(message:String){browser.loading(message)}
    fun catalogPaused(){browser.pauseLoading()}
    fun pauseArtwork(){effectsActive=false;stopRhythmClock();motionSerial++;stopMotion();background=currentSurface;browser.pause()}
    fun resumeArtwork(){effectsActive=true;updateRhythmClock();browser.resume()}
    fun release(){immersive?.dismiss();immersive=null;draggingQueue=false;queue.removeCallbacks(dragScroll);pauseArtwork();browser.close()}
    fun downloadState(active:Boolean){downloading=active;offline.text=if(active)"Cancel download" else "Save offline";if(active){offline.isEnabled=true;offline.alpha=1f}}
    fun message(value:String){val routine=value.startsWith("Local playback") || value.startsWith("Online ·") || value=="Preparing audio…";notice.visibility=if(routine)GONE else VISIBLE;notice.text=value}
    fun setQueue(values:List<QueuePresentation.Row>){rows.clear();rows.addAll(values);rowIndexes=rows.withIndex().associate {it.value.id to it.index};selectedRow=rows.indexOfFirst {it.selected}.takeIf {it>=0};queueAdapter.notifyDataSetChanged();queueSummary.text=if(rows.isEmpty())"The next song starts with you." else "${rows.size} tracks · hold the handle to reorder"}
    fun selectQueue(id:String?){
        val next=rowIndexes[id];if(next==selectedRow)return
        selectedRow?.let {rows[it]=rows[it].copy(selected=false)};next?.let {rows[it]=rows[it].copy(selected=true)}
        selectedRow=next;queueAdapter.notifyDataSetChanged()
    }
    internal fun suggest(query:String,items:List<MusicCard>){browser.suggest(query,items)}
    internal fun preferences(home:HomeArrangement,history:List<String>){browser.preferences(home,history)}
    fun bind(player:Player?,title:String?=null,artistName:String?=null) {
        lastPlayer=player;immersive?.bind(player);immersive?.liked(player?.currentMediaItem?.mediaId?.let(::tasteId) in favoriteIds)
        browser.playing(player?.currentMediaItem?.mediaId,player?.isPlaying==true)
        val nextIndex=player?.nextMediaItemIndex ?: -1
        upNext.text=if(nextIndex>=0)"Up next · ${player?.getMediaItemAt(nextIndex)?.mediaMetadata?.title ?: "Track"}" else "Queue · no next song"
        rhythmPlaying=player?.isPlaying==true;updateRhythmClock()
        selected=player?.currentMediaItem!=null
        val name=title ?: player?.mediaMetadata?.title?.toString() ?: "Find something you love"
        val by=artistName?.takeIf {it.isNotBlank()} ?: player?.mediaMetadata?.artist?.toString()?.takeIf {it.isNotBlank()} ?: if(selected)availability(player?.currentMediaItem?.mediaId) else "Play a song. Make yourself at home."
        trackTitle.text=name;miniTitle.text=name;artist.text=by;miniArtist.text=by
        artist.apply {isClickable=selected;isFocusable=selected;contentDescription="Open artist $by";setOnClickListener {if(selected)onArtist(by)}}
        val key="$name $by";if(art==null && key!=fallbackKey){fallbackKey=key;paintCover(null)}
        playerState.text=when {player?.playerError!=null->"PLAYBACK STOPPED";player?.playbackState==Player.STATE_BUFFERING->"GETTING READY";player?.isPlaying==true->"NOW PLAYING";selected->"PAUSED";else->"YOUR PLAYER"}
        sourceLabel.text=if(selected)availability(player?.currentMediaItem?.mediaId) else ""
        play.update(if(player?.isPlaying==true)"pause" else "play");miniPlay.update(play.symbol)
        for(control in listOf(play,miniPlay,previous,next,miniNext,shuffle,repeat))control.isEnabled=selected
        previous.isEnabled=selected && (player?.hasPreviousMediaItem()==true || player?.isCurrentMediaItemSeekable==true);next.isEnabled=selected && player?.hasNextMediaItem()==true;miniNext.isEnabled=next.isEnabled
        shuffle.update("shuffle",if(player?.shuffleModeEnabled==true)MusicStyle.accent else MusicStyle.muted)
        repeat.update(if(player?.repeatMode==Player.REPEAT_MODE_ONE)"repeat_one" else "repeat",if(player?.repeatMode!=null && player.repeatMode!=Player.REPEAT_MODE_OFF)MusicStyle.accent else MusicStyle.muted)
        repeat.contentDescription="Repeat: ${when(player?.repeatMode){Player.REPEAT_MODE_ONE->"one track";Player.REPEAT_MODE_ALL->"all tracks";else->"off"}}"
        offline.isEnabled=downloading || player?.currentMediaItem?.mediaId?.startsWith("youtube:")==true;offline.alpha=if(offline.isEnabled)1f else .4f
        mini.visibility=if(!wide && !fullPlayer && selected)VISIBLE else GONE
        updateProgress(player)
    }
    fun updateProgress(player:Player?) {
        immersive?.progress(player)
        updateRhythmClock()
        if(sleepDeadline>0)playerState.text="SLEEP · "+formatTime((sleepDeadline-android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
        else if(sleepEnd)playerState.text="SLEEP · END OF TRACK"
        else playerState.text=when {player?.playerError!=null->"PLAYBACK STOPPED";player?.playbackState==Player.STATE_BUFFERING->"GETTING READY";player?.isPlaying==true->"NOW PLAYING";player?.currentMediaItem!=null->"PAUSED";else->"YOUR PLAYER"}
        durationMs=player?.duration?.takeIf {it!=C.TIME_UNSET && it>0} ?: 0
        val position=player?.currentPosition?.coerceAtLeast(0) ?: 0
        lyrics.position(position)
        timeline.isEnabled=durationMs>0 && player?.isCurrentMediaItemSeekable==true
        if(!dragging){timeline.progress=if(durationMs>0)(position*1000/durationMs).toInt().coerceIn(0,1000) else 0;time.text=formatTime(position)}
        miniProgress.progress=if(durationMs>0)(position*1000/durationMs).toInt().coerceIn(0,1000) else 0
        duration.text=if(durationMs>0)formatTime(durationMs) else "–:–"
    }
    fun tools(extras:android.os.Bundle){
        sleepDeadline=extras.getLong("sleepDeadline");sleepEnd=extras.getBoolean("sleepEnd")
        val title=extras.getString("undoTitle")
        val visible=title!=null && extras.getLong("undoExpires")>android.os.SystemClock.elapsedRealtime()
        undoButton.visibility=if(visible)VISIBLE else GONE;undoButton.text="Undo removal · ${title.orEmpty()}"
    }
    fun prepareArtwork(){motionSerial++;stopMotion();art=null;fallbackKey="";paintCover(null)}
    fun setArtwork(bitmap:Bitmap?) {
        art=bitmap;paintCover(bitmap);immersive?.artwork(bitmap)
        val nextPalette=if(bitmap==null || bitmap.isRecycled)AmbientPalette.resting() else AmbientPalette.sample(bitmap.width,bitmap.height,bitmap::getPixel)
        if(palette.contentEquals(nextPalette))return
        palette=nextPalette
        brand.tint(palette[palette.size/2])
        val nextSurface=AmbientSurface(palette)
        background=if(effectsActive && isAttachedToWindow && ValueAnimator.areAnimatorsEnabled())TransitionDrawable(arrayOf(currentSurface,nextSurface)).apply {isCrossFadeEnabled=true;startTransition(650)} else nextSurface
        currentSurface=nextSurface
    }
    private fun paintCover(bitmap:Bitmap?) {
        for(image in listOf(bigCover,miniCover)) {
            if(bitmap==null)image.setImageDrawable(MusicFallback("${trackTitle.text} ${artist.text}",compact=image===miniCover)) else image.setImageBitmap(bitmap)
        }
    }
    private fun cover(radius:Int)=ImageView(context).apply {background=MusicStyle.box(context,MusicStyle.background,radius,true);clipToOutline=true;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    private fun formatTime(ms:Long):String {val s=ms/1000;return if(s>=3600)String.format(Locale.ROOT,"%d:%02d:%02d",s/3600,s/60%60,s%60) else String.format(Locale.ROOT,"%d:%02d",s/60,s%60)}
}
