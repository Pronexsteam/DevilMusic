// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof
import java.io.File
import java.security.MessageDigest

/** Disposable public image bytes; no profile data, source URLs or credentials. */
internal class ArtworkDiskCache(private val root:File,private val limit:Long=64L*1024*1024){
    companion object {private val lock=Any();private const val TTL=14L*24*60*60*1000}
    private fun file(key:String)=File(root,MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString(""){"%02x".format(it)}+".img")
    fun get(key:String,now:Long=System.currentTimeMillis()):ByteArray? = synchronized(lock){runCatching {
        val f=file(key);if(!f.isFile)return@synchronized null
        if(f.length()>4*1024*1024 || now-f.lastModified() !in 0..TTL){f.delete();return@synchronized null}
        f.readBytes().also {f.setLastModified(now)}
    }.getOrNull()}
    fun put(key:String,bytes:ByteArray,now:Long=System.currentTimeMillis())=synchronized(lock){runCatching {
        if(bytes.size>4*1024*1024 || bytes.size>limit)return@synchronized
        atomicWrite(file(key),bytes);file(key).setLastModified(now)
        val files=root.listFiles()?.filter {it.extension=="img"}.orEmpty().sortedBy {it.lastModified()}
        var total=files.sumOf {it.length()}
        for(f in files){if(total<=limit)break;val size=f.length();if(f.delete())total-=size}
    };Unit}
}
