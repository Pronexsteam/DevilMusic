// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import com.grack.nanojson.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class SavedTrack(val id:String,val title:String,val artist:String,val artwork:String?)
internal data class SavedQueue(val tracks:List<SavedTrack>,val currentId:String?,val position:Long,val shuffle:Boolean,val repeat:Int,val speed:Float,val shuffleOrder:List<String> = emptyList())

/** Only stable IDs/public metadata. Source URLs, headers and account state are never serialized. */
internal class MusicStateStore(private val root:File) {
    private var lastStructure:SavedQueue?=null
    private var revision=""
    companion object {private val lock=Any()}
    private fun encodeTrack(t:SavedTrack)=JsonObject().apply {put("id",t.id);put("title",t.title.take(512));put("artist",t.artist.take(512));t.artwork?.takeIf(NewPipeCatalog::validArtwork)?.let {put("artwork",it)}}
    private fun decodeTrack(o:JsonObject):SavedTrack? {
        val id=o.getString("id","")
        if(!validId(id))return null
        return SavedTrack(id,o.getString("title","Audio").take(512),o.getString("artist","").take(512),o.getString("artwork","").takeIf(NewPipeCatalog::validArtwork))
    }
    fun validId(id:String):Boolean=when {
        id.startsWith("youtube:")->YouTubeInput.parse(id.removePrefix("youtube:"))==id.removePrefix("youtube:")
        id.startsWith("download:")->YouTubeInput.parse(id.removePrefix("download:"))==id.removePrefix("download:")
        id.length<=8192 -> runCatching {val u=java.net.URI(id);u.scheme=="content" && !u.authority.isNullOrBlank()}.getOrDefault(false)
        else->false
    }
    private fun read(name:String):JsonObject? {
        val file=File(root,name);if(!file.exists())return null
        check(file.isFile && file.length()<=32*1024*1024){"Saved music state cannot be read"}
        return file.reader(Charsets.UTF_8).use {JsonParser.`object`().from(it)}
    }
    private fun tracks(root:JsonObject?,key:String)=root?.getArray(key)?.filterIsInstance<JsonObject>()?.mapNotNull(::decodeTrack)?.distinctBy {it.id} ?: emptyList()
    fun library():List<SavedTrack> = synchronized(lock){tracks(read("library.json"),"tracks")}
    fun addToLibrary(track:SavedTrack)=synchronized(lock){
        require(validId(track.id))
        val next=(library()+track).associateBy {it.id}.values
        write("library.json",JsonObject().apply {put("tracks",JsonArray(next.map(::encodeTrack)))})
    }
    /** Real playback history is separate from queue/library membership and never inferred from either. */
    fun recent():List<SavedTrack> = synchronized(lock){tracks(read("recent.json"),"tracks").take(100)}
    fun recordPlayed(track:SavedTrack)=synchronized(lock) {
        if(!validId(track.id))return@synchronized
        val prior=recent()
        if(prior.firstOrNull()==track)return@synchronized
        write("recent.json",JsonObject().apply {put("tracks",JsonArray((listOf(track)+prior.filter {it.id!=track.id}).take(100).map(::encodeTrack)))})
    }
    fun pinnedAlbums():List<MusicCard> = synchronized(lock) {
        read("pins.json")?.getArray("albums")?.filterIsInstance<JsonObject>()?.mapNotNull {o->
            val id=o.getString("id","")
            val kind=o.getString("kind","album")
            if(kind !in setOf("album","playlist") || !MusicCatalogParser.validBrowse(id) || id.startsWith("UC"))null
            else MusicCard(id,kind,o.getString("title","Collection").take(512),o.getString("subtitle","").take(512),o.getString("artwork","").takeIf(NewPipeCatalog::validArtwork))
        }?.distinctBy {it.id}?.take(24) ?: emptyList()
    }
    fun toggleAlbum(card:MusicCard):Boolean=synchronized(lock) {
        require(card.kind in setOf("album","playlist") && MusicCatalogParser.validBrowse(card.id) && !card.id.startsWith("UC"))
        val prior=pinnedAlbums();val adding=prior.none {it.id==card.id}
        val next=if(adding)(listOf(card)+prior).take(24) else prior.filter {it.id!=card.id}
        write("pins.json",JsonObject().apply {put("albums",JsonArray(next.map {c->JsonObject().apply {
            put("id",c.id);put("kind",c.kind);put("title",c.title.take(512));put("subtitle",c.subtitle.take(512));c.artwork?.takeIf(NewPipeCatalog::validArtwork)?.let {put("artwork",it)}
        }}))})
        adding
    }
    fun queue():SavedQueue?=synchronized(lock) {
        val o=try {read("queue.json")}catch(e:Exception){throw IllegalStateException("Saved queue could not be read",e)} ?: run {
            // A read/parse failure is not an intentionally empty queue. Keep its on-disk data.
            check(!File(root,"queue.json").exists()){ "Saved queue could not be read" }
            return null
        }
        val cursor=read("playback-position.json")?.takeIf {o.getString("revision","").isNotBlank() && it.getString("revision","")==o.getString("revision","")} ?: o
        SavedQueue(tracks(o,"tracks"),cursor.getString("currentId","").takeIf(::validId),cursor.getLong("position",0).coerceIn(0,86_400_000),o.getBoolean("shuffle",false),o.getInt("repeat",0).coerceIn(0,2),o.getDouble("speed",1.0).toFloat().takeIf {it.isFinite()}?.coerceIn(.5f,2f) ?: 1f,o.getArray("shuffleOrder")?.filterIsInstance<String>()?.filter(::validId)?.distinct().orEmpty())
    }
    fun save(queue:SavedQueue)=synchronized(lock) {
        val structure=queue.copy(currentId=null,position=0)
        if(lastStructure!=structure){
            val safe=queue.tracks.filter {validId(it.id)}.distinctBy {it.id}
            val before=library();val library=(before+safe).associateBy {it.id}.values.toList()
            if(library!=before)write("library.json",JsonObject().apply {put("tracks",JsonArray(library.map(::encodeTrack)))})
            val valid=safe.map {it.id}.toHashSet();val order=queue.shuffleOrder.filter {it in valid}.distinct()
            val nextRevision=java.util.UUID.randomUUID().toString()
            write("queue.json",JsonObject().apply {put("revision",nextRevision);put("tracks",JsonArray(safe.map(::encodeTrack)));put("currentId",queue.currentId);put("position",queue.position.coerceAtLeast(0));put("shuffle",queue.shuffle);put("repeat",queue.repeat);put("speed",queue.speed.toDouble());put("shuffleOrder",JsonArray(order))})
            revision=nextRevision;lastStructure=structure
        }
        // Frequent cursor checkpoints never rewrite the large library or queue metadata.
        write("playback-position.json",JsonObject().apply {put("revision",revision);put("currentId",queue.currentId);put("position",queue.position.coerceAtLeast(0))})
    }
    private fun write(name:String,value:JsonObject){root.mkdirs();atomicWrite(File(root,name),JsonWriter.string(value).toByteArray(Charsets.UTF_8))}
}

internal fun atomicWrite(file:File,bytes:ByteArray) {
    file.parentFile?.mkdirs();val part=File(file.parentFile,file.name+".part")
    FileOutputStream(part).use {it.write(bytes);it.fd.sync()}
    try {Files.move(part.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}
    catch(e:java.nio.file.AtomicMoveNotSupportedException){Files.move(part.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING)}
}
