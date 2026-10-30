package dev.devilmusic.androidproof

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import dev.devilmusic.proof.BoundedAudioPipe
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Unconnected Media3 seam: accepts validated container bytes, never a SABR URL or UMP frames. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SequentialAudioDataSource private constructor(
    private val pipe: BoundedAudioPipe,
    private val cancelProducer: () -> Unit,
) : BaseDataSource(false) {
    private var opened = false
    private var used = false
    private var closed = false
    private var currentUri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        if (used || closed) throw IOException("Sequential source cannot reopen")
        if (dataSpec.position != 0L || dataSpec.uri.scheme != "proof-audio") throw IOException("Only sequential audio bytes are supported")
        used = true
        transferInitializing(dataSpec)
        currentUri = dataSpec.uri; remaining = dataSpec.length; opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!opened || closed) throw IOException("Audio source is not open")
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = pipe.read(buffer, offset, if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt())
        if (count < 0 && remaining > 0) throw java.io.EOFException("Audio stream ended before its declared length")
        if (count > 0) { if (remaining != C.LENGTH_UNSET.toLong()) remaining -= count; bytesTransferred(count) }
        return count
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        if (closed) return
        closed = true
        pipe.close()
        try { cancelProducer() }
        finally { currentUri = null; if (opened) { opened = false; transferEnded() } }
    }

    /** A new track needs a new pipe/factory. Retry/seek cannot replay an already consumed stream. */
    class Factory(private val pipe: BoundedAudioPipe, private val cancelProducer: () -> Unit) : DataSource.Factory {
        private val claimed = AtomicBoolean(false)
        override fun createDataSource(): DataSource {
            check(claimed.compareAndSet(false, true)) { "Sequential pipe already claimed" }
            return SequentialAudioDataSource(pipe, cancelProducer)
        }
    }
}
