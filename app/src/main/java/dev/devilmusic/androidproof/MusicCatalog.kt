// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import com.grack.nanojson.*
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.localization.*
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.net.URI

internal data class MusicCard(val id:String,val kind:String,val title:String,val subtitle:String,val artwork:String?,val params:String?=null) {
    fun track()=CatalogTrack(id,title,subtitle,artwork)
}
internal data class MusicShelf(val title:String,val items:List<MusicCard>)

/** Public Music metadata only. Source resolution/playback still uses the single existing engine. */
internal object MusicCatalog {
    internal fun <T> request(block:(SearchDownloader)->T):T=synchronized(NewPipeAccess.lock) {
        val downloader=SearchDownloader(music=true)
        try {
            NewPipe.init(downloader,Localization("en","GB"),ContentCountry("GB"))
            block(downloader).also {downloader.check()}
        }catch(e:Exception){throw OnlineSourceException(downloader.failure ?: "music_catalog_unavailable")}
    }
    fun browse(id:String="FEmusic_home"):List<MusicShelf> {
        require(id=="FEmusic_home" || MusicCatalogParser.validBrowse(id))
        return request { downloader ->
            val version=YoutubeParsingHelper.getYoutubeMusicClientVersion()
            val body=JsonWriter.string().`object`().`object`("context").`object`("client")
                .value("clientName","WEB_REMIX").value("clientVersion",version).value("hl","en").value("platform","DESKTOP")
                .end().end().value("browseId",id).end().done().toByteArray(Charsets.UTF_8)
            val response=downloader.postWithContentTypeJson("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false",YoutubeParsingHelper.getYoutubeMusicHeaders(),body)
            MusicCatalogParser.parse(JsonParser.`object`().from(response.responseBody()))
        }
    }
    fun search(query:String,kind:String):List<MusicShelf> {
        require(query.trim().length in 2..200 && kind in setOf("songs","albums","artists","playlists"))
        return request {
            val extractor=ServiceList.YouTube.getSearchExtractor(query.trim(),listOf("music_$kind"),"")
            extractor.fetchPage()
            val cards=extractor.initialPage.items.take(40).mapNotNull { item ->
                val art=item.thumbnails.lastOrNull()?.url?.takeIf(NewPipeCatalog::validArtwork)
                when(item) {
                    is StreamInfoItem -> YouTubeInput.parse(item.url)?.let {MusicCard(it,"song",item.name,item.uploaderName,art)}
                    is ChannelInfoItem -> URI(item.url).path.substringAfterLast('/').takeIf(MusicCatalogParser::validBrowse)?.let {MusicCard(it,"artist",item.name,"Artist",art)}
                    is PlaylistInfoItem -> URI(item.url).rawQuery.orEmpty().split('&').firstOrNull {it.startsWith("list=")}?.substringAfter('=')?.let {"VL$it"}?.takeIf(MusicCatalogParser::validBrowse)?.let {MusicCard(it,if(kind=="albums")"album" else "playlist",item.name,item.uploaderName,art)}
                    else -> null
                }
            }.distinctBy {it.kind+":"+it.id}
            listOf(MusicShelf("Results for “${query.trim()}”",cards))
        }
    }
}

