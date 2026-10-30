// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof
import com.grack.nanojson.*
import java.io.File

/** User playlists have their own atomic file. Editing membership never changes taste or audio files. */
internal class PlaylistStore(private val root:File) {
    companion object {private val lock=Any()}
    private val file get()=File(root,"playlists.json")
    fun all():List<ImportedCollection> = synchronized(lock) {
        if(!file.exists())return@synchronized emptyList()
        check(file.length()<=32*1024*1024){"Playlist file is too large"}
        val o=JsonParser.`object`().from(file.readText());check(o.getInt("version",0)==1)
        o.getArray("playlists").filterIsInstance<JsonObject>().map {p->
            ImportedCollection(p.getString("id"),p.getString("title"),p.getArray("tracks").filterIsInstance<JsonObject>().map {t->
                SavedTrack(t.getString("id"),t.getString("title"),t.getString("artist",""),t.getString("artwork","").takeIf(NewPipeCatalog::validArtwork))
            })
        }
    }
    private fun write(values:List<ImportedCollection>){
        val o=JsonObject().apply {put("version",1);put("playlists",JsonArray(values.map {p->JsonObject().apply {
            put("id",p.id);put("title",p.title);put("tracks",JsonArray(p.tracks.map {t->JsonObject().apply {put("id",t.id);put("title",t.title.take(512));put("artist",t.artist.take(512));t.artwork?.takeIf(NewPipeCatalog::validArtwork)?.let {put("artwork",it)}}}))
        }}))}
        val bytes=JsonWriter.string(o).toByteArray(Charsets.UTF_8);check(bytes.size<=32*1024*1024){"Playlist file is too large"};atomicWrite(file,bytes)
    }
    private fun title(value:String)=value.trim().also {require(it.isNotEmpty() && it.length<=160){"Use a name of 1–160 characters"}}
    fun create(name:String,tracks:List<SavedTrack> = emptyList()):ImportedCollection = synchronized(lock) {
        val p=ImportedCollection("android-"+java.util.UUID.randomUUID(),title(name),safe(tracks));write(all()+p);p
    }
    private fun safe(values:List<SavedTrack>):List<SavedTrack>{val state=MusicStateStore(root);require(values.all {state.validId(it.id)});return values.distinctBy {tasteId(it.id)}}
    fun rename(id:String,name:String)=edit(id){it.copy(title=title(name))}
    fun add(id:String,tracks:List<SavedTrack>)=edit(id){it.copy(tracks=safe(it.tracks+tracks))}
    fun remove(id:String,trackId:String)=edit(id){it.copy(tracks=it.tracks.filter {t->tasteId(t.id)!=tasteId(trackId)})}
    private fun edit(id:String,change:(ImportedCollection)->ImportedCollection):ImportedCollection = synchronized(lock) {
        val list=all();val p=list.firstOrNull {it.id==id} ?: error("Playlist not found");val next=change(p);write(list.map {if(it.id==id)next else it});next
    }
    fun delete(id:String)=synchronized(lock){val list=all();check(list.any {it.id==id});write(list.filter {it.id!=id})}
}
