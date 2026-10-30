// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import com.grack.nanojson.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.security.MessageDigest

internal data class LyricWord(val time:Long,val text:String,val end:Long?=null)
internal data class LyricLine(val time:Long,val text:String,val words:List<LyricWord> = emptyList(),val end:Long?=null)
internal data class Lyrics(val lines:List<LyricLine> = emptyList(),val plain:String="",val instrumental:Boolean=false,val source:String="LRCLIB")
internal object Lrc {
    private val stamp=Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val word=Regex("<(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>")
    private fun ms(m:MatchResult):Long? {val s=m.groupValues[2].toInt();if(s>59)return null;return m.groupValues[1].toLong()*60000+s*1000+m.groupValues[3].padEnd(3,'0').take(3).toLong()}
    fun parse(value:String):List<LyricLine> {
        val offset=Regex("\\[offset:([+-]?\\d+)]",RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(-60000,60000) ?: 0
        return value.take(256000).lineSequence().take(3000).flatMap {raw->
            val times=stamp.findAll(raw).toList();val body=raw.substring(times.lastOrNull()?.range?.last?.plus(1) ?: raw.length).trim()
            val marks=word.findAll(body).toList()
            val words=marks.mapIndexedNotNull {i,m->ms(m)?.let {LyricWord((it+offset).coerceAtLeast(0),body.substring(m.range.last+1,marks.getOrNull(i+1)?.range?.first ?: body.length))}}.filter {it.text.isNotEmpty()}
            val clean=body.replace(word,"").trim()
            times.mapNotNull {m->ms(m)?.let {LyricLine((it+offset).coerceAtLeast(0),clean,if(times.size==1 && words.zipWithNext().all {it.first.time<=it.second.time})words else emptyList())}}.asSequence()
        }.sortedBy {it.time}.distinctBy {it.time to it.text}.toList()
    }
    fun index(lines:List<LyricLine>,position:Long):Int {var lo=0;var hi=lines.size;while(lo<hi){val mid=(lo+hi)/2;if(lines[mid].time<=position)lo=mid+1 else hi=mid};return lo-1}
}
internal class LyricsRepository(private val root:File) {
    fun key(title:String,artist:String,seconds:Int)=MessageDigest.getInstance("SHA-256").digest("$title\n$artist\n$seconds".toByteArray()).joinToString(""){"%02x".format(it)}
    fun fetch(title:String,artist:String,seconds:Int):Lyrics {
        require(title.isNotBlank() && artist.isNotBlank() && seconds>0)
        root.mkdirs();val file=File(root,key(title,artist,seconds)+".json")
        val cached=file.takeIf {it.isFile && it.length()<512000 && System.currentTimeMillis()-it.lastModified()<7*86400000L}?.readText()
        val raw=cached ?: run {
            val args=listOf("track_name" to title.take(512),"artist_name" to artist.removeSuffix(" - Topic").take(512),"duration" to seconds.toString()).joinToString("&"){it.first+"="+URLEncoder.encode(it.second,"UTF-8")}
            val c=java.net.URL("https://lrclib.net/api/get?$args").openConnection() as HttpURLConnection
            try {c.instanceFollowRedirects=false;c.connectTimeout=7000;c.readTimeout=7000;c.setRequestProperty("User-Agent","DevilMusicAndroid/0.0.16")
                if(c.responseCode==404)return Lyrics()
                check(c.responseCode==200)
                val bytes=c.inputStream.use {input->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){check(!Thread.currentThread().isInterrupted);val n=input.read(buffer);if(n<0)break;check(out.size()+n<=512000);out.write(buffer,0,n)};out.toByteArray()};check(!Thread.currentThread().isInterrupted)
                String(bytes,Charsets.UTF_8).also {atomicWrite(file,bytes)}
            }finally{c.disconnect()}
        }
        root.listFiles().orEmpty().sortedByDescending {it.lastModified()}.drop(80).forEach {it.delete()}
        val o=JsonParser.`object`().from(raw)
        return decode(o)
    }
    companion object {
        fun decode(o:JsonObject):Lyrics {
            val rich=runCatching {Lyricsfile.parse(o.getString("lyricsfile","").orEmpty())}.getOrNull()
            val lines=Lrc.parse(o.getString("syncedLyrics","") ?: "")
            return if(rich!=null && rich.lines.any {it.words.isNotEmpty()})rich
                else Lyrics(lines.ifEmpty {rich?.lines.orEmpty()},o.getString("plainLyrics","") ?: rich?.plain.orEmpty(),o.getBoolean("instrumental",false))
        }
    }
}
