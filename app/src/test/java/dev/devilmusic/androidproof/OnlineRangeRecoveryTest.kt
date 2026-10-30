// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import org.junit.Assert.*
import org.junit.Test
import dev.devilmusic.proof.PlayableSource
import java.net.*
import java.util.concurrent.atomic.AtomicReference

class OnlineRangeRecoveryTest {
    @Test fun temporaryServerErrorsRetryTwiceButOtherHttpStatusesRemainTerminal(){
        for(status in listOf(408,500,502,503,504))fixture(listOf("HTTP/1.1 $status Temporary\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")){stream,_->
            val e=failure("audio_http_$status"){stream.open(sourceUri,0)}
            assertEquals(500L,OnlineLoadErrorPolicy.retryDelay(e,1){throw AssertionError()})
            assertEquals(1000L,OnlineLoadErrorPolicy.retryDelay(e,2){throw AssertionError()})
            assertEquals(androidx.media3.common.C.TIME_UNSET,OnlineLoadErrorPolicy.retryDelay(e,3){throw AssertionError()})
        }
        for(status in listOf(302,400,404,416))fixture(listOf("HTTP/1.1 $status Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")){stream,_->
            val e=failure("audio_http_$status"){stream.open(sourceUri,0)}
            assertEquals(androidx.media3.common.C.TIME_UNSET,OnlineLoadErrorPolicy.retryDelay(e,1){throw AssertionError()})
        }
    }
    private val sourceUri=URI("https://test.googlevideo.com/videoplayback?token=private")
    private fun failure(reason:String,block:()->Unit):OnlineSourceException {
        try {block();throw AssertionError("Expected $reason")}catch(e:OnlineSourceException){assertEquals(reason,e.reason);return e}
    }
    /** Real loopback HTTP, exercising the production range stream (not a fake InputStream). */
    private fun fixture(responses:List<String>,body:(OnlineHttpStream,MutableList<String>)->Unit) {
        val server=ServerSocket(0,2,InetAddress.getByName("127.0.0.1"));server.soTimeout=5000
        val ranges=java.util.Collections.synchronizedList(mutableListOf<String>())
        val error=AtomicReference<Throwable?>()
        val worker=Thread {
            try {for(response in responses)server.accept().use {socket ->
                socket.soTimeout=5000
                val reader=socket.getInputStream().bufferedReader()
                while(true) {val line=reader.readLine() ?: break;if(line.isEmpty())break;if(line.startsWith("Range:",true))ranges.add(line.substringAfter(':').trim())}
                socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII));socket.getOutputStream().flush()
            }}catch(e:Throwable){error.set(e)}
        }.apply {isDaemon=true;start()}
        val stream=OnlineHttpStream(emptyMap()) {URL("http://127.0.0.1:${server.localPort}/audio").openConnection() as HttpURLConnection}
        try {body(stream,ranges);worker.join(6000);assertFalse("Fixture did not finish",worker.isAlive);error.get()?.let {throw AssertionError("Fixture failed",it)}}
        finally {stream.close();server.close();worker.join(1000)}
    }
    @Test fun truncatedHttpResponseResumesExactBytesWithSameLease() {
        val responses=listOf(
            "HTTP/1.1 206 Partial Content\r\nContent-Length: 10\r\nContent-Range: bytes 0-9/10\r\nConnection: close\r\n\r\nabcd",
            "HTTP/1.1 206 Partial Content\r\nContent-Length: 6\r\nContent-Range: bytes 4-9/10\r\nConnection: close\r\n\r\nefghij")
        var resolutions=0
        val binding=OnlineLeaseBinding({_:String -> resolutions++;sourceUri},{_,_->})
        fixture(responses) {stream,ranges ->
            assertEquals(10L,stream.open(binding.open("track"),0))
            val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(10)
            val failure=failure("audio_truncated") {while(true){val n=stream.read(buffer,0,buffer.size);if(n<0)break;bytes.write(buffer,0,n)}}
            assertEquals("read",failure.phase);assertEquals(4,bytes.size())
            assertEquals(500L,OnlineLoadErrorPolicy.retryDelay(failure,1){throw AssertionError()})
            stream.close()
            assertEquals(6L,stream.open(binding.open("track"),bytes.size().toLong()))
            while(true){val n=stream.read(buffer,0,buffer.size);if(n<0)break;bytes.write(buffer,0,n)}
            assertEquals("abcdefghij",bytes.toString("US-ASCII"));assertEquals(1,resolutions)
            assertEquals(listOf("bytes=0-","bytes=4-"),ranges)
        }
    }
    @Test fun httpProtectionCannotBecomeRetryAndWrongRangeCannotDuplicateAudio() {
        for(status in listOf(401,403,429))fixture(listOf("HTTP/1.1 $status Denied\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")) {stream,_ ->
            val e=failure("audio_http_$status"){stream.open(sourceUri,4)}
            assertEquals("open",e.phase);assertEquals(androidx.media3.common.C.TIME_UNSET,OnlineLoadErrorPolicy.retryDelay(e,1){throw AssertionError()})
        }
        fixture(listOf("HTTP/1.1 206 Partial Content\r\nContent-Length: 10\r\nContent-Range: bytes 0-9/10\r\nConnection: close\r\n\r\nabcdefghij")) {stream,_ ->
            failure("audio_range_mismatch"){stream.open(sourceUri,4)}
        }
    }
    @Test fun retainedLeaseDoesNotResolveAgainOnExpiryOrDenial() {
        var now=1000L;var resolutions=0
        val lease=PlayableSource.RemoteLease(sourceUri,emptyMap(),100000,"audio/webm")
        val binding=OnlineLeaseBinding({_:String -> resolutions++;lease},{_,value ->
            if(!value.isUsable(now))throw OnlineSourceException("audio_lease_expired","open")
        })
        assertSame(lease,binding.open("track"));assertSame(lease,binding.open("track"))
        now=100000;failure("audio_lease_expired"){binding.open("track")};assertEquals(1,resolutions)
        val renewed=PlayableSource.RemoteLease(sourceUri,emptyMap(),200000,"audio/webm")
        val explicitSelection=OnlineLeaseBinding({_:String->resolutions++;renewed},{_,value->if(!value.isUsable(now))throw OnlineSourceException("audio_lease_expired","open")})
        assertSame(renewed,explicitSelection.open("track"));assertEquals(2,resolutions)
        val resolver=NewPipeAudioResolver()
        val resolved=NewPipeAudioResolver.Resolved(lease,"title","artist",null)
        failure("audio_lease_expired"){resolver.validateRetained("track",resolved)}
        resolver.deny("track");failure("access_denied"){resolver.validateRetained("track",resolved)}
    }
}
