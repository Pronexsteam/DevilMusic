// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof
import java.io.File
import com.grack.nanojson.*

/** Explicit likes, separate from the bounded recommendation profile. */
internal class FavoritesStore(private val root:File){
    companion object {private val lock=Any()}
    private val file get()=File(root,"favorites.json")
    private fun read():JsonObject {if(!file.exists())return JsonObject();check(file.length()<=32*1024*1024);return JsonParser.`object`().from(file.readText())}
    private fun local(o:JsonObject)=o.getArray("tracks").filterIsInstance<JsonObject>().map {SavedTrack(it.getString("id"),it.getString("title"),it.getString("artist",""),it.getString("artwork","").takeIf(NewPipeCatalog::validArtwork))}
    private fun imported()=DesktopLibraryImport(root).collections().firstOrNull {it.id=="desktop:favorites"}?.tracks.orEmpty()
    fun all():List<SavedTrack> = synchronized(lock){val o=read();val hidden=o.getArray("hidden").filterIsInstance<String>().toSet();(local(o)+imported()).distinctBy {tasteId(it.id)}.filter {tasteId(it.id) !in hidden}}
    fun toggle(track:SavedTrack):Boolean=synchronized(lock){
        require(MusicStateStore(root).validId(track.id));val o=read();val id=tasteId(track.id)
        val adding=all().none {tasteId(it.id)==id};val hidden=o.getArray("hidden").filterIsInstance<String>().toMutableSet()
        if(adding)hidden.remove(id) else hidden.add(id)
        val tracks=(if(adding)listOf(track)+local(o) else local(o).filter {tasteId(it.id)!=id}).distinctBy {tasteId(it.id)}
        val next=JsonObject().apply {put("version",1);put("hidden",JsonArray(hidden));put("tracks",JsonArray(tracks.map {t->JsonObject().apply {put("id",t.id);put("title",t.title.take(512));put("artist",t.artist.take(512));t.artwork?.takeIf(NewPipeCatalog::validArtwork)?.let {put("artwork",it)}}}))}
        val bytes=JsonWriter.string(next).toByteArray();check(bytes.size<=32*1024*1024){"Favorites file is too large"};atomicWrite(file,bytes);adding
    }
}
