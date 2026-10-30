package dev.devilmusic.androidproof
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
class UiPreferencesTest {
 @Test fun historyAndHomeSurviveEachOthersWritesWithoutTouchingLibrary(){
  val root=Files.createTempDirectory("music-ui").toFile();try {
   val library=java.io.File(root,"library.json").apply {writeText("original library")}
   val prefs=UiPreferences(root);prefs.remember("Roxette");prefs.remember("roxette")
   prefs.home(HomeArrangement(listOf("Recent","Pinned"),setOf("Popular")))
   assertEquals(listOf("roxette"),prefs.history());assertEquals(setOf("Popular"),prefs.home().hidden)
   prefs.clearHistory();assertTrue(prefs.history().isEmpty());assertEquals(listOf("Recent","Pinned"),prefs.home().order)
   assertEquals("original library",library.readText())
  }finally{root.deleteRecursively()}
 }
 @Test fun hiddenShelvesAndUnknownNewShelvesKeepStableOrder(){
  val items=listOf("Popular","Recent","New","Pinned").map {MusicShelf(it,emptyList())}
  assertEquals(listOf("Pinned","Recent","New"),HomeArrangement(listOf("Pinned","Recent"),setOf("Popular")).arrange(items).map {it.title})
 }
 @Test fun dragUsesLiveIdsAndRejectsRemovedRows(){
  assertEquals(2 to 0,QueueMove.indices(listOf("c","b","a"),"a","c"))
  assertNull(QueueMove.indices(listOf("b","c"),"a","c"));assertNull(QueueMove.indices(listOf("a"),"a","a"))
 }
}
