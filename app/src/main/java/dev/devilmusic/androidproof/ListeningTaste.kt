// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import com.grack.nanojson.*
import java.io.File
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

internal enum class TasteSignal { HEARD, COMPLETED, EARLY_SKIP }
internal data class TasteEntry(val track:SavedTrack,val good:Double=0.0,val skips:Double=0.0,val updated:Long=0,val played:Long=0,val feedback:Int=0)
internal fun tasteId(id:String)=if(id.startsWith("download:"))"youtube:"+id.removePrefix("download:") else id
internal fun artistKey(name:String)=name.trim().lowercase(Locale.ROOT).removeSuffix(" - topic")

/** Small derived profile, separate from the user's library, queue and settings. No audio/URLs are saved. */
internal class ListeningTaste(private val root:File) {
    companion object {private val lock=Any();const val DAY=86_400_000L}
    private val ids=MusicStateStore(root)
    fun read():List<TasteEntry> = synchronized(lock) {runCatching {
        val file=File(root,"listening-taste.json")
        if(!file.isFile || file.length()>4*1024*1024)return@synchronized emptyList()
        JsonParser.`object`().from(file.readText()).getArray("tracks").filterIsInstance<JsonObject>().take(600).mapNotNull {o->
            val id=tasteId(o.getString("id",""));if(!ids.validId(id))return@mapNotNull null
            val track=SavedTrack(id,o.getString("title","").take(512),o.getString("artist","").take(512),o.getString("artwork","").takeIf(NewPipeCatalog::validArtwork))
            fun amount(key:String)=o.getDouble(key,0.0).takeIf {it.isFinite()}?.coerceIn(0.0,100.0) ?: 0.0
            TasteEntry(track,amount("good"),amount("skips"),o.getLong("updated",0).coerceAtLeast(0),o.getLong("played",0).coerceAtLeast(0),o.getInt("feedback",0).coerceIn(-1,1))
        }.distinctBy {it.track.id}
    }.getOrDefault(emptyList())}
    fun record(track:SavedTrack,signal:TasteSignal,now:Long)=update(track,now) {old->
        old.copy(good=(old.good+when(signal){TasteSignal.HEARD->1.0;TasteSignal.COMPLETED->.6;else->0.0}).coerceAtMost(100.0),
            skips=(old.skips+if(signal==TasteSignal.EARLY_SKIP)1.0 else 0.0).coerceAtMost(100.0),played=now)
    }
    fun feedback(track:SavedTrack,value:Int,now:Long)=update(track,now){it.copy(feedback=value.coerceIn(-1,1))}
    private fun update(track:SavedTrack,now:Long,change:(TasteEntry)->TasteEntry)=synchronized(lock) {
        val id=tasteId(track.id);if(!ids.validId(id))return@synchronized
        val all=read();val prior=all.firstOrNull {it.track.id==id} ?: TasteEntry(track.copy(id=id))
        val decay=exp(-(now-prior.updated).coerceAtLeast(0)/(30.0*DAY))
        val next=change(prior.copy(track=track.copy(id=id),good=prior.good*decay,skips=prior.skips*decay,updated=now))
        val items=(listOf(next)+all.filter {it.track.id!=id}).sortedByDescending {it.updated}.take(600)
        val json=JsonObject().apply {put("tracks",JsonArray(items.map {e->JsonObject().apply {
            put("id",e.track.id);put("title",e.track.title.take(512));put("artist",e.track.artist.take(512));e.track.artwork?.takeIf(NewPipeCatalog::validArtwork)?.let {put("artwork",it)}
            put("good",e.good);put("skips",e.skips);put("updated",e.updated);put("played",e.played);put("feedback",e.feedback)
        }}))}
        atomicWrite(File(root,"listening-taste.json"),JsonWriter.string(json).toByteArray())
    }
    fun reset()=synchronized(lock){atomicWrite(File(root,"listening-taste.json"),"{\"tracks\":[]}".toByteArray())}
}

