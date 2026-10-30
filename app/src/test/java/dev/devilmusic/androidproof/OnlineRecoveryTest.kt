// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof
import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class OnlineRecoveryTest {
    @Test fun timeoutPreservesSafeTypeAndHasTwoRetriesPerErrorSequence() {
        val source=object:InputStream(){ override fun read():Int=throw java.net.SocketTimeoutException("https://secret.invalid/signed?token=private") }
        val failure=try {source.read();throw AssertionError()}catch(e:Exception){OnlineTransportFailure.classify(e,"read",false)}
        assertEquals("audio_timeout",failure.reason);assertEquals("read",failure.phase);assertNull(failure.cause)
        assertFalse(failure.toString().contains("secret"))
        assertEquals(500L,OnlineLoadErrorPolicy.retryDelay(failure,1){throw AssertionError()})
        assertEquals(1000L,OnlineLoadErrorPolicy.retryDelay(failure,2){throw AssertionError()})
        assertEquals(C.TIME_UNSET,OnlineLoadErrorPolicy.retryDelay(failure,3){throw AssertionError()})
    }
    @Test fun cancellationAndProtectionNeverRecover() {
        val cancelled=OnlineTransportFailure.classify(InterruptedIOException("private"),"read",true)
        assertEquals("audio_cancelled",cancelled.reason)
        for(reason in listOf("audio_cancelled","audio_interrupted","audio_http_401","audio_http_403","audio_http_429","access_denied","unknown_playability","audio_tls_error","audio_protocol_error","audio_io_unknown","audio_lease_expired")) {
            assertEquals(C.TIME_UNSET,OnlineLoadErrorPolicy.retryDelay(OnlineSourceException(reason),1){throw AssertionError()})
        }
    }
    @Test fun localErrorsKeepTheirOwnPolicyAndRemoteSocketIsRedacted() {
        assertEquals(1234L,OnlineLoadErrorPolicy.retryDelay(FileNotFoundException("local"),1){1234})
        val socket=OnlineTransportFailure.classify(java.net.SocketException("secret"),"read",false)
        assertEquals("audio_socket_error",socket.reason);assertEquals(500L,OnlineLoadErrorPolicy.retryDelay(IOException("wrapper",socket),1){throw AssertionError()})
    }
}
