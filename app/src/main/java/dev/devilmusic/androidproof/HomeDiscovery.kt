// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import com.grack.nanojson.*
import java.io.File

/** Bounded public metadata cache. Its lifetime is independent of the user's library. */
internal class HomeDiscovery(private val root:File) {
    companion object {private val lock=Any();const val TTL=6*60*60_000L}
    private fun read():JsonObject=runCatching {val f=File(root,"home-discovery.json");if(!f.isFile || f.length()>2*1024*1024)JsonObject() else JsonParser.`object`().from(f.readText())}.getOrDefault(JsonObject())
    private fun write(o:JsonObject)=atomicWrite(File(root,"home-discovery.json"),JsonWriter.string(o).toByteArray())
    private fun encode(c:MusicCard)=JsonObject().apply {put("id",c.id);put("kind",c.kind);put("title",c.title.take(512));put("subtitle",c.subtitle.take(512));c.artwork?.takeIf(NewPipeCatalog::validArtwork)?.let {put("artwork",it)}}
    private fun cards(a:JsonArray?):List<MusicCard> = a?.filterIsInstance<JsonObject>()?.take(80)?.mapNotNull {o->
        val kind=o.getString("kind","");val id=o.getString("id","")
        if((kind=="song" && YouTubeInput.parse(id)==id) || (kind in setOf("album","artist","playlist") && MusicCatalogParser.validBrowse(id)))
            MusicCard(id,kind,o.getString("title","").take(512),o.getString("subtitle","").take(512),o.getString("artwork","").takeIf(NewPipeCatalog::validArtwork)) else null
    }.orEmpty()
    fun public():Pair<Long,List<MusicShelf>> = synchronized(lock){val o=read();o.getLong("at",0) to o.getArray("shelves").filterIsInstance<JsonObject>().take(8).map {s->MusicShelf(s.getString("title","Music"),cards(s.getArray("items")))}}
    fun savePublic(shelves:List<MusicShelf>,now:Long)=synchronized(lock){val o=read();o["at"]=now;o["shelves"]=JsonArray(shelves.take(8).map {s->JsonObject().apply {put("title",s.title.take(512));put("items",JsonArray(s.items.take(40).map(::encode)))}});write(o)}
    fun candidates():List<MusicCard> = synchronized(lock){read().getArray("artists").filterIsInstance<JsonObject>().take(8).flatMap {cards(it.getArray("items"))}.distinctBy {it.id}}
    /** Only searches seeded by this listener; never reuse a regional/public homepage. */
    fun candidatesFor(artists:Set<String>):List<MusicCard> = synchronized(lock){
        read().getArray("artists").filterIsInstance<JsonObject>().take(8)
            .filter {it.getString("artist","") in artists}
            .flatMap {entry->val seed=entry.getString("artist","");cards(entry.getArray("items")).filter {card->
                val credited=artistKey(card.subtitle)
                credited==seed || credited.split(Regex("\\s*(?:,|&| feat\\.? | ft\\.? | featuring | x )\\s*")).any {artistKey(it)==seed}
            }}.distinctBy {it.id}
    }
    fun due(artist:String,now:Long):Boolean=synchronized(lock){val e=read().getArray("artists").filterIsInstance<JsonObject>().firstOrNull {it.getString("artist","")==artistKey(artist)};e==null || now-e.getLong("at",0) !in 0 until TTL}
    fun saveArtist(artist:String,items:List<MusicCard>,now:Long)=synchronized(lock) {
        val o=read();val key=artistKey(artist)
        val next=JsonObject().apply {put("artist",key);put("at",now);put("items",JsonArray(items.filter {it.kind=="song"}.take(32).map(::encode)))}
        o["artists"]=JsonArray((listOf(next)+o.getArray("artists").filterIsInstance<JsonObject>().filter {it.getString("artist","")!=key}).take(8));write(o)
    }
}