/** Monotonic time actually spent playing; seeking, buffering and errors never become listening time. */
internal class ListeningMeter(private val emit:(SavedTrack,TasteSignal,Long)->Unit) {
    private var track:SavedTrack?=null
    private var tick=0L;private var playing=false;private var heard=0L;private var duration=0L
    private var counted=false;private var failed=false
    private fun accrue(now:Long){if(playing)heard+=(now-tick).coerceIn(0,60_000);tick=now}
    fun sample(item:SavedTrack?,isPlaying:Boolean,length:Long,now:Long,wall:Long,error:Boolean=false) {
        if(item?.id!=track?.id){finish(now,wall,false);track=item;tick=now}
        accrue(now);track=item;playing=isPlaying;duration=length.coerceAtLeast(0);failed=failed||error
        val threshold=if(duration>=10_000)minOf(30_000,duration/2) else 30_000
        if(!counted && !failed && heard>=threshold)track?.let {emit(it,TasteSignal.HEARD,wall);counted=true}
    }
    fun finish(now:Long,wall:Long,manual:Boolean) {
        accrue(now)
        track?.let {
            val threshold=if(duration>=10_000)minOf(30_000,duration/2) else 30_000
            if(!failed && !counted && heard>=threshold){emit(it,TasteSignal.HEARD,wall);counted=true}
            if(!failed && duration>=10_000 && heard>=duration*.75)emit(it,TasteSignal.COMPLETED,wall)
            else if(manual && !failed && !counted && heard in 2_000..20_000)emit(it,TasteSignal.EARLY_SKIP,wall)
        }
        track=null;playing=false;heard=0;duration=0;counted=false;failed=false;tick=now
    }
}

