// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import dev.devilmusic.proof.PlayableSource
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeStreamHelper
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.utils.Parser
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Blocking load-thread adapter. Only the playback service owns leases and this resolver. */
internal class NewPipeAudioResolver {
    data class Resolved(val lease: PlayableSource.RemoteLease, val title: String, val artist: String, val artwork:String?)
    private val leases = object : LinkedHashMap<String,Resolved>(32,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,Resolved>?) = size > 32
    }
    private val denied = LinkedHashSet<String>()
    @Synchronized fun deny(id: String) { leases.remove(id); denied.add(id) }
    @Synchronized fun validateRetained(id:String,source:Resolved) {
        if(id in denied)throw OnlineSourceException("access_denied","open")
        if(!source.lease.isUsable(System.currentTimeMillis()))throw OnlineSourceException("audio_lease_expired","open")
    }
    @Synchronized private fun cached(id:String):Resolved? {
        if(id in denied)throw OnlineSourceException("access_denied")
        return leases[id]?.takeIf {it.lease.isUsable(System.currentTimeMillis())}
    }
    fun resolve(id: String): Resolved {
        if(YouTubeInput.parse(id) != id) throw OnlineSourceException("invalid_track")
        cached(id)?.let {return it}
        return synchronized(NewPipeAccess.lock) {
            cached(id)?.let {return@synchronized it}
            val policy = OnlineSourcePolicy()
            try {
                AndroidNewPipeScript.installOnce()
                val downloader = NativeDownloader(id,policy)
                val locale = Localization("en","GB"); val country = ContentCountry("GB")
                NewPipe.init(downloader,locale,country)
                var cpn = YoutubeParsingHelper.generateContentPlaybackNonce()
                var player = YoutubeStreamHelper.getAndroidReelPlayerResponse(country,locale,id,cpn)
                var choice = choose(player)
                var vision = false
                if(choice == null) {
                    policy.check(); cpn = YoutubeParsingHelper.generateContentPlaybackNonce()
                    player = YoutubeStreamHelper.getVisionOsPlayerResponse(country,locale,id,cpn)
                    choice = choose(player); vision = true
                }
                val format = choice ?: policy.stop("no_audio_source")
                var url = format.getString("url", "")
                if(url.isEmpty()) {
                    val cipher = Parser.compatParseMap(format.getString("cipher",format.getString("signatureCipher","")))
                    val signature = YoutubeJavaScriptPlayerManager.deobfuscateSignature(id,cipher["s"].orEmpty())
                    policy.check(); url = "${cipher["url"]}&${cipher["sp"] ?: "signature"}=$signature"
                }
                url = YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(id,url)
                policy.check(); url += "&cpn=$cpn"
                val uri = URI(url)
                if(uri.scheme != "https" || uri.host?.endsWith(".googlevideo.com") != true || uri.userInfo != null || uri.port != -1 || uri.path != "/videoplayback") policy.stop("unsupported_source")
                val expires = uri.rawQuery.orEmpty().split('&').map { it.split('=',limit=2) }.filter { it[0] == "expire" }
                val expiry = expires.singleOrNull()?.getOrNull(1)?.let { URLDecoder.decode(it,"UTF-8").toLongOrNull() }?.times(1000) ?: policy.stop("invalid_expiry")
                if(expiry !in (System.currentTimeMillis()+30_001)..(System.currentTimeMillis()+86_400_000)) policy.stop("expired_source")
                val ua = if(vision) YoutubeParsingHelper.getVisionOsUserAgent(locale) else YoutubeParsingHelper.getAndroidUserAgent(locale)
                val lease = PlayableSource.RemoteLease(uri,mapOf("User-Agent" to ua),expiry,format.getString("mimeType","").substringBefore(';'))
                val details=player.getObject("videoDetails")
                val artwork=details.getObject("thumbnail").getArray("thumbnails").filterIsInstance<JsonObject>().map {it.getString("url","")}.lastOrNull(NewPipeCatalog::validArtwork)
                Resolved(lease,details.getString("title","YouTube audio"),details.getString("author","YouTube"),artwork).also {source->synchronized(this){if(id in denied)throw OnlineSourceException("access_denied");leases[id]=source}}
            } catch(e: Exception) {
                val reason=policy.failure ?: "extractor_error"
                if(reason in setOf("http_401","http_403","http_429","access_denied")) deny(id)
                throw OnlineSourceException(reason)
            }
        }
    }
    private fun choose(player: JsonObject): JsonObject? = player.getObject("streamingData").getArray("adaptiveFormats")
        .filterIsInstance<JsonObject>().filter { it.getString("mimeType","").startsWith("audio/") &&
            (it.getString("url","").isNotEmpty() || it.getString("cipher",it.getString("signatureCipher","")).isNotEmpty()) }
        .maxByOrNull { it.getInt("bitrate",0) }
}
internal object NewPipeAccess { val lock = Any() }
internal object OnlineSession { val resolver=NewPipeAudioResolver() }

private class NativeDownloader(private val id: String, private val policy: OnlineSourcePolicy) : Downloader() {
    override fun execute(request: Request): Response {
        var connection: HttpURLConnection? = null
        val timer=Executors.newSingleThreadScheduledExecutor()
        try {
            policy.check()
            val body=request.dataToSend()
            val client=body?.let { JsonParser.`object`().from(String(it,Charsets.UTF_8)).getObject("context").getObject("client").getString("clientName","") }
            val uri=URI(request.url())
            policy.request(request.httpMethod(),uri,request.headers().keys,client,body?.size ?: 0,id)
            connection=uri.toURL().openConnection() as HttpURLConnection
            val http=connection
            http.instanceFollowRedirects=false; http.connectTimeout=10_000;http.readTimeout=10_000
            http.requestMethod=request.httpMethod()
            request.headers().forEach { (name,values) -> http.setRequestProperty(name,values.joinToString(",")) }
            timer.schedule({ http.disconnect() },12,TimeUnit.SECONDS)
            if(body != null) { http.doOutput=true;http.setFixedLengthStreamingMode(body.size);http.outputStream.use { it.write(body) } }
            val status=http.responseCode; policy.response(status)
            val cap=if(request.httpMethod()=="GET")6*1024*1024 else 2*1024*1024
            val bytes=http.inputStream.use { input ->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) { policy.check();val n=input.read(buffer);if(n<0)break;if(out.size()+n>cap)policy.stop("response_too_large");out.write(buffer,0,n) };out.toByteArray()
            }
            val text=String(bytes,Charsets.UTF_8)
            if(request.httpMethod()=="POST" && !uri.path.endsWith("visitor_id")) {
                val root=JsonParser.`object`().from(text)
                val player=if(uri.path.contains("reel/"))root.getObject("playerResponse") else root
                policy.playability(player.getObject("playabilityStatus").getString("status",""),player.getObject("videoDetails").getString("videoId","")==id)
            }
            policy.check()
            return Response(status,"",http.headerFields.filterKeys { it != null },text,request.url())
        } catch(e: Exception) { policy.stop(policy.failure ?: if(Thread.currentThread().isInterrupted)"cancelled" else "network_error") }
        finally { connection?.disconnect();timer.shutdownNow() }
    }
}
