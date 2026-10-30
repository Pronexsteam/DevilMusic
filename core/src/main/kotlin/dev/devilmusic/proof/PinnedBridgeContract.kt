package dev.devilmusic.proof

import java.net.URI

/** Original one-session bridge policy. Monotonic times, no URI/source/body retention. */
class PinnedBridgeBudget {
    enum class Completion { VALID, TIMEOUT, STALE }
    private val deadlines = mutableMapOf<Int, Long>()
    private val seen = mutableSetOf<Int>()
    private var closed = false
    private var attempts = 0
    @Synchronized fun begin(version: Int, id: Int, chars: Int, nowMs: Long): String? {
        if (closed) return "CANCELLED"
        if (++attempts > 8 || deadlines.size >= 2) return "LIMIT"
        if (version != 1 || id !in 1..8 || chars !in 1..131072 || nowMs < 0 || !seen.add(id)) return "BAD_REQUEST"
        deadlines[id] = nowMs + 1500
        return null
    }
    @Synchronized fun finish(id: Int, nowMs: Long): Completion {
        val deadline = deadlines.remove(id) ?: return Completion.STALE
        return if (closed) Completion.STALE else if (nowMs >= deadline) Completion.TIMEOUT else Completion.VALID
    }
    @Synchronized fun cancel(id: Int) { deadlines.remove(id) }
    @Synchronized fun isActive(id: Int, nowMs: Long): Boolean = !closed && deadlines[id]?.let { nowMs < it } == true
    @Synchronized fun close() { closed = true; deadlines.clear() }
}

/** Fixture-only acquisition gate; no network is implemented here. The resulting lease is Media3-ready. */
object PinnedFixtureSourceContract {
    fun lease(requested: TrackId.YouTube, responseId: String, uri: String, headers: Map<String, String>,
              expiresAtMs: Long, mime: String, nowMs: Long, playability: String = "OK", protection: Int? = null): PlayableSource.RemoteLease {
        when (protection) {
            null, 0, 1 -> Unit
            2 -> throw SourceFailure(FailureKind.VERIFICATION_PENDING)
            3 -> throw SourceFailure(FailureKind.VERIFICATION_REQUIRED)
            else -> throw SourceFailure(FailureKind.PROTECTION_UNKNOWN)
        }
        if (playability == "VERIFICATION_REJECTED") throw SourceFailure(FailureKind.VERIFICATION_REJECTED)
        if (playability != "OK") throw SourceFailure(FailureKind.ACCESS_DENIED)
        require(requested.value == responseId) { "Source identity mismatch" }
        val parsed = URI(uri)
        require(parsed.host == "media.fixture.invalid" && parsed.port == -1 && parsed.fragment == null)
        val expiries = parsed.rawQuery.orEmpty().split('&').filter { it.startsWith("expire=") }
        require(expiries.size == 1 && expiresAtMs % 1000 == 0L && expiries.single().removePrefix("expire=").toLongOrNull() == expiresAtMs / 1000)
        require(nowMs >= 0 && expiresAtMs > nowMs && expiresAtMs - nowMs <= 24 * 60 * 60 * 1000)
        require(headers.keys.none { it.lowercase() in setOf("authorization", "cookie", "set-cookie") })
        return PlayableSource.RemoteLease(parsed, headers, expiresAtMs, mime).also { require(it.isUsable(nowMs)) }
    }
}
