package dev.devilmusic.androidproof
import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files
import java.io.File

class FavoritesStoreTest {
    private val track=SavedTrack("youtube:abcdefghijk","Song","Artist",null)
    @Test fun likesAreDurableAndSeparateFromRecommendationSignals(){
        val root=Files.createTempDirectory("likes").toFile()
        try {File(root,"listening-taste.json").writeText("keep");val likes=FavoritesStore(root)
            assertTrue(likes.toggle(track));assertEquals(listOf(track),FavoritesStore(root).all())
            assertFalse(likes.toggle(track.copy(id="download:abcdefghijk")));assertTrue(likes.all().isEmpty())
            assertEquals("keep",File(root,"listening-taste.json").readText())
            File(root,"favorites.json").writeText("broken");assertThrows(Exception::class.java){likes.toggle(track)};assertEquals("broken",File(root,"favorites.json").readText())
        }finally{root.deleteRecursively()}
    }
    @Test fun importedLikeCanBeRemovedWithoutReappearingAfterRestart(){
        val root=Files.createTempDirectory("imported-like").toFile()
        try {File(root,"desktop-library.json").writeText("""{"version":1,"collections":[{"id":"desktop:favorites","title":"PC","tracks":[{"id":"youtube:abcdefghijk","title":"Song","artist":"Artist"}]}]}""")
            val likes=FavoritesStore(root);assertEquals(listOf(track),likes.all());assertFalse(likes.toggle(track));assertTrue(FavoritesStore(root).all().isEmpty())
            assertTrue(likes.toggle(track));assertEquals(listOf(track),FavoritesStore(root).all())
        }finally{root.deleteRecursively()}
    }
}
