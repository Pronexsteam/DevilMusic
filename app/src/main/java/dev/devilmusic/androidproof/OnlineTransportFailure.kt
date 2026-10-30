// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

/** Never retain the upstream exception message/cause: it can contain a signed source URL. */
internal object OnlineTransportFailure {
    fun classify(error:Exception,phase:String,interrupted:Boolean=Thread.currentThread().isInterrupted):OnlineSourceException {
        if(error is OnlineSourceException)return error
        val kind=when {
            interrupted -> "audio_cancelled"
            error is java.net.SocketTimeoutException -> "audio_timeout"
            error is java.io.InterruptedIOException -> "audio_interrupted"
            error is javax.net.ssl.SSLException -> "audio_tls_error"
            error is java.net.UnknownHostException -> "audio_dns_error"
            error is java.net.ConnectException -> "audio_connect_error"
            error is java.net.SocketException -> "audio_socket_error"
            error is java.io.EOFException -> "audio_truncated"
            error is java.net.ProtocolException -> "audio_protocol_error"
            else -> "audio_io_unknown"
        }
        return OnlineSourceException(kind,phase)
    }
    fun recoverable(error:OnlineSourceException):Boolean = error.reason in setOf("audio_timeout","audio_socket_error","audio_connect_error","audio_truncated","audio_http_408","audio_http_500","audio_http_502","audio_http_503","audio_http_504")
}
