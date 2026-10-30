package dev.devilmusic.androidproof
import com.grack.nanojson.JsonParser
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class MusicCatalogTest {
    @Test fun homeKeepsRealKindsAndIgnoresMenuEntries() {
        val root=JsonParser.`object`().from("""{"contents":{"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Albums"}]}}},"contents":[{"musicTwoRowItemRenderer":{"title":{"simpleText":"Real album"},"navigationEndpoint":{"browseEndpoint":{"browseId":"MPRE_test"}},"overlay":{"videoId":"abcdefghijk"}}},{"musicTwoRowItemRenderer":{"title":{"simpleText":"Artist"},"navigationEndpoint":{"browseEndpoint":{"browseId":"UC_test"}}}},{"musicTwoRowItemRenderer":{"title":{"simpleText":"Song"},"navigationEndpoint":{"watchEndpoint":{"videoId":"abcdefghijk"}},"thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://lh3.googleusercontent.com/public-cover"}]}}}}}],"menu":{"musicTwoRowItemRenderer":{"title":{"simpleText":"Do not show"},"navigationEndpoint":{"watchEndpoint":{"videoId":"abcdefghijk"}}}}}}}""")
        val shelf=MusicCatalogParser.parse(root).single()
        assertEquals("Albums",shelf.title);assertEquals(listOf("album","artist","song"),shelf.items.map {it.kind})
        assertEquals("https://lh3.googleusercontent.com/public-cover",shelf.items.last().artwork)
    }
    @Test fun invalidBrowseAndArtworkStayRejected() {
        for(id in listOf("https://evil","UC../file","","FEmusic_library_landing"))assertFalse(MusicCatalogParser.validBrowse(id))
        for(url in listOf("https://lh3.googleusercontent.com.evil/a","http://lh3.googleusercontent.com/a","https://user@lh3.googleusercontent.com/a"))assertFalse(NewPipeCatalog.validArtwork(url))
    }
    @Test fun publicHomeProofOnlyWhenExplicitlyEnabled() {
        assumeTrue(System.getenv("DEVILMUSIC_PUBLIC_MUSIC_PROOF")=="1")
        val shelves=MusicCatalog.browse()
        assertTrue("No music shelves parsed",shelves.isNotEmpty())
        println("Music home: ${shelves.size} shelves; ${shelves.sumOf {it.items.size}} cards; kinds=${shelves.flatMap {it.items}.map {it.kind}.distinct()}")
    }
    @Test fun publicAlbumAndArtistProofOnlyWhenExplicitlyEnabled() {
        assumeTrue(System.getenv("DEVILMUSIC_MUSIC_BROWSE_PROOF")=="1")
        val album=MusicCatalog.browse().flatMap {it.items}.first {it.kind=="album"}
        val albumItems=MusicCatalog.browse(album.id).flatMap {it.items}
        assertTrue("Album returned no songs",albumItems.any {it.kind=="song"})
        val artist=MusicCatalog.search("Daft Punk","artists").flatMap {it.items}.first {it.kind=="artist"}
        val artistItems=MusicCatalog.browse(artist.id).flatMap {it.items}
        assertTrue("Artist page empty",artistItems.isNotEmpty())
        println("Music browse: album ${album.id}, songs=${albumItems.count {it.kind=="song"}}; artist ${artist.id}, cards=${artistItems.size}; source=real public metadata")
    }
}