/** Artist affinity with ageing, explicit feedback, discovery space and an artist repetition limit. */
internal object HomeRanking {
    fun shelves(profile:List<TasteEntry>,candidates:List<MusicCard>,now:Long,rotation:Long,current:String?,shown:Map<String,Long> = emptyMap()):List<MusicShelf> {
        val selected=personalized(profile,candidates,now,rotation,current,shown).flatMap {it.items}
        val known=profile.filter {it.good>0 || it.feedback>0}.map {tasteId(it.track.id)}.toSet()
        val lead=selected.take(5)
        val rest=selected.drop(5)
        return listOf(
            MusicShelf("For your listening",lead),
            MusicShelf("More from your artists",rest.filter {"youtube:${it.id}" !in known}),
            MusicShelf("Back in rotation",rest.filter {"youtube:${it.id}" in known})
        ).filter {it.items.isNotEmpty()}
    }
    fun weight(e:TasteEntry,now:Long):Double {
        val decay=exp(-(now-e.updated).coerceAtLeast(0)/(30.0*ListeningTaste.DAY))
        return (ln(1+e.good)*2-e.skips*.65)*decay+e.feedback*3.0
    }
    fun seeds(profile:List<TasteEntry>,now:Long,rotation:Long):List<String> {
        val artists=profile.filter {it.feedback>=0 && it.track.artist.isNotBlank()}.groupBy {artistKey(it.track.artist)}
            .mapNotNull {(_,entries)->val w=entries.sumOf {weight(it,now)};if(w>.3)entries.maxBy {it.updated}.track.artist to w else null}
            .sortedByDescending {it.second}.take(8)
        if(artists.isEmpty())return emptyList()
        val start=Math.floorMod(rotation,artists.size.toLong()).toInt()
        return (artists.drop(start)+artists.take(start)).take(2).map {it.first.take(160)}
    }
    fun personalized(profile:List<TasteEntry>,candidates:List<MusicCard>,now:Long,rotation:Long,current:String?,shown:Map<String,Long> = emptyMap()):List<MusicShelf> {
        if(profile.none {weight(it,now)>.3 && it.feedback>=0})return emptyList()
        val byId=profile.associateBy {it.track.id}
        val groups=profile.filter {it.track.artist.isNotBlank()}.groupBy {artistKey(it.track.artist)}
        val affinity=groups.mapValues {(_,v)->(v.sumOf {weight(it,now)}/kotlin.math.sqrt(v.size.toDouble())).coerceIn(-4.0,6.0)}
        val session=groups.mapValues {(_,v)->v.filter {it.feedback>=0 && it.played>0 && now-it.played in 0..6*60*60_000L}.sumOf {weight(it,now).coerceAtLeast(0.0)*exp(-(now-it.played)/(2.0*60*60_000))}.coerceAtMost(3.0)}
        val known=profile.filter {weight(it,now)>.3 && it.feedback>=0 && it.track.id.startsWith("youtube:")}.map {e->MusicCard(e.track.id.removePrefix("youtube:"),"song",e.track.title,e.track.artist,e.track.artwork)}
        val eligible=(candidates+known).filter {it.kind=="song" && YouTubeInput.parse(it.id)==it.id}
            .distinctBy {it.id}.filter {card->val entry=byId["youtube:${card.id}"];entry?.feedback!=-1 && "youtube:${card.id}"!=current?.let(::tasteId) && !(entry!=null && entry.played>0 && now-entry.played in 0 until 30*60_000L)}
        fun score(card:MusicCard):Double {
            val e=byId["youtube:${card.id}"]
            val novelty=if(e==null)1.4 else 0.0
            val jitter=kotlin.random.Random(card.id.hashCode().toLong() xor (rotation*-7046029254386353131L)).nextDouble()*1.2
            val age=shown[card.id]?.let {now-it}
            val exposure=if(age!=null && age in 0..4*60*60_000L)4.0*exp(-age/(2.0*60*60_000)) else 0.0
            return (affinity[artistKey(card.subtitle)] ?: 0.0)*.45+(session[artistKey(card.subtitle)] ?: 0.0)*.35+(e?.let {weight(it,now)} ?: 0.0)*.4+novelty+jitter-exposure
        }
        val scores=eligible.associateWith(::score)
        val fresh=eligible.filter {byId["youtube:${it.id}"]?.good?.let {g->g>0}!=true}.sortedByDescending {scores[it]}.toMutableList()
        val familiar=eligible.filter {byId["youtube:${it.id}"]?.good?.let {g->g>0}==true}.sortedByDescending {scores[it]}.toMutableList()
        fun artist(card:MusicCard)=artistKey(card.subtitle).ifBlank {"unknown:${card.id}"}
        val selected=mutableListOf<MusicCard>();val counts=mutableMapOf<String,Int>()
        while(selected.size<16 && (fresh.isNotEmpty() || familiar.isNotEmpty())) {
            val preferred=if(selected.size%3==1)familiar else fresh
            fun pick(list:MutableList<MusicCard>):MusicCard? {
                val i=list.indexOfFirst {(counts[artist(it)] ?: 0)<3 && (selected.lastOrNull()?.let(::artist)!=artist(it))}
                return if(i>=0)list.removeAt(i) else null
            }
            val next=pick(preferred) ?: pick(if(preferred===fresh)familiar else fresh) ?: run {
                val source=if(preferred.any {(counts[artist(it)] ?: 0)<3})preferred else if(preferred===fresh)familiar else fresh
                val i=source.indexOfFirst {(counts[artist(it)] ?: 0)<3}
                if(i>=0)source.removeAt(i) else null
            } ?: break
            selected.add(next);val artist=artist(next);counts[artist]=(counts[artist] ?: 0)+1
        }
        return if(selected.isEmpty())emptyList() else listOf(MusicShelf("For your listening",selected))
    }
    fun rotatePublic(shelves:List<MusicShelf>,rotation:Long,excluded:Set<String> = emptySet()):List<MusicShelf> {
        if(shelves.isEmpty())return emptyList()
        val shift=Math.floorMod(rotation,shelves.size.toLong()).toInt();val seen=HashSet<String>()
        return (shelves.drop(shift)+shelves.take(shift)).map {s->
            val items=s.items.filter {it.kind!="song" || "youtube:${it.id}" !in excluded}.distinctBy {it.kind+it.id}
            val start=if(items.isEmpty())0 else Math.floorMod(rotation,items.size.toLong()).toInt()
            MusicShelf(s.title,(items.drop(start)+items.take(start)).asSequence().filter {seen.add(it.kind+":"+it.id)}.take(16).toList())
        }.filter {it.items.isNotEmpty()}
    }
}
