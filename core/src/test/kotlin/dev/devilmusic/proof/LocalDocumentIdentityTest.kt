package dev.devilmusic.proof

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalDocumentIdentityTest {
    @Test fun restoredSessionItemUsesItsExactGrantedIdWithoutLocalConfiguration() {
        val uri = "content://documents/audio%3A42"
        assertEquals(uri, grantedDocumentIdentity(uri, null, setOf(uri)))
    }
    @Test fun remoteRevokedAndPrefixOnlyMatchesAreRejected() {
        val uri = "content://documents/audio%3A42"
        assertNull(grantedDocumentIdentity(uri, null, emptySet()))
        assertNull(grantedDocumentIdentity("${uri}5", null, setOf(uri)))
        assertNull(grantedDocumentIdentity("https://example.invalid", null, setOf("https://example.invalid")))
    }
}
