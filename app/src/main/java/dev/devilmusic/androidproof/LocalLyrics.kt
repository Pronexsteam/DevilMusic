package dev.devilmusic.androidproof
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

internal class LocalLyrics(private val root:File) {
    private fun file(id:String):File {val key=MessageDigest.getInstance("SHA-256").digest(tasteId(id).toByteArray()).joinToString(""){"%02x".format(it)};return File(root,"$key.txt")}
    fun load(id:String):Lyrics?=file(id).takeIf {it.isFile}?.let {decode(it.readText())}
    fun save(id:String,input:InputStream){
        val out=java.io.ByteArrayOutputStream();val b=ByteArray(8192)
        while(true){val n=input.read(b);if(n<0)break;require(out.size()+n<=512000){"Lyrics file is too large"};out.write(b,0,n)}
        val raw=out.toString("UTF-8");decode(raw);atomicWrite(file(id),raw.toByteArray())
    }
    fun remove(id:String){file(id).delete()}
    companion object {
        fun decode(raw:String):Lyrics {
            require(raw.length<=512000 && raw.isNotBlank())
            if(raw.trimStart().startsWith("version:"))return Lyricsfile.parse(raw).copy(source="Local lyrics")
            val lines=Lrc.parse(raw)
            require(lines.isNotEmpty()){ "Choose a timed LRC or Lyricsfile document" }
            return Lyrics(lines,source="Local lyrics")
        }
    }
}
