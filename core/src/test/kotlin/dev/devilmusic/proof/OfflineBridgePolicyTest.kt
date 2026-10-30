package dev.devilmusic.proof

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OfflineBridgePolicyTest {
    @Test fun onlyExactFixtureIsAllowed() {
        val policy = OfflineBridgePolicy()
        assertNull(policy.check(1, 1, "GET", "proof://fixtures/catalog", true))
        assertEquals("DENIED", policy.check(1, 2, "GET", "https://example.invalid/", true))
        assertEquals("DENIED", policy.check(1, 3, "POST", "proof://fixtures/catalog", true))
        assertEquals("DENIED", policy.check(1, 4, "GET", "proof://fixtures/catalog?token=x", true))
    }
    @Test fun badVersionFieldsAndReplayAreRejected() {
        val policy = OfflineBridgePolicy()
        assertEquals("BAD_REQUEST", policy.check(2, 1, "GET", "proof://fixtures/catalog", true))
        assertEquals("BAD_REQUEST", policy.check(1, 2, "GET", "proof://fixtures/catalog", false))
        assertNull(policy.check(1, 3, "GET", "proof://fixtures/catalog", true))
        assertEquals("BAD_REQUEST", policy.check(1, 3, "GET", "proof://fixtures/catalog", true))
    }
    @Test fun attemptsAreBoundedIncludingDenials() {
        val policy = OfflineBridgePolicy()
        repeat(8) { assertEquals("DENIED", policy.check(1, it + 1, "GET", "denied", true)) }
        assertEquals("LIMIT", policy.check(1, 1, "GET", "proof://fixtures/catalog", true))
    }
}
