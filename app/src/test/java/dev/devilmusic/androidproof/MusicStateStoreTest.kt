package dev.devilmusic.androidproof
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class MusicStateStoreTest {
    @Test fun saveToLibraryDoesNotReplaceQueueOrPlaybackSettings(){
        val dir=Files.createTempDirectory("library-only-test").toFile()
        try {
            val store=MusicStateStore(dir);val first=SavedTrack("youtube:abcdefghijk","First","Artist",null)
            store.save(SavedQueue(listOf(first),first.id,777,true,2,1.25f));val queue=java.io.File(dir,"queue.json").readBytes()
            val other=first.copy(id="youtube:12345678901",title="Other")
            store.addToLibrary(other);store.addToLibrary(other)
            assertEquals(2,store.library().size);assertArrayEquals(queue,java.io.File(dir,"queue.json").readBytes())
        }finally{dir.deleteRecursively()}
    }
    @Test fun legacyAlbumPinsAndNewMixPinsSurviveTogether(){
        val dir=Files.createTempDirectory("music-pins-test").toFile()
        try {
            java.io.File(dir,"pins.json").writeText("""{"albums":[{"id":"MPREabcdefgh","title":"Old album","subtitle":"Artist"}]}""")
            val store=MusicStateStore(dir)
            val mix=MusicCard("VLabcdefghijk","playlist","My mix","Artist",null)
            assertTrue(store.toggleAlbum(mix))
            val pins=MusicStateStore(dir).pinnedAlbums()
            assertEquals(listOf("playlist","album"),pins.map {it.kind})
            assertEquals("Old album",pins.last().title)
            assertFalse(store.toggleAlbum(mix));assertEquals("album",store.pinnedAlbums().single().kind)
        }finally {dir.deleteRecursively()}
    }
    @Test fun historyAndPinsSurviveReopenWithoutChangingLibraryOrPlaybackSettings() {
        val dir=Files.createTempDirectory("music-personal-test").toFile()
        try {
            val store=MusicStateStore(dir)
            val a=SavedTrack("youtube:abcdefghijk","A","Artist",null)
            val b=a.copy(id="youtube:12345678901",title="B")
            val queue=SavedQueue(listOf(a,b),b.id,41000,true,2,1.25f)
            store.save(queue)
            val libraryBefore=java.io.File(dir,"library.json").readBytes()
            val queueBefore=java.io.File(dir,"queue.json").readBytes()
            assertTrue(store.recent().isEmpty())
            store.recordPlayed(a);store.recordPlayed(b);store.recordPlayed(a)
            store.recordPlayed(a.copy(id="https://secret.googlevideo.com/stream?token=x"))
            val album=MusicCard("MPREabcdefgh","album","Album","Artist","https://secret.googlevideo.com/stream?token=x")
            assertTrue(store.toggleAlbum(album))
            val reopened=MusicStateStore(dir)
            assertEquals(listOf(a,b),reopened.recent())
            assertEquals(album.copy(artwork=null),reopened.pinnedAlbums().single())
            assertFalse(reopened.toggleAlbum(album));assertTrue(reopened.pinnedAlbums().isEmpty())
            assertArrayEquals(libraryBefore,java.io.File(dir,"library.json").readBytes())
            assertArrayEquals(queueBefore,java.io.File(dir,"queue.json").readBytes())
            assertEquals(queue,reopened.queue())
        }finally {dir.deleteRecursively()}
    }
    @Test fun historyRemainsBoundedAndNewestFirst() {
        val dir=Files.createTempDirectory("music-history-test").toFile()
        try {
            val store=MusicStateStore(dir)
            repeat(110){store.recordPlayed(SavedTrack("youtube:"+it.toString().padStart(11,'0'),"Track $it","",null))}
            assertEquals(100,store.recent().size)
            assertEquals("Track 109",store.recent().first().title)
            assertEquals("Track 10",store.recent().last().title)
        }finally {dir.deleteRecursively()}
    }
    @Test fun largeLibraryAndQueueRoundTripWithoutSignedSources() {
        val dir=Files.createTempDirectory("music-state-test").toFile()
        try {
            val store=MusicStateStore(dir)
            val tracks=(0 until 5000).map {SavedTrack("youtube:"+it.toString().padStart(11,'0'),"Title "+"a".repeat(480),"Artist "+"b".repeat(100),"https://i.ytimg.com/vi/abcdefghijk/default.jpg")}
            store.save(SavedQueue(tracks,tracks[1420].id,43567,true,2,1.5f))
            assertTrue(java.io.File(dir,"library.json").length()>2*1024*1024)
            val restored=store.queue()!!;assertEquals(tracks,restored.tracks);assertEquals(43567,restored.position);assertEquals(tracks[1420].id,restored.currentId);assertEquals(1.5f,restored.speed)
            store.save(SavedQueue(emptyList(),null,0,false,0,1f));assertEquals(5000,store.library().size);assertTrue(store.queue()!!.tracks.isEmpty())
            store.save(SavedQueue(listOf(SavedTrack("https://secret.googlevideo.com/stream?token=secret","bad","bad",null)),null,0,false,0,1f))
            assertFalse(java.io.File(dir,"queue.json").readText().contains("secret"))
        }finally {dir.deleteRecursively()}
    }
    @Test fun pendingRestoreCannotSaveEmptyQueueOrOverwriteUserSelection() {
        val gate=QueuePersistenceGate();assertFalse(gate.canSave());assertTrue(gate.canRestore())
        // onDestroy before read completion: caller must not save an initial empty player.
        assertFalse(gate.canSave());gate.edited();assertTrue(gate.canSave());assertFalse(gate.canRestore());gate.loaded();assertTrue(gate.canSave())
        val restored=QueuePersistenceGate();restored.loaded();assertTrue(restored.canSave());assertTrue(restored.canRestore())
    }
    @Test fun orphanTruncatedAndInvalidDownloadFilesAreNeverReady() {
        val dir=Files.createTempDirectory("music-file-test").toFile();val id="abcdefghijk"
        try {
            val audio=java.io.File(dir,"$id.audio");audio.writeText("abcd")
            assertNull(verifiedDownloadFile(dir,"download:$id"))
            java.io.File(dir,"$id.json").writeText("""{"id":"download:$id","bytes":10}""")
            assertNull(verifiedDownloadFile(dir,"download:$id"))
            audio.writeText("abcdefghij");assertEquals(audio,verifiedDownloadFile(dir,"download:$id"))
            assertNull(verifiedDownloadFile(dir,"download:../$id"))
            java.io.File(dir,"$id.json").writeText("broken");assertNull(verifiedDownloadFile(dir,"download:$id"))
        }finally {dir.deleteRecursively()}
    }
}
