package dev.devilmusic.androidproof

import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dev.devilmusic.proof.PlayableSource
import dev.devilmusic.proof.TrackId

/** Adapter seam only: no YouTube resolver calls this yet; not exposed to external controllers. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object RemoteMediaSourceFactory {
    fun create(id: TrackId.YouTube, lease: PlayableSource.RemoteLease, nowMs: Long): MediaSource {
        require(lease.isUsable(nowMs)) { "Source lease needs renewal" }
        val data = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(10_000).setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(false)
            .setDefaultRequestProperties(lease.headersForRequest())
        return ProgressiveMediaSource.Factory(data).createMediaSource(MediaItem.Builder()
            .setMediaId(id.value).setUri(lease.uri.toASCIIString()).setMimeType(lease.mimeType).build())
    }
}