/** Port of our desktop music card semantics, bounded and independent of UI/transport. */
internal object MusicCatalogParser {
    fun validBrowse(id:String)=id.length in 3..160 && Regex("[A-Za-z0-9_-]+").matches(id) && listOf("UC","VL","MPRE").any(id::startsWith)
    private fun text(value:Any?):String {
        val o=value as? JsonObject ?: return ""
        return o.getString("simpleText","").ifEmpty {o.getArray("runs").filterIsInstance<JsonObject>().joinToString(""){it.getString("text","")}}.take(500)
    }
    private fun find(value:Any?,key:String,depth:Int=0):Any? {
        if(depth>30)return null
        return when(value) {
            is JsonObject -> value[key] ?: value.entries.asSequence().filter {it.key !in setOf("menu","trackingParams")}.mapNotNull {find(it.value,key,depth+1)}.firstOrNull()
            is JsonArray -> value.asSequence().mapNotNull {find(it,key,depth+1)}.firstOrNull()
            else -> null
        }
    }
    private fun card(o:JsonObject):MusicCard? {
        val columns=o.getArray("flexColumns").filterIsInstance<JsonObject>().map {it.getObject("musicResponsiveListItemFlexColumnRenderer").getObject("text")}
        val titleValue=columns.firstOrNull() ?: o.getObject("title")
        val title=text(titleValue);if(title.isBlank())return null
        val nav=(o["navigationEndpoint"] ?: find(titleValue,"navigationEndpoint")) as? JsonObject ?: JsonObject()
        val page=find(nav,"pageType") as? String ?: ""
        if(page in setOf("MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE","MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE","MUSIC_PAGE_TYPE_USER_CHANNEL"))return null
        val browse=nav.getObject("browseEndpoint").getString("browseId","")
        val video=o.getObject("playlistItemData").getString("videoId","").ifEmpty {nav.getObject("watchEndpoint").getString("videoId","")}
            .ifEmpty {(find(o["overlay"],"videoId") ?: find(o["thumbnailOverlay"],"videoId")) as? String ?: ""}
        val kind=if(validBrowse(browse))when {browse.startsWith("UC")->"artist";browse.startsWith("MPRE")->"album";else->"playlist"} else "song"
        val id=if(kind=="song")video.takeIf {YouTubeInput.parse(it)==it} ?: return null else browse
        val subtitle=if(columns.isNotEmpty())columns.drop(1).joinToString(" · "){text(it)} else text(o["subtitle"])
        val art=(find(o["thumbnail"] ?: o["thumbnailRenderer"],"thumbnails") as? JsonArray)?.filterIsInstance<JsonObject>()?.map {it.getString("url","")}?.lastOrNull(NewPipeCatalog::validArtwork)
        return MusicCard(id,kind,title,subtitle.take(500),art)
    }
    private fun collect(value:Any?,items:MutableList<MusicCard>,depth:Int=0) {
        if(depth>30 || items.size>=1000)return
        when(value) {
            is JsonObject -> value.forEach {(key,v) ->
                if(key in setOf("musicTwoRowItemRenderer","musicResponsiveListItemRenderer","musicCardShelfRenderer")) {
                    (v as? JsonObject)?.let {card(it)?.let(items::add);if(key=="musicCardShelfRenderer")collect(it["contents"],items,depth+1)}
                } else if(key !in setOf("menu","header","trackingParams"))collect(v,items,depth+1)
            }
            is JsonArray -> value.forEach {collect(it,items,depth+1)}
        }
    }
    fun parse(root:JsonObject):List<MusicShelf> {
        val shelves=mutableListOf<MusicShelf>()
        fun walk(value:Any?,depth:Int) {
            if(depth>30 || shelves.size>=64)return
            when(value) {
                is JsonObject -> value.forEach {(key,v) ->
                    if(key in setOf("musicCarouselShelfRenderer","musicShelfRenderer","musicPlaylistShelfRenderer","musicShelfContinuation","musicPlaylistShelfContinuation")) {
                        val shelf=v as? JsonObject ?: return@forEach;val items=mutableListOf<MusicCard>();collect(shelf["contents"],items)
                        val more=find(shelf["header"],"moreContentButton") ?: shelf["bottomEndpoint"]
                        val endpoint=(find(more,"browseEndpoint") as? JsonObject)
                        val moreId=endpoint?.getString("browseId","")
                        if(moreId!=null && validBrowse(moreId))items.add(MusicCard(moreId,"collection","Show all · "+text(shelf["title"] ?: find(shelf["header"],"title")).ifBlank {"Music"},"Open complete collection",null,endpoint.getString("params","").takeIf {it.length in 1..16000}))
                        if(items.isNotEmpty())shelves.add(MusicShelf(text(shelf["title"] ?: find(shelf["header"],"title")).ifBlank {"Music"},items.distinctBy {it.kind+it.id}))
                    } else if(key !in setOf("menu","header","trackingParams"))walk(v,depth+1)
                }
                is JsonArray -> value.forEach {walk(it,depth+1)}
            }
        }
        walk(root,0)
        if(shelves.isEmpty()){val items=mutableListOf<MusicCard>();collect(root,items);if(items.isNotEmpty())shelves.add(MusicShelf("Music",items.distinctBy {it.kind+it.id}))}
        return shelves
    }
}
