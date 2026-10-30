// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Full-object verification, including capped 206 responses. No extractor fallback or lease renewal. */
internal class CompleteAudioDownload(private val connect:(URI)->HttpURLConnection={it.toURL().openConnection() as HttpURLConnection}) {
    fun run(uri:URI,headers:Map<String,String>,file:File,validate:()->Unit,progress:(Long,Long)->Unit):Long {
        val part=File(file.parentFile,file.name+".part")
        var offset=0L;var total=-1L;var attempts=0;var requests=0
        val deadline=System.nanoTime()+900_000_000_000L
        try {
            FileOutputStream(part).use {out ->
                val buffer=ByteArray(32*1024)
                while(total<0 || offset<total) {
                    if(Thread.currentThread().isInterrupted)throw OnlineSourceException("audio_cancelled","download")
                    if(++requests>128 || System.nanoTime()>deadline)throw OnlineSourceException("download_budget","download")
                    validate()
                    val stream=OnlineHttpStream(headers,connect)
                    try {
                        stream.open(uri,offset)
                        val size=stream.totalLength
                        if(size<=0 || size>256L*1024*1024 || (total>=0 && size!=total))throw OnlineSourceException("download_size_unverified","download")
                        total=size
                        while(true) {
                            if(Thread.currentThread().isInterrupted)throw OnlineSourceException("audio_cancelled","download")
                            if(System.nanoTime()>deadline)throw OnlineSourceException("download_budget","download")
                            val n=stream.read(buffer,0,buffer.size);if(n<0)break
                            if(offset+n>total)throw OnlineSourceException("download_size_mismatch","download")
                            out.write(buffer,0,n);offset+=n;progress(offset,total)
                        }
                        attempts=0
                    } catch(e:OnlineSourceException) {
                        if(!OnlineTransportFailure.recoverable(e) || ++attempts>2)throw e
                        try {Thread.sleep(attempts*500L)}catch(_:InterruptedException){Thread.currentThread().interrupt();throw OnlineSourceException("audio_cancelled","download")}
                    } finally {stream.close()}
                }
                if(offset!=total || total<=0)throw OnlineSourceException("download_incomplete","download")
                out.fd.sync()
            }
            validate()
            Files.move(part.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING)
            return total
        } finally {part.delete()}
    }
}
