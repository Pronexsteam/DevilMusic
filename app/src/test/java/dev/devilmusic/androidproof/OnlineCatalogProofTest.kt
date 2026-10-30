// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
class OnlineCatalogProofTest {
    @Test fun explicitlyRequestedPublicSearchProof() {
        assumeTrue("Live public search is opt-in",System.getenv("DEVILMUSIC_PUBLIC_SEARCH_PROOF")=="1")
        val results=NewPipeCatalog.search("Blender Wing It open movie")
        assertTrue(results.isNotEmpty())
        assertTrue(results.any {it.id=="u9lj-c29dxI"})
        println("public search: ${results.size} tracks; requested open film present; audio requests=0")
    }
}
