// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.downloader.*
import org.schabi.newpipe.extractor.localization.*
import java.net.URI
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class CatalogTrack(val id:String,val title:String,val artist:String,val artwork:String?)

/** Real NewPipe search parser. Serialized with source resolution because NewPipe has global state. */
internal object NewPipeCatalog {
    fun search(query:String):List<CatalogTrack> = synchronized(NewPipeAccess.lock) {
        require(query.trim().length in 2..200)
        val downloader=SearchDownloader()
        try {
            NewPipe.init(downloader,Localization("en","GB"),ContentCountry("GB"))
            val search=ServiceList.YouTube.getSearchExtractor(query.trim(),listOf("videos"),"")
            search.fetchPage()
            downloader.check()
            search.initialPage.items.filterIsInstance<StreamInfoItem>().take(20).mapNotNull { item ->
                YouTubeInput.parse(item.url)?.let { id -> CatalogTrack(id,item.name,item.uploaderName,
                    item.thumbnails.lastOrNull()?.url?.takeIf(::validArtwork)) }
            }
        } catch(e:Exception) { throw OnlineSourceException(downloader.failure ?: "search_unavailable") }
    }
    fun validArtwork(value:String):Boolean = runCatching {
        val u=URI(value);value.length<=2048 && u.scheme=="https" && u.userInfo==null && u.port==-1 &&
            ((u.host in setOf("i.ytimg.com","i9.ytimg.com") && u.path.startsWith("/vi")) || u.host in setOf("lh3.googleusercontent.com","yt3.googleusercontent.com"))
    }.getOrDefault(false)
}

/** Only public search/config endpoints. No account cookies, persistence, or protected retry. */
internal class SearchDownloader(private val music:Boolean=false):Downloader() {
    var failure:String?=null;private set
    private var requests=0
    private val deadline=System.nanoTime()+30_000_000_000L
    fun check() { failure?.let {throw OnlineSourceException(it)};if(Thread.currentThread().isInterrupted)stop("cancelled");if(System.nanoTime()>deadline)stop("search_timeout") }
    private fun stop(reason:String):Nothing {if(failure==null)failure=reason;throw OnlineSourceException(failure!!)}
    override fun execute(request:Request):Response {
        var http:HttpURLConnection?=null;val timer=Executors.newSingleThreadScheduledExecutor()
        try {
            check();if(++requests>4)stop("search_request_limit")
            val uri=URI(request.url())
            if(uri.scheme!="https" || uri.host!=(if(music)"music.youtube.com" else "www.youtube.com") || uri.userInfo!=null || uri.port!=-1)stop("search_endpoint")
            val allowed=if(request.httpMethod()=="GET")uri.path=="/sw.js" ||
                (if(music)uri.path=="/" && uri.rawQuery=="ucbcb=1" else uri.path=="/results" && uri.rawQuery=="search_query=&ucbcb=1")
                else request.httpMethod()=="POST" && uri.path in (if(music)setOf("/youtubei/v1/search","/youtubei/v1/browse","/youtubei/v1/music/get_search_suggestions") else setOf("/youtubei/v1/search","/youtubei/v1/guide"))
            if(!allowed)stop("search_endpoint")
            val headers=request.headers().filterKeys { !it.equals("cookie",true) }
            // v0.26.5 adds only this fixed anonymous consent preference. Omit it entirely;
            // do not forward unknown cookies or invent consent/authentication state.
            request.headers().filterKeys {it.equals("cookie",true)}.values.flatten().forEach { if(it!="SOCS=CAE=")stop("search_cookie_rejected") }
            if(headers.keys.any {it.lowercase() !in setOf("user-agent","content-type","accept-language","origin","referer","x-youtube-client-name","x-youtube-client-version")})stop("search_header")
            val c=uri.toURL().openConnection() as HttpURLConnection;http=c
            c.instanceFollowRedirects=false;c.connectTimeout=10_000;c.readTimeout=10_000;c.requestMethod=request.httpMethod()
            headers.forEach {(k,v)->c.setRequestProperty(k,v.joinToString(","))}
            timer.schedule({c.disconnect()},12,TimeUnit.SECONDS)
            // Browsing must release the global extractor promptly when the user starts audio.
            val owner=Thread.currentThread()
            timer.scheduleAtFixedRate({if(owner.isInterrupted)c.disconnect()},100,100,TimeUnit.MILLISECONDS)
            request.dataToSend()?.let {body->if(body.size>65536)stop("search_body");c.doOutput=true;c.setFixedLengthStreamingMode(body.size);c.outputStream.use {it.write(body)}}
            val status=c.responseCode;if(status !in 200..299)stop(if(status in listOf(401,403,429))"http_$status" else "search_http_error")
            val bytes=c.inputStream.use {input->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){check();val n=input.read(buffer);if(n<0)break;if(out.size()+n>6*1024*1024)stop("search_response_large");out.write(buffer,0,n)};out.toByteArray()}
            check()
            return Response(status,"",c.headerFields.filterKeys {it!=null && !it.equals("set-cookie",true)},String(bytes,Charsets.UTF_8),request.url())
        } catch(e:Exception) {stop(failure ?: if(Thread.currentThread().isInterrupted)"cancelled" else "search_network_error")}
        finally {http?.disconnect();timer.shutdownNow()}
    }
}
