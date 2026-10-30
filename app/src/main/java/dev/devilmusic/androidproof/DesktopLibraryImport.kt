// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import com.grack.nanojson.*
import java.io.File
import java.io.InputStream

internal data class ImportedCollection(val id:String,val title:String,val tracks:List<SavedTrack>)
internal data class DesktopImportPreview(val source:String,val collections:List<ImportedCollection>,val skipped:Int) {
    val tracks:List<SavedTrack> get()=collections.flatMap {it.tracks}.distinctBy {it.id}
}

/** Additive, reviewable library transfer. Never import desktop taste, volume, local paths or credentials. */
internal class DesktopLibraryImport(private val root:File) {
    companion object {
        private val lock=Any()
        fun read(input:InputStream):String {
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=64*1024*1024){"Profile is larger than 64 MB; nothing was imported"};out.write(buffer,0,n)}
            return out.toString("UTF-8")
        }
        fun preview(source:String):DesktopImportPreview {
            val o=JsonParser.`object`().from(source)
            require(o.getString("format","")=="devil-music-profile" && o.getInt("version",0) in 1..3){"Choose a Devil Music profile exported on your PC"}
            val saved=o.getObject("saved") ?: error("Missing library")
            var skipped=0
            fun tracks(values:JsonArray?):List<SavedTrack> {
                require(values!=null){"Missing track list"}
                return values.mapNotNull {v->
                    val t=v as? JsonObject ?: error("Invalid track")
                    val id=t.getString("id","");val title=t.getString("title","")
                    require(t.getString("kind","")=="song" && title.isNotBlank()){ "Invalid track metadata" }
                    if(YouTubeInput.parse(id)!=id){skipped++;null}
                    else SavedTrack("youtube:$id",title.take(512),t.getArray("artists")?.filterIsInstance<String>()?.joinToString(", ")?.take(512).orEmpty(),t.getString("image","").takeIf(NewPipeCatalog::validArtwork))
                }.distinctBy {it.id}
            }
            val collections=mutableListOf(ImportedCollection("desktop:favorites","Liked songs · PC",tracks(saved.getArray("favorites"))))
            val seen=hashSetOf<String>()
            for(v in saved.getArray("playlists") ?: JsonArray()){
                val p=v as? JsonObject ?: error("Invalid playlist");val id=p.getString("id","");val name=p.getString("name","")
                require(Regex("[-\\w]{1,64}").matches(id) && seen.add(id) && name.isNotBlank()){ "Invalid playlist" }
                collections.add(ImportedCollection("desktop:playlist:$id",name.take(160),tracks(p.getArray("items"))))
            }
            return DesktopImportPreview(source,collections,skipped)
        }
    }
    private val file get()=File(root,"desktop-library.json")
    fun collections():List<ImportedCollection> = synchronized(lock) {
        if(!file.exists())return@synchronized emptyList()
        val o=JsonParser.`object`().from(file.readText())
        require(o.getInt("version",0)==1)
        o.getArray("collections").filterIsInstance<JsonObject>().map {c->ImportedCollection(c.getString("id"),c.getString("title"),c.getArray("tracks").filterIsInstance<JsonObject>().map {t->SavedTrack(t.getString("id"),t.getString("title"),t.getString("artist",""),t.getString("artwork","").takeIf(NewPipeCatalog::validArtwork))})}
    }
    fun apply(preview:DesktopImportPreview):Int = synchronized(lock) {
        // Re-validate before committing; a malformed import cannot partially replace the collection.
        val incoming=DesktopLibraryImport.preview(preview.source);val previous=collections();val merged=previous.associateBy {it.id}.toMutableMap()
        val before=previous.flatMap {it.tracks}.map {it.id}.toSet()
        for(c in incoming.collections){val existing=merged[c.id];merged[c.id]=c.copy(tracks=(existing?.tracks.orEmpty()+c.tracks).distinctBy {it.id})}
        val value=JsonObject().apply {
            put("version",1)
            put("collections",JsonArray(merged.values.map {c->JsonObject().apply {put("id",c.id);put("title",c.title);put("tracks",JsonArray(c.tracks.map {t->JsonObject().apply {put("id",t.id);put("title",t.title);put("artist",t.artist);put("artwork",t.artwork)}}))}}))
        }
        root.mkdirs()
        if(file.isFile)atomicWrite(File(root,"desktop-library.previous.json"),file.readBytes())
        atomicWrite(file,JsonWriter.string(value).toByteArray(Charsets.UTF_8))
        incoming.tracks.count {it.id !in before}
    }
}
