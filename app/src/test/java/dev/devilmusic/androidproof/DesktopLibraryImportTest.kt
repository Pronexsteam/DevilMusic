package dev.devilmusic.androidproof
import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files
import java.io.File

class DesktopLibraryImportTest {
    @Test fun roundTripKeepsStablePlaylistIdsWithoutDuplicates(){
        val device="12345678-1234-1234-1234-123456789abc"
        val song=SavedTrack("youtube:abcdefghijk","Song","Artist",null)
        val imported=listOf(ImportedCollection("desktop:playlist:android-$device","Android library",listOf(song)),ImportedCollection("desktop:playlist:favorites","A playlist named favorites",listOf(song)))
        val o=com.grack.nanojson.JsonParser.`object`().from(CollectionExport.encode(listOf(song),imported,device))
        val lists=o.getArray("playlists").map {it as com.grack.nanojson.JsonObject}
        assertEquals(2,lists.size);assertEquals(1,lists.first().getArray("items").size);assertTrue(o.getArray("favorites").isEmpty())
        assertEquals(setOf("android-$device","favorites"),lists.map {it.getString("id")}.toSet())
    }
    @Test fun phoneExportDoesNotTurnLibraryIntoLikesOrExportFileGrants(){
        val source=CollectionExport.encode(listOf(SavedTrack("youtube:abcdefghijk","Song","Artist",null),SavedTrack("download:abcdefghijk","Offline alias","Artist",null),SavedTrack("content://private/document","Private","",null)),emptyList(),"12345678-1234-1234-1234-123456789abc")
        val o=com.grack.nanojson.JsonParser.`object`().from(source)
        assertTrue(o.getArray("favorites").isEmpty());assertFalse(source.contains("content://"));assertFalse(source.contains("taste"))
        assertEquals(1,(o.getArray("playlists")[0] as com.grack.nanojson.JsonObject).getArray("items").size)
    }
    private fun profile(count:Int)= """{"format":"devil-music-profile","version":3,"saved":{"favorites":[${(0 until count).joinToString(","){"""{"kind":"song","id":"${it.toString().padStart(11,'0')}","title":"Song $it","artists":["Artist"]}"""}}],"playlists":[]},"taste":{"entries":{}},"unknownFutureField":true}"""
    @Test fun largeTransferIsIdempotentAndDoesNotTouchTasteOrQueue(){
        val root=Files.createTempDirectory("pc-transfer").toFile()
        try {val taste=File(root,"taste.json").apply {writeText("original taste")};val queue=File(root,"queue.json").apply {writeText("original queue")}
            val bridge=DesktopLibraryImport(root);val preview=DesktopLibraryImport.preview(profile(10000))
            assertEquals(10000,preview.tracks.size);assertEquals(10000,bridge.apply(preview));assertEquals(0,bridge.apply(preview));assertEquals(10000,bridge.collections().single().tracks.size)
            assertEquals("original taste",taste.readText());assertEquals("original queue",queue.readText())
            val before=File(root,"desktop-library.json").readBytes()
            try{bridge.apply(preview.copy(source="{}"));fail()}catch(_:IllegalArgumentException){}
            assertArrayEquals(before,File(root,"desktop-library.json").readBytes())
        }finally{root.deleteRecursively()}
    }
    @Test fun additiveImportsPreserveOldTracksAndRejectFutureFormats(){
        val root=Files.createTempDirectory("pc-merge").toFile()
        try {val b=DesktopLibraryImport(root);b.apply(DesktopLibraryImport.preview(profile(12)));b.apply(DesktopLibraryImport.preview(profile(3)));assertEquals(12,b.collections().single().tracks.size)
            try{DesktopLibraryImport.preview(profile(1).replace("\"version\":3","\"version\":99"));fail()}catch(_:IllegalArgumentException){}
        }finally{root.deleteRecursively()}
    }
}
