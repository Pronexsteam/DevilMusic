// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import java.net.HttpURLConnection
import java.net.URI
import java.io.InputStream

/** Production range transport; connection injection permits loopback-only fault tests. */
internal class OnlineHttpStream(private val headers:Map<String,String>,
    private val connect:(URI)->HttpURLConnection={it.toURL().openConnection() as HttpURLConnection}) {
    private var connection:HttpURLConnection?=null
    private var input:InputStream?=null
    private var remaining=-1L
    var totalLength:Long=-1;private set
    fun open(uri:URI,position:Long,length:Long=-1):Long {
        try {
            if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException()
            if(uri.scheme!="https" || uri.userInfo!=null || uri.port!=-1 || uri.host?.endsWith(".googlevideo.com")!=true)
                throw OnlineSourceException("audio_origin","open")
            if(position<0 || length< -1 || (length>0 && position>Long.MAX_VALUE-length))
                throw OnlineSourceException("audio_range_mismatch","open")
            if(length==0L) {remaining=0;return 0}
            var target=uri;var hops=0;val visited=hashSetOf<URI>()
            var http:HttpURLConnection
            var status:Int
            while(true){
                if(!visited.add(target))throw OnlineSourceException("audio_redirect_loop","open")
                http=connect(target);connection=http
                http.instanceFollowRedirects=false;http.connectTimeout=10_000;http.readTimeout=15_000
                headers.forEach(http::setRequestProperty)
                val end=if(length>=0)(position+length-1).toString() else ""
                http.setRequestProperty("Range","bytes=$position-$end")
                status=http.responseCode
                if(status !in listOf(301,302,303,307,308))break
                val location=http.getHeaderField("Location")
                if(location.isNullOrBlank())throw OnlineSourceException("audio_http_$status","open")
                if(++hops>3)throw OnlineSourceException("audio_redirect_limit","open")
                val next=runCatching {target.resolve(location)}.getOrNull()
                if(next==null || next.scheme!="https" || next.userInfo!=null || next.port!=-1 || next.fragment!=null || next.host?.endsWith(".googlevideo.com")!=true || next.path!="/videoplayback")throw OnlineSourceException("audio_redirect_origin","open")
                http.disconnect();target=next
                if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException()
            }
            if(status !in listOf(200,206))throw OnlineSourceException(if(status in 100..599)"audio_http_$status" else "audio_http_error","open")
            remaining=AudioRangePolicy.response(status,position,length,http.getHeaderField("Content-Range"),http.getHeaderFieldLong("Content-Length",-1))
            totalLength=if(status==200)http.getHeaderFieldLong("Content-Length",-1) else http.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1
            input=http.inputStream
            return remaining
        } catch(e:Exception) {runCatching {close()};throw OnlineTransportFailure.classify(e,"open")}
    }
    fun read(buffer:ByteArray,offset:Int,length:Int):Int {
        try {
            if(length==0)return 0
            if(remaining==0L)return -1
            if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException()
            val n=input?.read(buffer,offset,if(remaining<0)length else minOf(length.toLong(),remaining).toInt()) ?: -1
            if(n<0) {if(remaining>0)throw OnlineSourceException("audio_truncated","read");return -1}
            if(remaining>=0)remaining-=n
            return n
        } catch(e:Exception) {throw OnlineTransportFailure.classify(e,"read")}
    }
    fun close() {try {input?.close()} finally {input=null;connection?.disconnect();connection=null}}
}
