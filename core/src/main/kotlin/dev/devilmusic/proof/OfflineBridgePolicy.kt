package dev.devilmusic.proof

/** Bounded protocol policy. This fixture bridge intentionally has no HTTP implementation. */
class OfflineBridgePolicy {
    private val seen = mutableSetOf<Int>()
    private var count = 0
    @Synchronized fun check(version: Int, id: Int, operation: String, path: String, exactFields: Boolean): String? {
        if (++count > 8) return "LIMIT"
        if (version != 1 || id !in 1..8 || !exactFields || !seen.add(id)) return "BAD_REQUEST"
        if (operation != "GET" || path != "proof://fixtures/catalog") return "DENIED"
        return null
    }
}
