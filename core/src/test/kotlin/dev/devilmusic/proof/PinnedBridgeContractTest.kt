package dev.devilmusic.proof

import kotlin.test.*

class PinnedBridgeContractTest {
    @Test fun cancelledAndLateResponsesCannotCompleteOrReuseAnId() {
        val budget = PinnedBridgeBudget()
        assertNull(budget.begin(1, 1, 100, 0)); budget.cancel(1)
        assertEquals(PinnedBridgeBudget.Completion.STALE, budget.finish(1, 1))
        assertEquals("BAD_REQUEST", budget.begin(1, 1, 100, 1))
        assertNull(budget.begin(1, 2, 100, 2))
        budget.close(); assertEquals(PinnedBridgeBudget.Completion.STALE, budget.finish(2, 3))
    }
    @Test fun timeoutAndConcurrencyAndLifetimeAreBounded() {
        val budget = PinnedBridgeBudget()
        assertNull(budget.begin(1, 1, 100, 0)); assertNull(budget.begin(1, 2, 100, 0))
        assertEquals("LIMIT", budget.begin(1, 3, 100, 0))
        assertEquals(PinnedBridgeBudget.Completion.TIMEOUT, budget.finish(1, 1500))
        assertEquals(PinnedBridgeBudget.Completion.VALID, budget.finish(2, 1499))
        for (id in 4..8) { assertNull(budget.begin(1, id, 100, 2000)); budget.finish(id, 2001) }
        assertEquals("LIMIT", budget.begin(1, 3, 100, 2002))
        assertEquals("BAD_REQUEST", PinnedBridgeBudget().begin(1, 1, 131073, 0))
    }
    @Test fun identityHeaderAndExactExpirySurviveTheSourceSeam() {
        val id = TrackId.YouTube.parse("abcdefghijk")
        val headers = mutableMapOf("X-Fixture-Audio" to "kept")
        val lease = PinnedFixtureSourceContract.lease(id, id.value, "https://media.fixture.invalid/audio?expire=100", headers, 100_000, "audio/mp4", 10_000)
        headers.clear(); assertEquals("kept", lease.headersForRequest()["X-Fixture-Audio"])
        assertEquals(100_000, lease.expiresAtMs); assertFalse(lease.toString().contains("fixture.invalid"))
        assertFails { PinnedFixtureSourceContract.lease(id, "othertrack1", lease.uri.toString(), emptyMap(), 100_000, "audio/mp4", 10_000) }
        assertFails { PinnedFixtureSourceContract.lease(id, id.value, lease.uri.toString(), emptyMap(), 100_000, "audio/mp4", 90_000) }
        assertFails { PinnedFixtureSourceContract.lease(id, id.value, "https://www.youtube.com/audio", emptyMap(), 100_000, "audio/mp4", 10_000) }
    }
    @Test fun verificationStatesStayDistinctAndStopBeforeAnySource() {
        for ((status, kind) in listOf(2 to FailureKind.VERIFICATION_PENDING, 3 to FailureKind.VERIFICATION_REQUIRED, 9 to FailureKind.PROTECTION_UNKNOWN)) {
            val error = assertFailsWith<SourceFailure> { PinnedFixtureSourceContract.lease(TrackId.YouTube.parse("abcdefghijk"), "abcdefghijk", "invalid", emptyMap(), 1, "audio/mp4", 0, protection=status) }
            assertEquals(kind, error.kind)
        }
    }
}
