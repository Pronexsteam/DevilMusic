// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.grack.nanojson.*
import java.io.File

internal class MusicStorage(context:Context) {
    val state=MusicStateStore(File(context.filesDir,"music-state"))
    val taste=ListeningTaste(File(context.filesDir,"music-state"))
    val discovery=HomeDiscovery(File(context.filesDir,"music-state"))
    val recommendations=RecommendationHistory(File(context.filesDir,"music-state"))
    val ui=UiPreferences(File(context.filesDir,"music-state"))
    val downloads=File(context.filesDir,"music-downloads").apply {mkdirs()}
    fun audio(id:String):File? {
        return verifiedDownloadFile(downloads,id)
    }
    fun cover(id:String):File?=id.removePrefix("download:").takeIf {id.startsWith("download:") && YouTubeInput.parse(it)==it}?.let {File(downloads,"$it.jpg").takeIf(File::isFile)}
    fun downloaded():List<SavedTrack> = downloads.listFiles {f->f.extension=="json"}.orEmpty().mapNotNull {file->runCatching {
        if(file.length()>8192)return@runCatching null
        val o=JsonParser.`object`().from(file.readText());val id=o.getString("id","");val f=audio(id) ?: return@runCatching null
        if(f.length()!=o.getLong("bytes",-1))return@runCatching null
        SavedTrack(id,o.getString("title","Downloaded audio"),o.getString("artist",""),o.getString("artwork","").takeIf(NewPipeCatalog::validArtwork))
    }.getOrNull()}.sortedBy {it.title.lowercase()}
    fun item(track:SavedTrack)=MediaItem.Builder().setMediaId(track.id).setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setArtworkUri(track.artwork?.let(Uri::parse)).build()).build()
    fun normalize(item:MediaItem,grants:Set<String>,packageName:String):MediaItem? {
        val id=item.mediaId
        val uri=when {
            id.startsWith("youtube:") && YouTubeInput.parse(id.removePrefix("youtube:"))==id.removePrefix("youtube:")->Uri.parse(YouTubeInput.logical(id.removePrefix("youtube:")))
            id in grants->Uri.parse(id)
            id.startsWith("download:")->audio(id)?.let(Uri::fromFile)
            id=="fixture:440"->Uri.parse("android.resource://$packageName/${R.raw.test_tone}")
            else->null
        } ?: return null
        return item.buildUpon().setUri(uri).build()
    }
    fun download(track:SavedTrack,progress:(Long,Long)->Unit):SavedTrack {
        val id=track.id.removePrefix("youtube:");require(track.id=="youtube:$id" && YouTubeInput.parse(id)==id)
        downloaded().firstOrNull {it.id=="download:$id"}?.let {return it}
        val resolver=OnlineSession.resolver
        val source=resolver.resolve(id)
        val file=File(downloads,"$id.audio")
        try {
            val bytes=CompleteAudioDownload().run(source.lease.uri,source.lease.headersForRequest(),file,{resolver.validateRetained(id,source)},progress)
            val saved=SavedTrack("download:$id",source.title,source.artist,source.artwork ?: track.artwork)
            atomicWrite(File(downloads,"$id.json"),JsonWriter.string(JsonObject().apply {put("id",saved.id);put("title",saved.title.take(512));put("artist",saved.artist.take(512));put("artwork",saved.artwork);put("bytes",bytes)}).toByteArray(Charsets.UTF_8))
            // Artwork is optional; failure must not invalidate verified audio.
            runCatching {saved.artwork?.let {OnlineArtwork.load(it)}?.let {bitmap->java.io.ByteArrayOutputStream().use {out->bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,88,out);atomicWrite(File(downloads,"$id.jpg"),out.toByteArray())}}}
            return saved
        }catch(e:OnlineSourceException){if(e.reason in setOf("audio_http_401","audio_http_403","audio_http_429"))resolver.deny(id);throw e}
    }
}
