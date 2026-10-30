// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof
import com.grack.nanojson.*
import java.io.File

internal data class HomeArrangement(val order:List<String> = emptyList(),val hidden:Set<String> = emptySet()) {
    fun arrange(items:List<MusicShelf>):List<MusicShelf> = items.filter {it.title !in hidden}.sortedBy {order.indexOf(it.title).takeIf {i->i>=0} ?: Int.MAX_VALUE}
}
/** Separate UI preferences; never rewrites library, taste or queue. */
internal class UiPreferences(private val root:File) {
    private val lock=Any()
    private fun read():JsonObject=runCatching {val f=File(root,"ui-layout.json");if(!f.isFile || f.length()>65536)JsonObject() else JsonParser.`object`().from(f.readText())}.getOrDefault(JsonObject())
    private fun strings(o:JsonObject,key:String)=o.getArray(key).filterIsInstance<String>().map {it.take(200)}.distinct().take(32)
    private fun save(o:JsonObject){root.mkdirs();atomicWrite(File(root,"ui-layout.json"),JsonWriter.string(o).toByteArray())}
    fun history():List<String> = synchronized(lock){strings(read(),"searches").take(12)}
    fun remember(query:String)=synchronized(lock){val q=query.trim().take(200);if(q.length<2)return@synchronized;val o=read();o["searches"]=JsonArray((listOf(q)+strings(o,"searches").filterNot {it.equals(q,true)}).take(12));save(o)}
    fun clearHistory()=synchronized(lock){val o=read();o["searches"]=JsonArray();save(o)}
    fun home():HomeArrangement=synchronized(lock){val o=read();HomeArrangement(strings(o,"order"),strings(o,"hidden").toSet())}
    fun home(value:HomeArrangement)=synchronized(lock){val o=read();o["order"]=JsonArray(value.order.distinct().take(32));o["hidden"]=JsonArray(value.hidden.take(32));save(o)}
}

internal object QueueMove {
    /** Resolve IDs at drop time so playback changes cannot make a stale index move another song. */
    fun indices(ids:List<String>,source:String,target:String):Pair<Int,Int>? {
        val from=ids.indexOf(source);val to=ids.indexOf(target)
        return if(from>=0 && to>=0 && from!=to)from to to else null
    }
}
