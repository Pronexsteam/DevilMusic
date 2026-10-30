// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import java.io.IOException
import java.net.URI
import java.net.URLDecoder

internal object YouTubeInput {
    private val idPattern = Regex("[A-Za-z0-9_-]{11}")
    fun fromSearchInput(value:String):String? = if(value.trim().startsWith("https://"))parse(value) else null
    fun parse(value: String): String? {
        val text = value.trim()
        if (idPattern.matches(text)) return text
        if (text.length > 2048) return null
        return runCatching {
            val uri = URI(text)
            if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1) return null
            val id = when (uri.host?.lowercase()) {
                "youtu.be" -> uri.path.removePrefix("/").takeIf { '/' !in it }
                "youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com" -> {
                    when {
                        uri.path == "/watch" -> uri.rawQuery.orEmpty().split('&').map { it.split('=', limit = 2) }
                            .filter { it.size == 2 && it[0] == "v" }.singleOrNull()?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
                        uri.path.startsWith("/shorts/") || uri.path.startsWith("/embed/") -> uri.path.substringAfterLast('/')
                        else -> null
                    }
                }
                else -> null
            }
            id?.takeIf(idPattern::matches)
        }.getOrNull()
    }
    fun logical(id: String): String { require(idPattern.matches(id)); return "devilmusic-youtube://track/$id" }
    fun fromLogical(value: String): String? = runCatching {
        val uri = URI(value)
        if (uri.scheme != "devilmusic-youtube" || uri.host != "track" || uri.userInfo != null || uri.port != -1 || uri.query != null || uri.fragment != null) return null
        uri.path.removePrefix("/").takeIf(idPattern::matches)
    }.getOrNull()
}

internal class OnlineSourceException(val reason: String,val phase:String="resolve") : IOException(reason)

internal class OnlineSourcePolicy(private val nanoTime: () -> Long = System::nanoTime) {
    private val deadline = nanoTime() + 45_000_000_000L
    var failure: String? = null; private set
    private var apiCount = 0
    private var scriptCount = 0
    private var playerOk = false
    fun stop(reason: String): Nothing { if (failure == null) failure = reason; throw OnlineSourceException(failure!!) }
    fun check() { failure?.let { throw OnlineSourceException(it) }; if (Thread.currentThread().isInterrupted) stop("cancelled"); if (nanoTime() >= deadline) stop("timeout") }
    fun response(status: Int) { check(); if(status !in 200..299) stop(if(status in listOf(401,403,429)) "http_$status" else "http_error") }
    fun playability(status: String, matchingId: Boolean) {
        check()
        if(status != "OK") stop(when(status) {
            "" -> "missing_playability"
            "LOGIN_REQUIRED","AGE_CHECK_REQUIRED","CONTENT_CHECK_REQUIRED" -> "access_denied"
            "UNPLAYABLE","LIVE_STREAM_OFFLINE" -> "unavailable"
            "ERROR" -> "upstream_error"
            else -> "unknown_playability"
        })
        if(!matchingId)stop("wrong_track");playerOk=true
    }
    fun request(method: String, uri: URI, headers: Set<String>, client: String?, bytes: Int, videoId: String) {
        check()
        if (headers.any { it.lowercase() !in setOf("user-agent", "content-type", "accept-language", "x-goog-api-format-version") }) stop("unsupported_headers")
        if(uri.scheme != "https" || uri.userInfo != null || uri.port != -1) stop("unsupported_endpoint")
        if(method == "GET") {
            if(!playerOk || ++scriptCount > 4 || uri.host != "www.youtube.com" || uri.rawQuery != null ||
                !(uri.path == "/iframe_api" || uri.path == "/embed/$videoId" || Regex("/s/player/[A-Za-z0-9]+/player_ias\\.vflset/[A-Za-z_-]+/base\\.js").matches(uri.path))) stop("unsupported_script")
            return
        }
        if(method != "POST" || bytes !in 1..65536 || apiCount >= 4) stop("unsupported_request")
        val vision = apiCount >= 2
        if(vision && !playerOk) stop("additional_client_before_ok")
        val host = if(apiCount == 2) "www.youtube.com" else "youtubei.googleapis.com"
        val path = if(apiCount % 2 == 0) "/youtubei/v1/visitor_id" else if(vision) "/youtubei/v1/player" else "/youtubei/v1/reel/reel_item_watch"
        if(uri.host != host || uri.path != path || client != if(vision) "VISIONOS" else "ANDROID") stop("unsupported_sequence")
        apiCount++
    }
}
