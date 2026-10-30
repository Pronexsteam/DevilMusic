package dev.devilmusic.androidproof
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.widget.*
import java.util.concurrent.ExecutorService

internal class PlaylistUi(private val activity:Activity,private val worker:ExecutorService,
    private val alive:()->Boolean,private val queue:()->List<SavedTrack>,private val open:(ImportedCollection)->Unit,private val play:(ImportedCollection)->Unit) {
    private val store=PlaylistStore(java.io.File(activity.filesDir,"music-state"))
    var onChanged:()->Unit={}
    private fun <T> task(work:()->T,done:(T)->Unit){worker.submit {val result=runCatching(work);activity.runOnUiThread {if(alive())result.fold({done(it);onChanged()}){Toast.makeText(activity,it.message ?: "Could not save playlist",Toast.LENGTH_SHORT).show()}}}}
    fun create(){name("New playlist",""){value->task({store.create(value)}){open(it)}}}
    fun saveQueue(){val snapshot=queue();if(snapshot.isEmpty())return;name("Save queue",""){value->task({store.create(value,snapshot)}){open(it)}}}
    fun edit(id:String){task({store.all().first {it.id==id}}){details(it)}}
    fun manager(){task(store::all){values->MusicActionSheet.show(activity,"Your playlists",groups=listOf(
        "Create" to listOf(MusicAction("New playlist"){name("New playlist",""){value->task({store.create(value)}){details(it)}}},MusicAction("Save queue as playlist","Keeps this order without changing playback",queue().isNotEmpty()){val snapshot=queue();name("Save queue",""){value->task({store.create(value,snapshot)}){details(it)}}}),
        "Playlists" to values.map {p->MusicAction(p.title,"${p.tracks.size} tracks"){details(p)}}
    ))}}
    fun add(track:SavedTrack){task(store::all){values->MusicActionSheet.show(activity,"Add to playlist",track.title,listOf(
        "Create" to listOf(MusicAction("New playlist"){name("New playlist",""){value->task({store.create(value,listOf(track))}){Toast.makeText(activity,"Added to ${it.title}",Toast.LENGTH_SHORT).show()}}}),
        "Playlists" to values.map {p->MusicAction(p.title,"${p.tracks.size} tracks"){task({store.add(p.id,listOf(track))}){Toast.makeText(activity,"Added to ${it.title}",Toast.LENGTH_SHORT).show()}}}
    ))}}
    private fun details(p:ImportedCollection){MusicActionSheet.show(activity,p.title,"${p.tracks.size} tracks",listOf(
        "Listen" to listOf(MusicAction("Open playlist",if(p.tracks.isEmpty())"Add songs from their ⋯ menu" else "Choose a song to play",p.tracks.isNotEmpty()){open(p)},MusicAction("Play playlist","Replaces the current queue with these tracks",p.tracks.isNotEmpty()){play(p)}),
        "Edit" to listOf(MusicAction("Rename playlist"){name("Rename playlist",p.title){value->task({store.rename(p.id,value)}){details(it)}}},MusicAction("Remove tracks","Only changes this playlist",p.tracks.isNotEmpty()){removeTracks(p)},MusicAction("Delete playlist","Songs, downloads and listening history stay saved"){
            AlertDialog.Builder(activity).setTitle("Delete ${p.title}?").setMessage("This removes the playlist only. Your music files and library stay saved.").setNegativeButton("Cancel",null).setPositiveButton("Delete"){_,_->task({store.delete(p.id)}){manager()}}.show()
        })
    ))}
    private fun name(title:String,initial:String,save:(String)->Unit){
        fun dp(v:Int)=MusicStyle.dp(activity,v)
        val dialog=android.app.Dialog(activity)
        val body=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(24),dp(24),dp(20));background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,intArrayOf(0xff252d36.toInt(),0xff121820.toInt())).apply {cornerRadius=dp(26).toFloat();setStroke(dp(1),0xff39444f.toInt())}}
        val head=LinearLayout(activity).apply {gravity=android.view.Gravity.CENTER_VERTICAL}
        head.addView(MusicGlyph(activity,"collection",MusicStyle.accent),LinearLayout.LayoutParams(dp(42),dp(42)))
        head.addView(MusicStyle.text(activity,23f,bold=true,lines=2).apply {text=title},LinearLayout.LayoutParams(0,-2,1f).apply {leftMargin=dp(10)})
        body.addView(head)
        body.addView(MusicStyle.text(activity,13f,MusicStyle.muted,lines=2).apply {text="A place for the songs that belong together."},LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(12);bottomMargin=dp(22)})
        val field=EditText(activity).apply {setText(initial);setSelection(text.length);hint="Playlist name";contentDescription="Playlist name";setSingleLine(true);imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE;filters=arrayOf(android.text.InputFilter.LengthFilter(160));typeface=MusicStyle.face(activity);textSize=18f;setTextColor(MusicStyle.ink);setHintTextColor(MusicStyle.muted);background=MusicStyle.box(activity,0xff10161d.toInt(),16,true);setPadding(dp(16),dp(12),dp(16),dp(12))}
        body.addView(field,LinearLayout.LayoutParams(-1,dp(60)))
        val helper=MusicStyle.text(activity,12f,MusicStyle.muted,lines=2).apply {text="You can rename it any time."}
        body.addView(helper,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(10);bottomMargin=dp(22)})
        fun button(label:String,primary:Boolean,click:()->Unit)=MusicStyle.text(activity,14f,if(primary)MusicStyle.background else MusicStyle.ink,true).apply {text=label;gravity=android.view.Gravity.CENTER;background=MusicStyle.touch(activity,if(primary)MusicStyle.accent else 0xff252c34.toInt(),18);isClickable=true;isFocusable=true;setOnClickListener {click()}}
        fun commit(){val value=field.text.toString().trim();if(value.isBlank()){helper.text="Give your playlist a name.";helper.setTextColor(0xffffb8aa.toInt());field.requestFocus()}else {dialog.dismiss();save(value)}}
        val actions=LinearLayout(activity)
        actions.addView(button("Cancel",false){dialog.dismiss()},LinearLayout.LayoutParams(0,dp(50),1f).apply {rightMargin=dp(10)})
        actions.addView(button(if(initial.isEmpty())"Create playlist" else "Save changes",true,::commit),LinearLayout.LayoutParams(0,dp(50),1.4f))
        body.addView(actions)
        field.setOnEditorActionListener {_,id,_->if(id==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){commit();true}else false}
        dialog.setContentView(body);dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show();dialog.window?.setLayout(minOf(activity.resources.displayMetrics.widthPixels-dp(32),dp(460)),-2)
        field.requestFocus();field.post {if(dialog.isShowing)(activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(field,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)}
    }
    private fun removeTracks(p:ImportedCollection){
        // ListView recycles rows: a large playlist never creates thousands of views.
        val list=ListView(activity);val adapter=object:BaseAdapter(){override fun getCount()=p.tracks.size;override fun getItem(i:Int)=p.tracks[i];override fun getItemId(i:Int)=i.toLong()
            override fun getView(i:Int,recycled:android.view.View?,parent:android.view.ViewGroup):android.view.View=(recycled as? TextView ?: MusicStyle.text(activity,15f,lines=2)).apply {text="${p.tracks[i].title}\n${p.tracks[i].artist}";setPadding(24,18,24,18);minimumHeight=MusicStyle.dp(activity,54)}}
        list.adapter=adapter
        val dialog=AlertDialog.Builder(activity).setTitle("Remove a track").setView(list).setNegativeButton("Done",null).create()
        list.setOnItemClickListener {_,_,index,_->dialog.dismiss();task({store.remove(p.id,p.tracks[index].id)}){details(it)}};dialog.show()
    }
}
