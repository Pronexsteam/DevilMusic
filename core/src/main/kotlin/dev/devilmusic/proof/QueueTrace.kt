package dev.devilmusic.proof

import java.security.MessageDigest
import java.security.SecureRandom

/** Bounded, memory-only trace. IDs use a fresh per-process salt, never raw paths or file names. */
class QueueTrace(private val salt: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }) {
    enum class Scope { SERVICE, CONTROLLER, UI }
    enum class Event { SELECT, AFTER_SELECT, NEXT, PREVIOUS, TIMELINE, TRANSITION, RENDER, REPLACE }
    private val lines = ArrayDeque<String>()
    private var sequence = 0L
    private data class Cached(val ids:List<String>,val order:String,val preview:String)
    private val cache=java.util.EnumMap<Scope,Cached>(Scope::class.java)
    private fun digest(values: List<String>): String {
        val hash = MessageDigest.getInstance("SHA-256"); hash.update(salt)
        for (value in values) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            hash.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte()))
            hash.update(bytes)
        }
        return hash.digest().take(6).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    @Synchronized fun record(scope: Scope, event: Event, ids: List<String>, currentIndex: Int,
        requestedId: String? = null, targetIndex: Int? = null, reason: Int? = null) {
        val cached=cache[scope]?.takeIf {it.ids===ids} ?: Cached(ids,digest(ids),ids.take(8).mapIndexed {index,id->"${index+1}:${digest(listOf(id))}"}.joinToString(",")).also {cache[scope]=it}
        lines.addLast("${++sequence} ${scope.name}/${event.name} count=${ids.size} current=$currentIndex order=${cached.order}" +
            (requestedId?.let { " requested=${digest(listOf(it))} target=$targetIndex" } ?: "") +
            (reason?.let { " reason=$it" } ?: "") + " rows=[${cached.preview}${if (ids.size > 8) ",…" else ""}]")
        while (lines.size > 32) lines.removeFirst()
    }
    @Synchronized fun report(): String = "Queue trace · positions are zero-based except rows.\nNo names or URIs; tokens reset with the process.\n" + lines.joinToString("\n")
}
