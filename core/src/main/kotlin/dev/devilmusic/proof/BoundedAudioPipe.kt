package dev.devilmusic.proof

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Single producer/consumer, sequential container bytes. No URLs, UMP or decoder work here. */
class BoundedAudioPipe(capacity: Int = 256 * 1024, private val timeoutMs: Long = 10_000) : AutoCloseable {
    init { require(capacity in 1..1024 * 1024); require(timeoutMs in 1..60_000) }
    private val buffer = ByteArray(capacity)
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var head = 0
    private var size = 0
    private var ended = false
    private var stopped = false

    val bufferedBytes: Int get() = lock.withLock { size }

    @Throws(IOException::class)
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) = lock.withLock {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var cursor = offset
        val end = offset + length
        checkOpen()
        if (ended) throw IOException("Audio producer already finished")
        while (cursor < end) {
            while (size == buffer.size) { awaitChange(deadline); checkOpen(); if (ended) throw IOException("Audio producer already finished") }
            checkOpen()
            val tail = (head + size) % buffer.size
            val count = minOf(end - cursor, buffer.size - size, buffer.size - tail)
            bytes.copyInto(buffer, tail, cursor, cursor + count)
            size += count; cursor += count; changed.signalAll()
        }
    }

    @Throws(IOException::class)
    fun read(target: ByteArray, offset: Int, length: Int): Int = lock.withLock {
        require(offset >= 0 && length >= 0 && offset <= target.size - length)
        checkOpen()
        if (length == 0) return 0
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (size == 0 && !ended) { awaitChange(deadline); checkOpen() }
        checkOpen()
        if (size == 0) return -1
        val count = minOf(length, size, buffer.size - head)
        buffer.copyInto(target, offset, head, head + count)
        head = (head + count) % buffer.size; size -= count; changed.signalAll()
        count
    }

    fun finish() = lock.withLock { checkOpen(); ended = true; changed.signalAll() }

    /** Stop/failure drops queued bytes and wakes both sides; it is never reported as normal EOF. */
    override fun close() = lock.withLock { stopped = true; size = 0; buffer.fill(0); changed.signalAll() }
    private fun checkOpen() { if (stopped) throw IOException("Audio transport stopped") }
    private fun awaitChange(deadline: Long) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) { close(); throw IOException("Audio transport timed out") }
        try { changed.awaitNanos(remaining) }
        catch (_: InterruptedException) { close(); Thread.currentThread().interrupt(); throw InterruptedIOException("Audio transport interrupted") }
    }
}
