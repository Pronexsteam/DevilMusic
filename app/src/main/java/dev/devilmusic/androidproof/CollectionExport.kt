package dev.devilmusic.androidproof
import com.grack.nanojson.*

/** Metadata only. Library membership is exported as a playlist, never invented likes or listening events. */
internal object CollectionExport {
    fun encode(library:List<SavedTrack>,collections:List<ImportedCollection>,deviceId:String):String {
        require(Regex("[a-f0-9-]{36}").matches(deviceId))
        fun tracks(values:List<SavedTrack>)=JsonArray(values.distinctBy {tasteId(it.id)}.mapNotNull {t->
            val id=tasteId(t.id).removePrefix("youtube:")
            if(!t.id.startsWith("youtube:") && !t.id.startsWith("download:") || YouTubeInput.parse(id)!=id)null else JsonObject().apply {
                put("kind","song");put("id",id);put("title",t.title);put("artists",JsonArray(listOf(t.artist)));put("image",t.artwork ?: "")
            }
        })
        val groups=(collections.filter {it.id!="desktop:favorites"}.map {it.copy(id=it.id.removePrefix("desktop:playlist:").removePrefix("desktop:"))}+ImportedCollection("android-$deviceId","Android library",library)).groupBy {it.id}
        val lists=groups.map { (id,group)->JsonObject().apply {put("id",id);put("name",group.first().title);put("items",tracks(group.flatMap {it.tracks}))}}
        return JsonWriter.string(JsonObject().apply {put("format","devil-music-collection");put("version",1);put("favorites",tracks(collections.firstOrNull {it.id=="desktop:favorites"}?.tracks.orEmpty()));put("playlists",JsonArray(lists))})
    }
}
