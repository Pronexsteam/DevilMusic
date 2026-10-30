// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import java.util.concurrent.Executors

/** Native cover-led catalogue; local collection filters never issue an online request. */
internal class MusicBrowser(context:Context,private val open:(MusicCard)->Unit,
    private val search:(String,String)->Unit,private val home:()->Unit,
    private val addLocal:()->Unit,private val retry:()->Unit,private val pin:(MusicCard)->Unit,private val refreshHome:()->Unit):LinearLayout(context) {
    private fun dp(n:Int)=MusicStyle.dp(context,n)
    private val compactWindow=context.resources.configuration.screenWidthDp<600 && context.resources.configuration.screenHeightDp<760
    private fun text(size:Float,color:Int=MusicStyle.ink,bold:Boolean=false,lines:Int=1)=MusicStyle.text(context,size,color,bold,lines)
    private var sectionId="home"
    private var filter="songs"
    private var pageMode="discover"
    private var listMode=false
    private var listItems=emptyList<MusicCard>()
    private var allItems=emptyList<MusicCard>()
    private val shelves=mutableListOf<MusicShelf>()
    private var catalogShelves=emptyList<MusicShelf>()
    private var recentShelf:MusicShelf?=null
    private var recentHistory=emptyList<MusicCard>()
    private var pinnedShelf:MusicShelf?=null
    private var pinnedIds=emptySet<String>()
    private var currentTrack:String?=null
    private var trackPlaying=false
    private fun trackKey(id:String)=id.removePrefix("youtube:").removePrefix("download:")
    fun playing(id:String?,playing:Boolean){
        val key=id?.let(::trackKey)
        if(currentTrack==key && trackPlaying==playing)return
        currentTrack=key;trackPlaying=playing
        if(pageMode=="discover")adapter.notifyDataSetChanged()
    }
    var onSuggest:(String)->Unit={}
    var onHomeChange:(HomeArrangement)->Unit={}
    var onClearSearch:()->Unit={}
    var onTrackMenu:(MusicCard)->Unit={}
    var onArtist:(String)->Unit={}
    var onCatalogBack:()->Unit=home
    var onPlaylists:()->Unit={}
    var onLibraryHome:()->Unit={}
    var onPlayCollection:()->Unit={}
    private var libraryCards=emptyList<MusicCard>()
    private val playlistsButton=button("Playlists"){onPlaylists()}.apply {visibility=GONE}
    private val playCollectionButton=button("Play all"){onPlayCollection()}.apply {visibility=GONE}
    private val collectionHero=LinearLayout(context).apply {orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;visibility=GONE;setPadding(0,dp(10),0,dp(18))}
    private var homeArrangement=HomeArrangement()
    private var searches=emptyList<String>()
    private val suggestions=LinearLayout(context).apply {orientation=VERTICAL;background=MusicStyle.panel(context,18);visibility=GONE;setPadding(dp(8),dp(5),dp(8),dp(5))}
    private val customize=MusicGlyph(context,"tune",MusicStyle.muted).apply {contentDescription="Customize Home";isClickable=true;isFocusable=true;background=MusicStyle.control(context,inset=4);setOnClickListener {customizeHome()}}
    private val thumbs=ThumbnailLoader()
    private val heading=text(if(compactWindow)24f else 29f,bold=true,lines=2).apply {text="Discover";letterSpacing=-.025f}
    private val status=text(12f,MusicStyle.muted,lines=2).apply {text="Albums, mixes and new favourites"}
    private val query=EditText(context).apply {
        hint="Search music";setHintTextColor(MusicStyle.muted);setTextColor(MusicStyle.ink);textSize=15f;typeface=MusicStyle.face(context);isSingleLine=true;imeOptions=EditorInfo.IME_ACTION_SEARCH
        setPadding(0,0,0,0);background=null
    }
    private val filters=LinearLayout(context)
    private val filterScroll=HorizontalScrollView(context).apply {isHorizontalScrollBarEnabled=false;addView(filters);visibility=GONE}
    private val filterButtons=mutableMapOf<String,TextView>()
    private val back=MusicGlyph(context,"back",MusicStyle.muted).apply {contentDescription="Back to Discover";isClickable=true;isFocusable=true;background=MusicStyle.touch(context);setOnClickListener {if(pageMode=="playlist")onLibraryHome() else onCatalogBack()};visibility=GONE}
    var onMore:()->Unit={}
    private val more=button("Show more"){onMore()}.apply {visibility=GONE}
    fun more(available:Boolean,busy:Boolean=false){more.visibility=if(available)VISIBLE else GONE;more.isEnabled=!busy;more.text=if(busy)"Loading more…" else "Show more"}
    private val retryButton=button("Try again"){retry()}.apply {visibility=GONE}
    private val refreshButton=MusicGlyph(context,"refresh",MusicStyle.muted).apply {contentDescription="Refresh recommendations";isClickable=true;isFocusable=true;background=MusicStyle.control(context,inset=4);setOnClickListener {refreshHome()}}
    private val emptyTitle=text(24f,bold=true,lines=2).apply {gravity=Gravity.CENTER}
    private val emptyText=text(14f,MusicStyle.muted,lines=5).apply {gravity=Gravity.CENTER}
    private val emptyIcon=MusicGlyph(context,"home",MusicStyle.accent)
    private val emptyAction=button("Explore music"){home()}
    private val empty=LinearLayout(context).apply {orientation=VERTICAL;gravity=Gravity.CENTER;setPadding(dp(24),dp(20),dp(24),dp(24));addView(emptyIcon,LayoutParams(dp(68),dp(68)));addView(emptyTitle,LayoutParams(-1,-2).apply {topMargin=dp(18)});addView(emptyText,LayoutParams(-1,-2).apply {topMargin=dp(12)});addView(emptyAction,LayoutParams(-2,dp(48)).apply {topMargin=dp(24)});visibility=GONE}
    private val busyText=text(14f,MusicStyle.muted).apply {gravity=Gravity.CENTER}
    private val busy=LinearLayout(context).apply {orientation=VERTICAL;gravity=Gravity.CENTER;addView(ProgressBar(context).apply {indeterminateTintList=android.content.res.ColorStateList.valueOf(MusicStyle.accent)},LayoutParams(dp(28),dp(28)));addView(busyText,LayoutParams(-1,-2).apply {topMargin=dp(18)});visibility=GONE;isClickable=true}
    private data class TrackRow(val root:LinearLayout,val image:ImageView,val title:TextView,val subtitle:TextView,val glyph:MusicGlyph,var binding:Any=Any(),var key:String="")
    private data class ShelfBinding(val shelf:MusicShelf,val pending:List<Pair<ImageView,String>>,val width:Int,val feature:Boolean,val compact:Boolean=false)
    private val adapter=object:BaseAdapter() {
        override fun getCount()=if(listMode)listItems.size else shelves.size
        override fun getItem(position:Int):Any=if(listMode)listItems[position] else shelves[position]
        override fun getItemId(position:Int)=position.toLong()
        override fun getView(position:Int,recycled:View?,parent:ViewGroup):View {
            if(listMode) {
                val item=listItems[position]
                val holder=(recycled?.tag as? TrackRow) ?: newTrackRow()
                val binding=Any();holder.binding=binding
                holder.title.text=item.title
                holder.subtitle.text=when(item.kind){"download"->"Downloaded · ${item.subtitle}";"online"->"Online · ${item.subtitle}";"local"->"Local file · ${item.subtitle}";else->item.subtitle.ifBlank {item.kind.replaceFirstChar(Char::uppercase)}}
                val canOpenArtist=item.kind in setOf("song","online","download","local") && ArtistNavigation.name(item.subtitle).length>=2
                holder.subtitle.isClickable=canOpenArtist;holder.subtitle.isFocusable=canOpenArtist
                holder.subtitle.contentDescription=if(canOpenArtist)"Open artist ${item.subtitle}" else null
                holder.subtitle.setOnClickListener {if(canOpenArtist)onArtist(item.subtitle)}
                holder.glyph.update(if(item.kind in setOf("song","online","local","download"))"play" else "forward",MusicStyle.muted)
                val coverKey="${item.id}|${item.artwork}";if(holder.key!=coverKey){holder.key=coverKey;holder.image.tag=null;holder.image.setImageDrawable(MusicFallback("${item.title} ${item.subtitle}",true))}
                holder.root.setOnClickListener {open(item)};holder.root.contentDescription="${item.kind}: ${item.title}, ${item.subtitle}"
                holder.root.setOnLongClickListener {if(item.kind in setOf("album","playlist"))albumMenu(holder.root,item) else if(item.kind in setOf("artist","collection"))open(item) else onTrackMenu(item);true}
                holder.glyph.apply {update(if(item.kind in setOf("artist","collection"))"forward" else "menu",MusicStyle.muted);isClickable=true;isFocusable=true;contentDescription=if(item.kind in setOf("artist","collection"))"Open ${item.title}" else "Actions for ${item.title}";setOnClickListener {if(item.kind in setOf("album","playlist"))albumMenu(this,item) else if(item.kind in setOf("artist","collection"))open(item) else onTrackMenu(item)}}
                (item.id.takeIf {it.startsWith("download:")} ?: item.artwork)?.let {url->thumbs.prime(holder.image,url);holder.root.post {if(holder.binding===binding && holder.image.getLocalVisibleRect(Rect()))thumbs.load(holder.image,url)}}
                return holder.root
            }
            val shelf=shelves[position]
            if(pageMode=="library-home")return libraryGrid(shelf,recycled)
            val feature=pageMode=="discover" && shelf===shelves.firstOrNull {it!==recentShelf && it!==pinnedShelf && it.items.isNotEmpty()}
            val discoveries=pageMode=="discover" && !feature && shelf!==recentShelf && shelf!==pinnedShelf && shelf.items.isNotEmpty() && shelf.items.all {it.kind in setOf("song","online","download","local")}
            val binding=recycled?.tag as? ShelfBinding
            if(shelf!==recentShelf && shelf!==pinnedShelf && binding?.shelf==shelf && binding.width==width && binding.feature==feature && binding.compact==discoveries){recycled.post {binding.pending.forEach {(image,url)->if(image.getLocalVisibleRect(Rect()))thumbs.load(image,url)}};return recycled}
            if(shelf===recentShelf || shelf===pinnedShelf)return quickShelf(shelf)
            if(discoveries)return discoveryShelf(shelf)
            val row=LinearLayout(context).apply {orientation=VERTICAL;setPadding(0,dp(16),0,dp(24))}
            val titleRow=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;addView(text(22f,bold=true,lines=2).apply {text=shelf.title},LayoutParams(0,-2,1f))}
            row.addView(titleRow,LayoutParams(-1,-2).apply {bottomMargin=dp(12)})
            val cards=LinearLayout(context)
            val scroll=HorizontalScrollView(context).apply {isHorizontalScrollBarEnabled=false;clipToPadding=false;addView(cards)}
            val pending=mutableListOf<Pair<ImageView,String>>()
            val logicalWidth=if(width>0)width/resources.displayMetrics.density else resources.configuration.screenWidthDp-32f
            if(feature){
                val item=shelf.items.first()
                val spotlight=HomeFeature(context,item,logicalWidth,{open(item)}){anchor->if(item.kind in setOf("album","playlist"))albumMenu(anchor,item) else onTrackMenu(item)}
                row.addView(spotlight,LayoutParams(-1,-2).apply {bottomMargin=dp(20)})
                (item.id.takeIf {it.startsWith("download:")} ?: item.artwork)?.let {thumbs.prime(spotlight.artwork,it);pending.add(spotlight.artwork to it)}
            }
            val size=when {logicalWidth>=800->176;logicalWidth>=520->164;logicalWidth>=360->156;else->144}
            for(item in if(feature)shelf.items.drop(1) else shelf.items) {
                val card=LinearLayout(context).apply {orientation=VERTICAL;background=MusicStyle.touch(context,radius=16);isClickable=true;isFocusable=true;contentDescription="${item.kind}: ${item.title}, ${item.subtitle}";setOnClickListener {open(item)}}
                val image=ImageView(context).apply {scaleType=ImageView.ScaleType.CENTER_CROP;background=MusicStyle.box(context,MusicStyle.surface,if(item.kind=="artist")100 else 16);clipToOutline=true;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;setImageDrawable(MusicFallback(item.title,true))}
                val artwork=FrameLayout(context).apply {addView(image,FrameLayout.LayoutParams(-1,-1))}
                if(item.kind in setOf("album","playlist","song","online","local","download"))artwork.addView(MusicGlyph(context,"menu").apply {
                    contentDescription="Actions for ${item.title}";isClickable=true;isFocusable=true;background=MusicStyle.touch(context,Color.argb(155,8,10,14),24)
                    setOnClickListener {if(item.kind in setOf("album","playlist"))albumMenu(this,item) else onTrackMenu(item)}
                },FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP or Gravity.END))
                card.addView(artwork,LayoutParams(dp(size),dp(size)))
                card.addView(text(15f,bold=true,lines=2).apply {text=item.title;minLines=2},LayoutParams(-1,-2).apply {topMargin=dp(11)})
                card.addView(text(12f,MusicStyle.muted).apply {text=item.subtitle.ifBlank {item.kind.replaceFirstChar(Char::uppercase)}},LayoutParams(-1,-2).apply {topMargin=dp(5);bottomMargin=dp(4)})
                cards.addView(card,LayoutParams(dp(size),-2).apply {rightMargin=dp(16)})
                (item.id.takeIf {it.startsWith("download:")} ?: item.artwork)?.let {thumbs.prime(image,it);pending.add(image to it)}
            }
            fun visibleCovers(){pending.forEach {(image,url)->if(image.getLocalVisibleRect(Rect()))thumbs.load(image,url)}}
            scroll.setOnScrollChangeListener {_,_,_,_,_->visibleCovers()};scroll.post {visibleCovers()}
            row.addView(scroll,LayoutParams(-1,-2));row.tag=ShelfBinding(shelf,pending,width,feature);return row
        }
    }
    private val list=ListView(context).apply {
        adapter=this@MusicBrowser.adapter;divider=null;clipToPadding=false;isVerticalScrollBarEnabled=false;selector=MusicStyle.box(context,Color.TRANSPARENT)
        setOnScrollListener(object:AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view:AbsListView,state:Int){thumbs.scrolling(state==AbsListView.OnScrollListener.SCROLL_STATE_FLING);scheduleCovers(view)}
            override fun onScroll(view:AbsListView,first:Int,visible:Int,total:Int){
                scheduleCovers(view)
            }
        })
    }
    private var coverPassPending=false
    private val visibleRect=Rect()
    private fun scheduleCovers(view:AbsListView){
        if(coverPassPending)return
        coverPassPending=true
        view.postOnAnimation {coverPassPending=false
            for(i in 0 until view.childCount){val child=view.getChildAt(i)
                (child.tag as? ShelfBinding)?.pending?.forEach {(image,url)->if(image.getLocalVisibleRect(visibleRect))thumbs.load(image,url)}
                if(listMode){val row=child.tag as? TrackRow;val item=listItems.getOrNull(view.firstVisiblePosition+i)
                    if(row!=null && item!=null)(item.id.takeIf {it.startsWith("download:")} ?: item.artwork)?.let {thumbs.load(row.image,it)}
                }
            }
        }
    }
    init {
        orientation=VERTICAL
        val searchRow=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;background=MusicStyle.panel(context,20)}
        searchRow.addView(MusicGlyph(context,"search",MusicStyle.muted),LayoutParams(dp(46),dp(48)))
        searchRow.addView(query,LayoutParams(0,dp(52),1f))
        searchRow.addView(MusicGlyph(context,"forward",MusicStyle.accent).apply {contentDescription="Search";isClickable=true;isFocusable=true;background=MusicStyle.touch(context);setOnClickListener {submit()}},LayoutParams(dp(48),dp(48)))
        addView(searchRow,LayoutParams(-1,dp(52)))
        addView(suggestions,LayoutParams(-1,-2).apply {topMargin=dp(7)})
        query.setOnEditorActionListener {_,id,_->if(id==EditorInfo.IME_ACTION_SEARCH){submit();true}else false}
        query.setOnFocusChangeListener {_,focused->filterVisibility();if(focused && sectionId=="home" && pageMode!="history")requestSuggestions() else {suggestions.visibility=GONE;onSuggest("")}}
        query.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){if(sectionId!="home" || pageMode=="history")filterLocal();filterVisibility();if(query.hasFocus() && sectionId=="home" && pageMode!="history")requestSuggestions()}
            override fun afterTextChanged(s:Editable?){}
        })
        for((id,title) in listOf("songs" to "Songs","albums" to "Albums","artists" to "Artists","playlists" to "Playlists","videos" to "Videos")) {
            val button=button(title){filter=id;paintFilters();if(query.text.toString().trim().length>=2)submit()}
            filterButtons[id]=button;filters.addView(button,LayoutParams(-2,dp(44)).apply {rightMargin=dp(6)})
        }
        addView(filterScroll,LayoutParams(-1,dp(54)).apply {topMargin=dp(8)});paintFilters()
        val headline=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;addView(back,LayoutParams(dp(44),dp(48)));addView(heading,LayoutParams(0,-2,1f));addView(retryButton,LayoutParams(-2,dp(44)).apply {leftMargin=dp(8)})}
        addView(headline,LayoutParams(-1,-2).apply {topMargin=dp(if(compactWindow)10 else 22);bottomMargin=dp(7)})
        headline.addView(refreshButton,LayoutParams(dp(48),dp(48)))
        headline.addView(customize,LayoutParams(dp(48),dp(48)))
        headline.addView(playlistsButton,LayoutParams(-2,dp(48)))
        headline.addView(playCollectionButton,LayoutParams(-2,dp(48)).apply {leftMargin=dp(6)})
        addView(collectionHero,LayoutParams(-1,-2))
        addView(status,LayoutParams(-1,-2).apply {bottomMargin=dp(8)})
        if(compactWindow)status.visibility=GONE
        addView(FrameLayout(context).apply {addView(list,FrameLayout.LayoutParams(-1,-1));addView(empty,FrameLayout.LayoutParams(-1,-1));addView(busy,FrameLayout.LayoutParams(-1,-1))},LayoutParams(-1,0,1f))
        addView(more,LayoutParams(-1,dp(48)))
    }
    fun collections(cards:List<MusicCard>,caption:String){
        libraryCards=cards
        show(emptyList(),caption,title="Your library",mode="library-home")
        filterLocal()
    }
    fun editableCollection(value:Boolean){if(pageMode=="playlist")playlistsButton.visibility=if(value)VISIBLE else GONE}
    private fun libraryGrid(shelf:MusicShelf,recycled:View?):View {
        val old=recycled?.tag as? ShelfBinding
        if(old?.shelf==shelf && old.width==width){recycled.post {old.pending.forEach {(v,u)->thumbs.load(v,u)}};return recycled}
        val row=LinearLayout(context).apply {gravity=Gravity.TOP;setPadding(0,dp(6),0,dp(14))}
        val pending=mutableListOf<Pair<ImageView,String>>()
        val columns=libraryColumns()
        for((index,item) in shelf.items.withIndex()){
            val card=LinearLayout(context).apply {orientation=VERTICAL;setPadding(dp(12),dp(12),dp(12),dp(12));background=MusicStyle.touch(context,0xff171d23.toInt(),20,true);isClickable=true;isFocusable=true;contentDescription="Open ${item.title}";setOnClickListener {open(item)};setOnLongClickListener {if(item.kind=="saved_playlist")onTrackMenu(item);true}}
            val artwork=FrameLayout(context).apply {background=MusicStyle.box(context,if(item.kind=="liked")0xff324648.toInt() else 0xff22282e.toInt(),14);clipToOutline=true}
            val image=ImageView(context).apply {scaleType=ImageView.ScaleType.CENTER_CROP;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
            if(item.kind=="liked" || item.kind=="all_tracks"){image.setImageDrawable(CollectionArtwork(item.kind=="liked"));artwork.addView(image,FrameLayout.LayoutParams(-1,-1))}
            else if(item.artwork!=null){artwork.addView(image,FrameLayout.LayoutParams(-1,-1));thumbs.prime(image,item.artwork);pending.add(image to item.artwork)}
            else artwork.addView(MusicGlyph(context,if(item.kind=="liked")"heart" else "collection",MusicStyle.ink),FrameLayout.LayoutParams(dp(50),dp(50),Gravity.CENTER))
            if(item.kind=="saved_playlist")artwork.addView(MusicGlyph(context,"menu").apply {background=MusicStyle.touch(context,0xc010141a.toInt(),24);contentDescription="Edit ${item.title}";isClickable=true;isFocusable=true;setOnClickListener {onTrackMenu(item)}},FrameLayout.LayoutParams(dp(44),dp(44),Gravity.TOP or Gravity.END))
            val available=if(width>0)width/resources.displayMetrics.density else resources.configuration.screenWidthDp-32f
            val coverSize=((available-(columns-1)*10)/columns-24).toInt().coerceAtLeast(72)
            card.addView(artwork,LayoutParams(-1,dp(coverSize)))
            card.addView(text(16f,bold=true,lines=2).apply {text=item.title;minLines=2},LayoutParams(-1,-2).apply {topMargin=dp(12)})
            card.addView(text(12f,MusicStyle.muted).apply {text=item.subtitle},LayoutParams(-1,-2).apply {topMargin=dp(5)})
            row.addView(card,LayoutParams(0,-2,1f).apply {if(index<columns-1)rightMargin=dp(10)})
        }
        repeat(columns-shelf.items.size){row.addView(View(context),LayoutParams(0,1,1f))}
        row.tag=ShelfBinding(shelf,pending,width,false);row.post {pending.forEach {(v,u)->thumbs.load(v,u)}};return row
    }
    private fun libraryColumns():Int {val available=if(width>0)width/resources.displayMetrics.density else resources.configuration.screenWidthDp-32f;return when {available>=740->4;available>=520->3;else->2}}
    private fun showCollectionHero(title:String,caption:String,items:List<MusicCard>){
        collectionHero.removeAllViews()
        val image=ImageView(context).apply {scaleType=ImageView.ScaleType.CENTER_CROP;background=MusicStyle.box(context,MusicStyle.surface,18);clipToOutline=true}
        if(title=="Liked songs" || title=="All songs")image.setImageDrawable(CollectionArtwork(title=="Liked songs"))
        else {image.setImageDrawable(MusicFallback(title,true));items.firstOrNull {it.artwork!=null}?.artwork?.let {u->thumbs.prime(image,u);image.post {thumbs.load(image,u)}}}
        collectionHero.addView(image,LayoutParams(dp(104),dp(104)))
        collectionHero.addView(LinearLayout(context).apply {orientation=VERTICAL;addView(text(22f,bold=true,lines=2).apply {text=title});addView(text(12f,MusicStyle.muted,lines=2).apply {text=caption},LayoutParams(-1,-2).apply {topMargin=dp(7)});addView(button("▶  Play all"){onPlayCollection()}.apply {contentDescription="Play all";isEnabled=items.isNotEmpty();alpha=if(items.isEmpty()).4f else 1f},LayoutParams(-2,dp(46)).apply {topMargin=dp(10)})},LayoutParams(0,-2,1f).apply {leftMargin=dp(18)})
    }
    fun artistHeader(card:MusicCard){
        collectionHero.removeAllViews();collectionHero.visibility=VISIBLE;heading.text="Artist";status.visibility=GONE
        val image=ImageView(context).apply {scaleType=ImageView.ScaleType.CENTER_CROP;background=MusicStyle.box(context,MusicStyle.surface,60);clipToOutline=true;setImageDrawable(MusicFallback(card.title,true));contentDescription="${card.title} artist photo"}
        collectionHero.addView(image,LayoutParams(dp(104),dp(104)))
        collectionHero.addView(LinearLayout(context).apply {orientation=VERTICAL;addView(text(24f,bold=true,lines=2).apply {text=card.title});addView(text(13f,MusicStyle.muted).apply {text="Songs and releases"},LayoutParams(-1,-2).apply {topMargin=dp(8)})},LayoutParams(0,-2,1f).apply {leftMargin=dp(18)})
        card.artwork?.let {url->thumbs.prime(image,url);image.post {thumbs.load(image,url)}}
        back.visibility=VISIBLE;back.contentDescription="Back from artist"
    }
    private fun albumMenu(anchor:View,card:MusicCard) {
        PopupMenu(context,anchor).apply {
            menu.add(if(card.id in pinnedIds)"Unpin from Home" else "Pin ${if(card.kind=="playlist")"mix / playlist" else "album"} to Home")
            setOnMenuItemClickListener {pin(card);true};show()
        }
    }
    private fun quickShelf(shelf:MusicShelf):View {
        val pending=mutableListOf<Pair<ImageView,String>>()
        val group=LinearLayout(context).apply {orientation=VERTICAL;setPadding(0,dp(12),0,dp(22))}
        val header=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;addView(text(21f,bold=true,lines=2).apply {text=shelf.title},LayoutParams(0,-2,1f))}
        if(shelf===recentShelf)header.addView(button("View all"){
            query.setText("");query.clearFocus();show(listOf(MusicShelf("Recently played",recentHistory)),"Your recent music · saved on this device",true,"Recently played","history");query.hint="Filter recently played"
        }.apply {contentDescription="View all recently played songs"},LayoutParams(-2,dp(44)))
        group.addView(header,LayoutParams(-1,-2).apply {bottomMargin=dp(12)})
        val available=if(width>0)width/resources.displayMetrics.density else resources.configuration.screenWidthDp-32f
        val columns=when {available>=800->3;available>=400->2;else->1}
        for(pair in shelf.items.chunked(columns)) {
            val row=LinearLayout(context)
            pair.forEachIndexed {index,item->
                val current=trackKey(item.id)==currentTrack
                val tile=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(70);setPadding(dp(10),dp(8),dp(10),dp(8));background=MusicStyle.touch(context,0xff15191e.toInt(),12);isClickable=true;isFocusable=true;contentDescription="Play ${item.title}, ${item.subtitle}";setOnClickListener {open(item)}
                    if(item.kind in setOf("album","playlist")){contentDescription="Open ${item.title}. Long press for pin actions";setOnLongClickListener {albumMenu(this,item);true}} else setOnLongClickListener {onTrackMenu(item);true}
                }
                if(current)tile.background=MusicStyle.touch(context,0xff202b2d.toInt(),12,true)
                val image=(if(current)VinylArtwork(context) else ImageView(context)).apply {scaleType=ImageView.ScaleType.CENTER_CROP;clipToOutline=true;background=MusicStyle.box(context,MusicStyle.surface,10);setImageDrawable(MusicFallback(item.title,true));importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
                if(image is VinylArtwork){
                    val spin=object:Runnable{override fun run(){if(image.isAttachedToWindow && image.isShown && trackPlaying){image.tick(true);image.postOnAnimation(this)}else image.tick(false)}}
                    image.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
                        override fun onViewAttachedToWindow(v:View){image.postOnAnimation(spin)}
                        override fun onViewDetachedFromWindow(v:View){image.removeCallbacks(spin);image.tick(false)}
                    })
                }
                tile.addView(image,LayoutParams(dp(44),dp(44)))
                tile.addView(LinearLayout(context).apply {orientation=VERTICAL;addView(text(13f,bold=true).apply {text=item.title});addView(text(11f,MusicStyle.muted).apply {text=item.subtitle.ifBlank {"Saved music"}},LayoutParams(-1,-2).apply {topMargin=dp(4)})},LayoutParams(0,-2,1f).apply {leftMargin=dp(10)})
                row.addView(tile,LayoutParams(0,-2,1f).apply {if(index<columns-1)rightMargin=dp(8)})
                (item.id.takeIf {it.startsWith("download:")} ?: item.artwork)?.let {thumbs.prime(image,it);pending.add(image to it)}
            }
            repeat(columns-pair.size){row.addView(View(context),LayoutParams(0,1,1f))}
            group.addView(row,LayoutParams(-1,-2).apply {bottomMargin=dp(8)})
        }
        group.tag=ShelfBinding(shelf,pending,width,false)
        group.post {pending.forEach {(image,url)->if(image.getLocalVisibleRect(Rect()))thumbs.load(image,url)}}
        return group
    }
    private fun discoveryShelf(shelf:MusicShelf):View {
        val group=LinearLayout(context).apply {orientation=VERTICAL;setPadding(0,dp(16),0,dp(24))}
        val head=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
        head.addView(text(22f,bold=true,lines=2).apply {text=shelf.title},LayoutParams(0,-2,1f))
        if(shelf.items.size>4)head.addView(button("View all"){
            query.setText("");query.clearFocus();show(listOf(shelf),"From your recommendations",true,shelf.title,"history");query.hint="Filter this selection"
        },LayoutParams(-2,dp(44)))
        group.addView(head,LayoutParams(-1,-2).apply {bottomMargin=dp(10)})
        val pending=mutableListOf<Pair<ImageView,String>>()
        for(item in shelf.items.take(4)){
            val holder=newTrackRow();holder.title.text=item.title;holder.subtitle.text=item.subtitle
            holder.root.setOnClickListener {open(item)};holder.root.setOnLongClickListener {onTrackMenu(item);true}
            holder.image.setImageDrawable(MusicFallback(item.title,true))
            holder.glyph.apply {update("menu");isClickable=true;isFocusable=true;contentDescription="Actions for ${item.title}";setOnClickListener {onTrackMenu(item)}}
            group.addView(holder.root,LayoutParams(-1,-2))
            (item.id.takeIf {it.startsWith("download:")} ?: item.artwork)?.let {thumbs.prime(holder.image,it);pending.add(holder.image to it)}
        }
        group.tag=ShelfBinding(shelf,pending,width,false,true)
        group.post {pending.forEach {(image,url)->if(image.getLocalVisibleRect(Rect()))thumbs.load(image,url)}}
        return group
    }
    fun personal(recent:List<MusicCard>,pinned:List<MusicCard>) {
        pinnedIds=pinned.map {it.id}.toSet()
        if(recentHistory==recent.take(80) && pinnedShelf?.items.orEmpty()==pinned.take(24))return
        recentHistory=recent.take(80)
        recentShelf=recent.take(6).takeIf {it.isNotEmpty()}?.let {MusicShelf("Recently played",it)}
        pinnedShelf=pinned.take(24).takeIf {it.isNotEmpty()}?.let {MusicShelf("Pinned collections",it)}
        if(pageMode=="discover" && !listMode){rebuildShelves();adapter.notifyDataSetChanged();emptyState()}
    }
    private fun availableShelves()=listOfNotNull(pinnedShelf,recentShelf)+catalogShelves
    private fun rebuildShelves(){shelves.clear();shelves.addAll(if(pageMode=="discover")homeArrangement.arrange(availableShelves()) else catalogShelves)}
    fun preferences(home:HomeArrangement,history:List<String>){homeArrangement=home;searches=history;if(pageMode=="discover"){rebuildShelves();adapter.notifyDataSetChanged()};if(query.hasFocus() && query.text.isBlank())requestSuggestions()}
    private fun customizeHome(){HomeEditor.show(context,availableShelves().map {it.title}.distinct(),homeArrangement,::applyHome)}
    private fun applyHome(value:HomeArrangement){homeArrangement=value;rebuildShelves();adapter.notifyDataSetChanged();emptyState();onHomeChange(value)}
    private fun suggestionLabel(value:String)=text(11f,MusicStyle.muted,true).apply {text=value;setPadding(dp(8),dp(5),0,dp(5))}
    private fun requestSuggestions(){
        suggestions.removeAllViews();val q=query.text.toString().trim()
        suggestions.visibility=VISIBLE
        if(q.length<2){
            onSuggest("");suggestions.addView(suggestionLabel(if(searches.isEmpty())"Search songs, artists or albums" else "Recent searches"))
            for(term in searches.take(4))suggestions.addView(button(term){query.setText(term);submit()},LayoutParams(-1,dp(42)))
            if(searches.isNotEmpty())suggestions.addView(button("Clear search history"){searches=emptyList();onClearSearch();requestSuggestions()},LayoutParams(-1,dp(40)))
        }else {suggestions.addView(suggestionLabel("Finding suggestions…"));onSuggest(q)}
    }
    fun suggest(q:String,items:List<MusicCard>){
        if(!query.hasFocus() || sectionId!="home" || query.text.toString().trim()!=q)return
        suggestions.removeAllViews();suggestions.addView(suggestionLabel(if(items.isEmpty())"Press Search for all results" else "Songs · tap to play"))
        for(card in items.take(4)){
            val row=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(6),dp(4),dp(6),dp(4));background=MusicStyle.touch(context);isClickable=true;isFocusable=true;contentDescription="Play ${card.title}, ${card.subtitle}";setOnClickListener {query.clearFocus();(context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(query.windowToken,0);open(card)}}
            val image=ImageView(context).apply {scaleType=ImageView.ScaleType.CENTER_CROP;background=MusicStyle.box(context,MusicStyle.surface,9);clipToOutline=true;setImageDrawable(MusicFallback(card.title,true))}
            row.addView(image,LayoutParams(dp(40),dp(40)))
            row.addView(LinearLayout(context).apply {orientation=VERTICAL;addView(text(13f,bold=true).apply {text=card.title});addView(text(11f,MusicStyle.muted).apply {text=card.subtitle})},LayoutParams(0,-2,1f).apply {leftMargin=dp(10)})
            suggestions.addView(row,LayoutParams(-1,dp(52)));card.artwork?.let {url->thumbs.prime(image,url);image.post {thumbs.load(image,url)}}
        }
    }
    private fun newTrackRow():TrackRow {
        val row=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(2),dp(10),dp(2),dp(10));background=MusicStyle.touch(context,radius=14);isFocusable=true;isClickable=true}
        val image=ImageView(context).apply {scaleType=ImageView.ScaleType.CENTER_CROP;clipToOutline=true;background=MusicStyle.box(context,MusicStyle.surface,12);importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
        val title=text(15f,bold=true);val subtitle=text(12f,MusicStyle.muted)
        row.addView(image,LayoutParams(dp(52),dp(52)))
        row.addView(LinearLayout(context).apply {orientation=VERTICAL;setPadding(dp(12),0,dp(6),0);addView(title);addView(subtitle,LayoutParams(-1,-2).apply {topMargin=dp(6)})},LayoutParams(0,-2,1f))
        val glyph=MusicGlyph(context,"play",MusicStyle.muted);row.addView(glyph,LayoutParams(dp(44),dp(48)))
        return TrackRow(row,image,title,subtitle,glyph).also {row.tag=it}
    }
    private fun button(title:String,click:()->Unit)=text(12f,bold=true).apply {text=title;gravity=Gravity.CENTER;setPadding(dp(14),dp(8),dp(14),dp(8));background=MusicStyle.touch(context,Color.argb(70,34,42,48),20);isClickable=true;isFocusable=true;setOnClickListener {click()}}
    private fun paintFilters(){filterButtons.forEach {(id,b)->b.setTextColor(if(id==filter)MusicStyle.background else MusicStyle.muted);b.background=MusicStyle.touch(context,if(id==filter)MusicStyle.accent else Color.argb(70,34,42,48),20)}}
    private fun filterVisibility(){filterScroll.visibility=if(sectionId=="home" && pageMode!="history" && (query.hasFocus() || pageMode=="search"))VISIBLE else GONE}
    private fun submit(){if(sectionId!="home" || pageMode=="history"){filterLocal();query.clearFocus();return};val value=query.text.toString().trim();if(value.length !in 2..200){query.error="Enter at least 2 characters";return};query.clearFocus();(context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(query.windowToken,0);search(value,filter)}
    private fun filterLocal(){val value=query.text.toString().trim();if(pageMode=="library-home"){
        val visible=libraryCards.filter {value.isBlank() || it.title.contains(value,true)};shelves.clear();shelves.addAll(visible.chunked(libraryColumns()).map {MusicShelf("Collections",it)})
    }else listItems=if(value.isEmpty())allItems else allItems.filter {it.title.contains(value,true) || it.subtitle.contains(value,true)};adapter.notifyDataSetChanged();emptyState()}
    fun section(id:String){if(sectionId!=id){sectionId=id;query.setText("");query.clearFocus()};query.hint=when(id){"library"->"Filter your library";"downloads"->"Filter downloads";else->"Songs, artists, albums"};filterVisibility()}
    fun show(items:List<MusicShelf>,caption:String,asList:Boolean=false,title:String?=null,mode:String="discover") {
        thumbs.cancel();pageMode=mode;listMode=asList;allItems=if(asList)items.flatMap {it.items} else emptyList();listItems=allItems;catalogShelves=items;rebuildShelves()
        heading.text=title ?: if(mode=="discover")"Discover" else items.firstOrNull()?.title ?: "Music"
        status.text=caption;status.setTextColor(MusicStyle.muted);retryButton.visibility=GONE;back.visibility=if(mode=="collection" || mode=="history")VISIBLE else GONE;refreshButton.visibility=if(mode=="discover")VISIBLE else GONE
        status.visibility=if(compactWindow && mode=="discover")GONE else VISIBLE
        customize.visibility=if(mode=="discover")VISIBLE else GONE
        playlistsButton.visibility=if(mode=="library-home" || (mode=="playlist" && title!="Liked songs" && title!="All songs"))VISIBLE else GONE
        playlistsButton.text=if(mode=="library-home")"+ New" else "Edit"
        playCollectionButton.visibility=GONE
        collectionHero.visibility=if(mode=="playlist")VISIBLE else GONE
        if(mode=="playlist"){heading.text="Your music";showCollectionHero(title ?: "Playlist",caption,allItems);status.visibility=GONE}
        if(mode=="playlist"){back.visibility=VISIBLE;back.contentDescription="Back to library"}else back.contentDescription="Back to Discover"
        busy.visibility=GONE;list.visibility=VISIBLE
        if(mode=="discover"){query.hint="Songs, artists, albums";query.setText("");query.clearFocus()}
        adapter.notifyDataSetChanged();if(sectionId!="home" || pageMode=="history")filterLocal() else emptyState();filterVisibility()
    }
    private fun emptyState(){val count=adapter.count;empty.visibility=if(count==0 && busy.visibility!=VISIBLE)VISIBLE else GONE
        emptyIcon.update(if(sectionId=="downloads")"download" else "library",MusicStyle.accent)
        emptyTitle.text=when {query.text.isNotEmpty()->"No matches yet";pageMode=="playlist"->"Make this collection yours";sectionId=="downloads"->"Good music. Anywhere.";sectionId=="library"->"Make this space yours";else->"Nothing to show just yet"}
        emptyText.text=when {query.text.isNotEmpty()->"Try another title or artist.";sectionId=="downloads"->"Save a song from the player and it will be ready here, even without a connection.";sectionId=="library"->"Your played tracks and local files will find a home here.";else->"Search for an artist, album or song to get started."}
        emptyAction.text=if(sectionId=="library")"Add local files" else "Find your music";emptyAction.setOnClickListener {if(sectionId=="library")addLocal() else {query.requestFocus();(context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(query,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)}}
        if(pageMode=="playlist" && query.text.isBlank()){emptyText.text="Use the heart to save favorites, or add songs to a playlist from their ⋯ menu.";emptyAction.text="Discover music";emptyAction.setOnClickListener {home()}}
    }
    fun loading(message:String) {
        more(false)
        if(message.contains("unavailable",true)) {
            status.visibility=VISIBLE
            busy.visibility=GONE;list.visibility=VISIBLE;status.text=if(adapter.count>0)"Could not load. Showing the previous page." else "Could not connect to music.";status.setTextColor(Color.rgb(222,192,153));retryButton.visibility=VISIBLE;emptyState()
            if(adapter.count==0){emptyTitle.text="A quiet moment";emptyText.text="Check your connection and try again.";emptyAction.text="Try again";emptyAction.setOnClickListener {retry()}}
        } else {retryButton.visibility=GONE;list.visibility=INVISIBLE;empty.visibility=GONE;busy.visibility=VISIBLE;busyText.text=message}
    }
    fun pauseLoading(){busy.visibility=GONE;list.visibility=VISIBLE;status.visibility=VISIBLE;status.text="Browsing paused while audio starts";retryButton.visibility=VISIBLE;emptyState()}
    fun close(){thumbs.close()}
    fun pause(){thumbs.cancel()}
    fun resume(){adapter.notifyDataSetChanged()}
}
private class ThumbnailLoader {
    companion object {
        private val cache=object:android.util.LruCache<String,Bitmap>(12*1024*1024){override fun sizeOf(key:String,value:Bitmap)=value.byteCount}
    }
    private val worker=Executors.newFixedThreadPool(2){task->Thread({android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);task.run()},"music-covers")}
    private val main=Handler(Looper.getMainLooper())
    private val tasks=mutableListOf<java.util.concurrent.Future<*>>()
    private val inFlight=mutableMapOf<String,MutableList<Pair<ImageView,Pair<Int,String>>>>()
    private var generation=0
    private var flinging=false
    private data class Binding(val view:ImageView,val tag:Pair<Int,String>,val bitmap:Bitmap)
    private val waiting=ArrayDeque<Binding>()
    private var flushPending=false
    fun scrolling(value:Boolean){flinging=value;if(!value)flush()}
    private fun bind(view:ImageView,tag:Pair<Int,String>,bitmap:Bitmap){
        if(waiting.size>=48){waiting.removeFirst().let {if(it.view.tag==it.tag)it.view.tag=null}}
        waiting.addLast(Binding(view,tag,bitmap));flush()
    }
    private fun flush(){
        if(flinging || flushPending || waiting.isEmpty())return
        flushPending=true
        main.postDelayed({flushPending=false
            if(!flinging)repeat(minOf(2,waiting.size)){val b=waiting.removeFirst();if(b.tag.first==generation && b.view.tag==b.tag){
                if(b.view.isShown)b.view.setImageBitmap(b.bitmap) else b.view.tag=null
            }}
            flush()
        },16)
    }
    // Paint a previously seen cover before the first layout/draw. No I/O or decode here.
    // The normal sized request still runs later, so a small cached preview never limits quality.
    fun prime(view:ImageView,url:String){
        if(view.tag!=null)return
        for(size in intArrayOf(512,384,256))cache.get("$size:$url")?.let {view.setImageBitmap(it);return}
    }
    fun load(view:ImageView,url:String) {
        if(flinging)return
        val target=when {view.width>384->512;view.width>256->384;else->256}
        val key="$target:$url"
        val tag=generation to key
        if(view.tag==tag)return;view.tag=tag
        cache.get(key)?.let {view.setImageBitmap(it);OnlineArtwork.trace("memory_hit");return}
        inFlight[key]?.let {it.add(view to tag);return}
        if(tasks.count {!it.isDone}>=24){view.tag=null;return}
        inFlight[key]=mutableListOf(view to tag)
        val current=generation
        tasks.add(worker.submit {
            val bitmap=runCatching {
                val loaded=if(url.startsWith("download:"))MusicStorage(view.context.applicationContext).cover(url)?.takeIf {it.length()<=1024*1024}?.let {BitmapFactory.decodeFile(it.path)} else OnlineArtwork.load(url,target)
                loaded?.let {if(maxOf(it.width,it.height)>target)Bitmap.createScaledBitmap(it,(it.width*target/maxOf(it.width,it.height)).coerceAtLeast(1),(it.height*target/maxOf(it.width,it.height)).coerceAtLeast(1),true) else it}
            }.getOrNull()
            bitmap?.prepareToDraw()
            main.post {if(current==generation){if(bitmap!=null)cache.put(key,bitmap);inFlight.remove(key)?.forEach {(image,binding)->if(image.tag==binding){if(bitmap!=null)bind(image,binding,bitmap)else image.tag=null}}}}
        });tasks.removeAll {it.isDone}
    }
    fun cancel(){generation++;tasks.forEach {it.cancel(true)};tasks.clear();waiting.clear();inFlight.clear();flinging=false}
    fun close(){cancel();worker.shutdownNow();main.removeCallbacksAndMessages(null)}
}


