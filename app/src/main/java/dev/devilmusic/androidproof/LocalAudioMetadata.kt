package dev.devilmusic.androidproof

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.devilmusic.proof.grantedDocumentIdentity

/** Reads only documents explicitly selected and granted to this app. No directory scan or artwork index. */
class LocalAudioMetadata(private val context: Context) {
    fun sourceUri(item: MediaItem): Uri? = grantedDocumentIdentity(item.mediaId, item.localConfiguration?.uri?.toString(),
        context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet())?.let(Uri::parse)
    private fun text(value: String?): String? = value?.replace(Regex("[\\p{Cc}\\u202A-\\u202E\\u2066-\\u2069]"), " ")
        ?.trim()?.take(256)?.takeIf { it.isNotBlank() && !it.equals("<unknown>", true) }

    fun item(uri: Uri, cancellation: CancellationSignal): MediaItem {
        requireGranted(uri)
        var name: String? = null
        // OpenableColumns are the portable SAF contract. Never derive a name from an opaque document ID.
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, cancellation)?.use { cursor ->
            if (cursor.moveToFirst()) name = text(cursor.getString(0))
        }
        return MediaItem.Builder().setMediaId(uri.toString()).setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(name ?: "Audio file").setDisplayTitle(name ?: "Audio file").build()).build()
    }

    data class Current(val title: String?, val artist: String?, val album: String?, val artwork: Bitmap?)

    /** On a background worker, for the current item only. Do not retain full encoded artwork. */
    fun current(uri: Uri): Current {
        requireGranted(uri)
        return read { it.setDataSource(context, uri) }
    }

    /** User-invoked diagnostic, on a worker; exercises the same retriever/decode path on owned resources. */
    fun checkBundledFixtures(): String {
        fun fixture(resource: Int): Current = context.resources.openRawResourceFd(resource).use { descriptor ->
            read { it.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length) }
        }
        val withArt = fixture(R.raw.artwork_fixture)
        val withoutArt = fixture(R.raw.no_artwork_fixture)
        try {
            check(withArt.title == "Fixture with artwork" && withArt.artist == "Devil Music test")
            check(withArt.artwork?.width == 192 && withArt.artwork.height == 192)
            check(withoutArt.title == "Fixture without artwork" && withoutArt.artist == "Devil Music test")
            check(withoutArt.artwork == null)
            return "ANDROID_ARTWORK_FIXTURES: PASS\nEmbedded PNG + title/artist: PASS\nNo-art file: PASS\nOnly bundled synthetic fixtures were read. No queue or playback changes."
        } finally { withArt.artwork?.recycle(); withoutArt.artwork?.recycle() }
    }

    private fun read(configure: (MediaMetadataRetriever) -> Unit): Current {
        val retriever = MediaMetadataRetriever()
        try {
            configure(retriever)
            val encoded = retriever.embeddedPicture
            val artwork = if (encoded != null && encoded.size <= 4 * 1024 * 1024) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
                if (bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384) {
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 768) sample *= 2
                    BitmapFactory.decodeByteArray(encoded, 0, encoded.size, BitmapFactory.Options().apply { inSampleSize = sample })
                } else null
            } else null
            return Current(text(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)),
                text(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)),
                text(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)), artwork)
        } finally { retriever.release() }
    }

    private fun requireGranted(uri: Uri) {
        require(uri.scheme == "content")
        check(context.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri == uri }) { "Document grant missing" }
    }
}
