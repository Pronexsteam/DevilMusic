package dev.devilmusic.androidproof

import dev.devilmusic.proof.PlayableSource
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ResolverCacheConcurrencyTest {
    @Test fun readyTrackDoesNotWaitForUnrelatedExtractorWorkAndDenialsStillWin(){
        val resolver=NewPipeAudioResolver()
        val id="abcdefghijk"
        val source=NewPipeAudioResolver.Resolved(PlayableSource.RemoteLease(URI("https://rr1.googlevideo.com/videoplayback"),emptyMap(),System.currentTimeMillis()+3600000,"audio/webm"),"Track","Artist",null)
        val field=NewPipeAudioResolver::class.java.getDeclaredField("leases").apply {isAccessible=true}
        @Suppress("UNCHECKED_CAST") val cache=field.get(resolver) as MutableMap<String,NewPipeAudioResolver.Resolved>
        cache[id]=source
        val worker=Executors.newSingleThreadExecutor()
        try {
            synchronized(NewPipeAccess.lock){assertSame(source,worker.submit<NewPipeAudioResolver.Resolved>{resolver.resolve(id)}.get(2,TimeUnit.SECONDS))}
            resolver.deny(id)
            assertThrows(OnlineSourceException::class.java){resolver.resolve(id)}
            assertThrows(OnlineSourceException::class.java){resolver.validateRetained(id,source)}
        }finally{worker.shutdownNow()}
    }
}
