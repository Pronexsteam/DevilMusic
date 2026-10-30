package dev.devilmusic.proof

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.*

class BoundedAudioPipeTest {
    @Test fun roundTripAcrossTinyRingPreservesBytesAndEof() {
        val pipe = BoundedAudioPipe(31)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val input = ByteArray(100_000) { (it * 17).toByte() }
            val writer = executor.submit { pipe.write(input); pipe.finish() }
            val output = ByteArrayOutputStream(); val chunk = ByteArray(47)
            while (true) { val count = pipe.read(chunk, 0, chunk.size); if (count < 0) break; output.write(chunk, 0, count); assertTrue(pipe.bufferedBytes <= 31) }
            writer.get(2, TimeUnit.SECONDS)
            assertContentEquals(input, output.toByteArray())
            assertEquals(-1, pipe.read(chunk, 0, 1)); assertEquals(0, pipe.read(chunk, 0, 0))
        } finally { pipe.close(); executor.shutdownNow() }
    }

    @Test fun fullPipeBackpressuresWriterUntilConsumerReads() {
        val pipe = BoundedAudioPipe(4); pipe.write(byteArrayOf(1, 2, 3, 4))
        val executor = Executors.newSingleThreadExecutor(); val started = CountDownLatch(1)
        try {
            val pending = executor.submit { started.countDown(); pipe.write(byteArrayOf(5)) }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { pending.get(50, TimeUnit.MILLISECONDS) }
            assertEquals(4, pipe.bufferedBytes)
            assertEquals(1, pipe.read(ByteArray(1), 0, 1)); pending.get(1, TimeUnit.SECONDS)
            assertEquals(4, pipe.bufferedBytes)
        } finally { pipe.close(); executor.shutdownNow() }
    }

    @Test fun cancellationWakesBlockedReaderAndWriterAndDropsQueuedBytes() {
        for (writer in listOf(false, true)) {
            val pipe = BoundedAudioPipe(1)
            if (writer) pipe.write(byteArrayOf(1))
            val executor = Executors.newSingleThreadExecutor(); val started = CountDownLatch(1)
            try {
                val pending = executor.submit<Boolean> { started.countDown(); try {
                    if (writer) pipe.write(byteArrayOf(2)) else pipe.read(ByteArray(1), 0, 1)
                    false
                } catch (_: IOException) { true } }
                assertTrue(started.await(1, TimeUnit.SECONDS)); pipe.close()
                assertTrue(pending.get(1, TimeUnit.SECONDS)); assertEquals(0, pipe.bufferedBytes)
            } finally { pipe.close(); executor.shutdownNow() }
        }
    }

    @Test fun timeoutIsTerminalAndFinishDrainsBeforeEof() {
        val stalled = BoundedAudioPipe(4, 25)
        assertFailsWith<IOException> { stalled.read(ByteArray(1), 0, 1) }
        assertFailsWith<IOException> { stalled.write(byteArrayOf(1)) }
        val pipe = BoundedAudioPipe(4); pipe.write(byteArrayOf(7, 8)); pipe.finish()
        assertFailsWith<IOException> { pipe.write(byteArrayOf(9)) }
        val output = ByteArray(4); assertEquals(2, pipe.read(output, 0, 4)); assertEquals(-1, pipe.read(output, 0, 4))
        assertContentEquals(byteArrayOf(7, 8), output.copyOf(2)); pipe.close()
    }
}
