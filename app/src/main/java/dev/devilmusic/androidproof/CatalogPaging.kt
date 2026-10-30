// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import com.grack.nanojson.*
import org.schabi.newpipe.extractor.*
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import java.net.URI

internal data class CatalogPage(val shelves:List<MusicShelf>,val next:(()->CatalogPage)?=null)
internal object CatalogPaging {
    fun merge(before:List<MusicShelf>,after:List<MusicShelf>):List<MusicShelf> {
        val seen=HashSet<String>();return (before+after).mapNotNull {s->s.items.filter {seen.add(it.kind+":"+it.id)}.takeIf {it.isNotEmpty()}?.let {MusicShelf(s.title,it)}}
    }
    fun tokens(root:Any?):List<String> {
        val found=linkedSetOf<String>()
        fun walk(v:Any?,depth:Int){if(depth>30 || found.size>=16)return;when(v){
            is JsonObject->v.forEach {(k,x)->if(k in setOf("nextContinuationData","continuationCommand")){val o=x as? JsonObject;val t=o?.getString(if(k=="continuationCommand")"token" else "continuation","");if(t!=null && t.length in 1..16000)found.add(t)}else if(k !in setOf("menu","trackingParams"))walk(x,depth+1)}
            is JsonArray->v.forEach {walk(it,depth+1)}
        }};walk(root,0);return found.toList()
    }
    fun browse(id:String,params:String?=null):CatalogPage {require(MusicCatalogParser.validBrowse(id));return browseRequest(id,null,emptyList(),emptySet(),params)}
    private fun browseRequest(id:String?,token:String?,pending:List<String>,seen:Set<String>,params:String?=null):CatalogPage = MusicCatalog.request {downloader->
        val client=JsonObject().apply {put("clientName","WEB_REMIX");put("clientVersion",YoutubeParsingHelper.getYoutubeMusicClientVersion());put("hl","en");put("platform","DESKTOP")}
        val body=JsonObject().apply {put("context",JsonObject().apply {put("client",client)});if(token!=null)put("continuation",token)else {put("browseId",id);params?.takeIf {it.length<=16000}?.let {put("params",it)}}}
        val response=downloader.postWithContentTypeJson("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false",YoutubeParsingHelper.getYoutubeMusicHeaders(),JsonWriter.string(body).toByteArray())
        val root=JsonParser.`object`().from(response.responseBody());val visited=seen+listOfNotNull(token)
        val remaining=(pending+tokens(root)).distinct().filter {it !in visited}
        CatalogPage(MusicCatalogParser.parse(root),remaining.firstOrNull()?.let {next->{browseRequest(null,next,remaining.drop(1),visited)}})
    }
    fun search(query:String,kind:String):CatalogPage=MusicCatalog.request {
        require(query.trim().length in 2..200 && kind in setOf("songs","artists","albums","playlists"))
        val extractor=ServiceList.YouTube.getSearchExtractor(query.trim(),listOf("music_$kind"),"");extractor.fetchPage()
        fun convert(page:ListExtractor.InfoItemsPage<InfoItem>):CatalogPage {
            val cards=page.items.mapNotNull {item->
                val art=item.thumbnails.lastOrNull()?.url?.takeIf(NewPipeCatalog::validArtwork)
                when(item){
                    is StreamInfoItem->YouTubeInput.parse(item.url)?.let {MusicCard(it,"song",item.name,item.uploaderName,art)}
                    is ChannelInfoItem->URI(item.url).path.substringAfterLast('/').takeIf(MusicCatalogParser::validBrowse)?.let {MusicCard(it,"artist",item.name,"Artist",art)}
                    is PlaylistInfoItem->URI(item.url).rawQuery.orEmpty().split('&').firstOrNull {it.startsWith("list=")}?.substringAfter('=')?.let {"VL$it"}?.takeIf(MusicCatalogParser::validBrowse)?.let {MusicCard(it,if(kind=="albums")"album" else "playlist",item.name,item.uploaderName,art)}
                    else->null
                }
            }.distinctBy {it.kind+it.id}
            val next=page.nextPage
            return CatalogPage(listOf(MusicShelf("Search results",cards)),if(Page.isValid(next))({MusicCatalog.request {convert(extractor.getPage(next))}})else null)
        };convert(extractor.initialPage)
    }
}
