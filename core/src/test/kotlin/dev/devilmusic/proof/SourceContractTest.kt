package dev.devilmusic.proof

import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.test.*

class SourceContractTest {
    @Test fun desktopLocalIdsDoNotBecomePathsOrQueries() {
        assertEquals("local:" + "a".repeat(64), TrackId.Local.parse("local:" + "a".repeat(64)).value)
        for (bad in listOf("local:../song", "local:" + "A".repeat(64), "https://example.test/song")) {
            assertFailsWith<IllegalArgumentException> { TrackId.Local.parse(bad) }
        }
        assertFailsWith<IllegalArgumentException> { TrackId.YouTube.parse("local:" + "a".repeat(64)) }
    }

    @Test fun revokedLocalGrantNeverFallsBackToOnline() {
        var onlineCalls = 0
        val local = LocalDocuments { CompletableFuture.failedFuture(SourceFailure(FailureKind.PERMISSION_REVOKED)) }
        val online = OnlineSourceResolver { _, _ -> onlineCalls++; CompletableFuture.failedFuture(AssertionError()) }
        val error = assertFailsWith<CompletionException> {
            SourceRouter(local, online).resolve(TrackId.Local.parse("local:" + "a".repeat(64))).join()
        }
        assertEquals(FailureKind.PERMISSION_REVOKED, (error.cause as SourceFailure).kind)
        assertEquals(0, onlineCalls)
    }

    @Test fun newerSelectionAndCancellationInvalidateOldWork() {
        val gate = SelectionGate()
        val first = gate.select()
        val second = gate.select()
        assertFalse(gate.isCurrent(first)); assertTrue(gate.isCurrent(second))
        gate.cancel(); assertFalse(gate.isCurrent(second))
    }

    @Test fun signedHeadersAreCopiedAndRedactedAndHaveNoInjection() {
        val input = mutableMapOf("Authorization" to "private-token")
        val source = PlayableSource.RemoteLease(URI("https://example.test/audio?signature=secret"), input, 100_000, "audio/mp4")
        input["Authorization"] = "changed"
        assertEquals("private-token", source.headersForRequest()["Authorization"])
        assertFalse(source.toString().contains("secret")); assertFalse(source.toString().contains("private-token"))
        assertFailsWith<IllegalArgumentException> {
            PlayableSource.RemoteLease(URI("https://example.test/a"), mapOf("X" to "a\r\nInjected: true"), 100_000, "audio/mp4")
        }
        assertFailsWith<IllegalArgumentException> {
            PlayableSource.RemoteLease(URI("http://example.test/a"), emptyMap(), 100_000, "audio/mp4")
        }
    }

    @Test fun expiryIsExplicitAndOnlyRemoteSourcesAreRefreshed() {
        val source = PlayableSource.RemoteLease(URI("https://example.test/a"), emptyMap(), 100_000, "audio/mp4")
        assertFalse(PlaybackPolicy.shouldRefreshBeforePlay(source, 10_000))
        assertTrue(PlaybackPolicy.shouldRefreshBeforePlay(source, 90_000))
        assertTrue(PlaybackPolicy.shouldRefreshBeforePlay(source, 100_000))
        assertFalse(PlaybackPolicy.shouldRefreshBeforePlay(PlayableSource.LocalDocument(URI("content://documents/audio/1")), 100_000))
    }

    @Test fun routingKeepsRefreshReasonAndDoesNotRunAnExtractorForLocalPlayback() {
        var receivedReason: ResolveReason? = null
        var localCalls = 0
        val router = SourceRouter(LocalDocuments { localCalls++; CompletableFuture.completedFuture(PlayableSource.LocalDocument(URI("content://documents/1"))) },
            OnlineSourceResolver { _, reason -> receivedReason = reason; CompletableFuture.completedFuture(PlayableSource.RemoteLease(URI("https://example.test/a"), emptyMap(), 100_000, "audio/mp4")) })
        router.resolve(TrackId.YouTube.parse("abcdefghijk"), ResolveReason.LEASE_EXPIRED).join()
        assertEquals(ResolveReason.LEASE_EXPIRED, receivedReason); assertEquals(0, localCalls)
        router.resolve(TrackId.Local.parse("local:" + "b".repeat(64))).join()
        assertEquals(1, localCalls)
    }

    @Test fun speedBoundsKeepPitchIndependent() {
        assertEquals(.5f, PlaybackPolicy.speed(-1f)); assertEquals(2f, PlaybackPolicy.speed(8f))
        assertEquals(1f, PlaybackPolicy.speed(Float.NaN)); assertEquals(1.5f, PlaybackPolicy.speed(1.5f))
        assertEquals(1f, PlaybackPolicy.PITCH)
    }
}
