package dev.devilmusic.androidproof
import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files

class PlaylistStoreTest {
    private val track=SavedTrack("youtube:dQw4w9WgXcQ","Track","Artist",null)
    @Test fun editsSurviveRestartAndDoNotTouchOtherState(){
        val root=Files.createTempDirectory("playlists").toFile()
        try {
            val unrelated=java.io.File(root,"listening-taste.json").apply {writeText("keep taste")}
            val store=PlaylistStore(root);val p=store.create("Evening",listOf(track));store.add(p.id,listOf(track.copy(id="download:dQw4w9WgXcQ")))
            assertEquals(1,PlaylistStore(root).all().single().tracks.size)
            store.rename(p.id,"Night");assertEquals("Night",PlaylistStore(root).all().single().title)
            store.remove(p.id,track.id);assertTrue(store.all().single().tracks.isEmpty());store.delete(p.id)
            assertTrue(store.all().isEmpty());assertEquals("keep taste",unrelated.readText())
        }finally{root.deleteRecursively()}
    }
    @Test fun failedEditDoesNotReplaceExistingFile(){
        val root=Files.createTempDirectory("playlists").toFile()
        try {val store=PlaylistStore(root);val p=store.create("Keep");val before=java.io.File(root,"playlists.json").readBytes()
            assertThrows(IllegalArgumentException::class.java){store.rename(p.id,"  ")}
            assertArrayEquals(before,java.io.File(root,"playlists.json").readBytes())
            java.io.File(root,"playlists.json").writeText("broken")
            assertThrows(Exception::class.java){store.create("New")};assertEquals("broken",java.io.File(root,"playlists.json").readText())
        }finally{root.deleteRecursively()}
    }
}
