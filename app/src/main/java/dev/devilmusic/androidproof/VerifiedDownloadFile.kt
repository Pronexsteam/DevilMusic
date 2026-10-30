package dev.devilmusic.androidproof
import com.grack.nanojson.JsonParser
import java.io.File

internal fun verifiedDownloadFile(root:File,id:String):File? {
    val key=id.removePrefix("download:")
    if(!id.startsWith("download:") || YouTubeInput.parse(key)!=key)return null
    val file=File(root,"$key.audio")
    return runCatching {
        val manifest=File(root,"$key.json");if(!manifest.isFile || manifest.length()>8192)return null
        val o=JsonParser.`object`().from(manifest.readText())
        file.takeIf {it.canonicalFile.parentFile==root.canonicalFile && it.isFile && it.length()>0 && o.getString("id","")==id && it.length()==o.getLong("bytes",-1)}
    }.getOrNull()
}
