package dev.devilmusic.proof

import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

/** IDs are logical library identities. Neither a desktop path nor a signed URL is an ID. */
sealed class TrackId(val value: String) {
    class Local private constructor(value: String) : TrackId(value) {
        companion object {
            fun parse(value: String): Local {
                require(Regex("local:[a-f0-9]{64}").matches(value))
                return Local(value)
            }
        }
    }
    class YouTube private constructor(value: String) : TrackId(value) {
        companion object {
            fun parse(value: String): YouTube {
                require(Regex("[A-Za-z0-9_-]{11}").matches(value))
                return YouTube(value)
            }
        }
    }
}

data class Track(val id: TrackId, val title: String, val artists: List<String>, val durationMs: Long?)
data class CatalogPage(val tracks: List<Track>, val continuation: String?)

interface OnlineCatalog {
    /** Cancellation and response-size limits belong to the concrete HTTP adapter. */
    fun search(query: String, continuation: String?): CompletableFuture<CatalogPage>
}

enum class ResolveReason { INITIAL, LEASE_EXPIRED }
enum class FailureKind { MISSING_LOCAL, PERMISSION_REVOKED, ACCESS_DENIED, RATE_LIMITED, NETWORK, UNSUPPORTED, VERIFICATION_PENDING, VERIFICATION_REQUIRED, VERIFICATION_REJECTED, PROTECTION_UNKNOWN }

/** Intentionally contains no upstream URL, headers, token, filesystem path, or response body. */
class SourceFailure(val kind: FailureKind) : Exception(kind.name)

sealed class PlayableSource {
    class LocalDocument(val uri: URI) : PlayableSource() {
        init { require(uri.scheme == "content" && !uri.authority.isNullOrBlank()) }
        override fun toString() = "LocalDocument(redacted)"
    }

    /** Memory-only lease. Never put this object in the durable queue/profile/diagnostic log. */
    class RemoteLease(
        val uri: URI,
        headers: Map<String, String>,
        val expiresAtMs: Long,
        val mimeType: String,
        val downloadAllowed: Boolean = false,
    ) : PlayableSource() {
        private val requestHeaders = headers.toMap()
        init {
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null)
            require(expiresAtMs > 0)
            require(mimeType in setOf("audio/mp4", "audio/webm", "audio/mpeg", "audio/flac", "audio/ogg"))
            require(requestHeaders.size <= 16)
            require(requestHeaders.all { (name, value) ->
                Regex("[A-Za-z0-9-]{1,64}").matches(name) && value.length <= 8192 &&
                    value.none { it == '\r' || it == '\n' || it == '\u0000' } &&
                    name.lowercase() !in setOf("host", "connection", "content-length", "transfer-encoding")
            })
        }
        fun isUsable(nowMs: Long, reserveMs: Long = 30_000): Boolean =
            reserveMs >= 0 && nowMs >= 0 && expiresAtMs > nowMs && expiresAtMs - nowMs > reserveMs
        fun headersForRequest(): Map<String, String> = requestHeaders.toMap()
        override fun toString() = "RemoteLease(redacted, expiresAtMs=$expiresAtMs)"
    }
}

fun interface LocalDocuments {
    /** Resolve only an indexed SAF grant; do not interpret the ID as a path or online query. */
    fun resolve(id: TrackId.Local): CompletableFuture<PlayableSource.LocalDocument>
}

fun interface OnlineSourceResolver {
    /** No Android YouTube implementation is supplied by this proof. */
    fun resolve(id: TrackId.YouTube, reason: ResolveReason): CompletableFuture<PlayableSource.RemoteLease>
}

class SourceRouter(private val local: LocalDocuments, private val online: OnlineSourceResolver) {
    fun resolve(id: TrackId, reason: ResolveReason = ResolveReason.INITIAL): CompletableFuture<PlayableSource> =
        when (id) {
            is TrackId.Local -> local.resolve(id).thenApply { it }
            is TrackId.YouTube -> online.resolve(id, reason).thenApply { it }
        }
}

/** Selection generations prevent late asynchronous resolution from replacing a newer track. */
class SelectionGate {
    private val generation = AtomicLong()
    fun select(): Long = generation.incrementAndGet()
    fun cancel() { generation.incrementAndGet() }
    fun isCurrent(ticket: Long): Boolean = ticket == generation.get()
}

object PlaybackPolicy {
    fun speed(value: Float): Float = if (value.isFinite()) value.coerceIn(.5f, 2f) else 1f
    const val PITCH = 1f
    // Authorization denial/rate limiting is not evidence that a lease merely expired.
    fun shouldRefreshBeforePlay(source: PlayableSource, nowMs: Long): Boolean =
        source is PlayableSource.RemoteLease && !source.isUsable(nowMs)
}
