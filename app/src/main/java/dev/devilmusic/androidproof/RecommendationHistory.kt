// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof
import com.grack.nanojson.*
import java.io.File

/** Recently delivered recommendations are an exposure cooldown, never a like/skip signal. */
internal class RecommendationHistory(private val root:File) {
    companion object {private val lock=Any();const val LIMIT=512;const val TTL=7*ListeningTaste.DAY}
    fun read(now:Long):Map<String,Long> = synchronized(lock){runCatching {
        val file=File(root,"recommendation-history.json")
        if(!file.isFile || file.length()>128*1024)return@synchronized emptyMap()
        JsonParser.`object`().from(file.readText()).getArray("shown").filterIsInstance<JsonObject>().take(LIMIT).mapNotNull {
            val id=it.getString("id","");val at=it.getLong("at",0)
            if(YouTubeInput.parse(id)==id && now-at in 0..TTL)id to at else null
        }.toMap()
    }.getOrDefault(emptyMap())}
    fun record(cards:List<MusicCard>,now:Long)=synchronized(lock){
        val values=read(now).toMutableMap()
        cards.filter {it.kind=="song" && YouTubeInput.parse(it.id)==it.id}.forEach {values[it.id]=now}
        val rows=values.entries.sortedByDescending {it.value}.take(LIMIT).map {JsonObject().apply {put("id",it.key);put("at",it.value)}}
        atomicWrite(File(root,"recommendation-history.json"),JsonWriter.string(JsonObject().apply {put("shown",JsonArray(rows))}).toByteArray())
    }
    fun reset()=synchronized(lock){atomicWrite(File(root,"recommendation-history.json"),"{\"shown\":[]}".toByteArray())}
}
