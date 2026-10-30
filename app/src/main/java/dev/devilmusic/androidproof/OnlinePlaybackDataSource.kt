// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.*

/** Logical IDs stay in MediaSession; a signed URI exists only in the private load path. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class OnlinePlaybackDataSource(context: Context,private val resolver: NewPipeAudioResolver,
    private val resolved: (String,NewPipeAudioResolver.Resolved)->Unit = { _,_ -> }) : DataSource {
    private val local=DefaultDataSource.Factory(context).createDataSource()
    private var delegate: DataSource=local
    private var remoteId: String?=null
    private val listeners=mutableListOf<TransferListener>()
    private val binding=OnlineLeaseBinding(resolver::resolve,resolver::validateRetained)
    override fun addTransferListener(listener: TransferListener) { listeners.add(listener);local.addTransferListener(listener) }
    override fun open(spec: DataSpec): Long {
        val id=YouTubeInput.fromLogical(spec.uri.toString())
        remoteId=id
        if(id == null) { delegate=local;return delegate.open(spec) }
        return try {
            val source=binding.open(id)
            resolved(id,source)
            delegate=NativeOnlineMediaDataSource(source.lease.headersForRequest())
            listeners.forEach(delegate::addTransferListener)
            delegate.open(spec.withUri(Uri.parse(source.lease.uri.toASCIIString())))
        }
        catch(e: Exception) { val safe=sanitized(e,"open");denyOnProtection(safe);PlaybackTrace.recordOnlineFailure(remoteId,safe);runCatching { delegate.close() };throw safe }
    }
    override fun read(buffer: ByteArray,offset: Int,length: Int): Int = try { delegate.read(buffer,offset,length) }
        catch(e: Exception) { if(remoteId==null)throw e;val safe=sanitized(e,"read");denyOnProtection(safe);PlaybackTrace.recordOnlineFailure(remoteId,safe);throw safe }
    private fun denyOnProtection(e: Exception) { if(e is OnlineSourceException && e.reason in listOf("audio_http_401","audio_http_403","audio_http_429"))remoteId?.let(resolver::deny) }
    private fun sanitized(e: Exception,phase:String) = OnlineTransportFailure.classify(e,phase)
    // Do not expose the signed URI or upstream response headers through playback diagnostics.
    override fun getUri(): Uri? = remoteId?.let { Uri.parse(YouTubeInput.logical(it)) } ?: delegate.uri
    override fun getResponseHeaders(): Map<String,List<String>> = if(remoteId==null)delegate.responseHeaders else emptyMap()
    override fun close() { try { delegate.close() } finally { remoteId=null } }
}

/** No redirect/client fallback; Media3 supplies byte ranges and owns cancellation/release. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class NativeOnlineMediaDataSource(private val headers: Map<String,String>): BaseDataSource(true) {
    private val stream=OnlineHttpStream(headers)
    private var opened=false
    override fun open(spec: DataSpec): Long {
        transferInitializing(spec)
        try {
            val remaining=stream.open(java.net.URI(spec.uri.toString()),spec.position,spec.length)
            opened=true;transferStarted(spec);return remaining
        } catch(e:Exception) { runCatching {close()};throw OnlineTransportFailure.classify(e,"open") }
    }
    override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
        val n=stream.read(buffer,offset,length)
        if(n>0)bytesTransferred(n)
        return n
    }
    override fun getUri():Uri?=null
    override fun close() { try { stream.close() } finally {if(opened) {opened=false;transferEnded()} } }
}
