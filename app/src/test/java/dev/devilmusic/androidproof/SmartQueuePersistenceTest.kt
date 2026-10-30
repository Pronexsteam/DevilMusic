package dev.devilmusic.androidproof
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
import kotlin.random.Random

class SmartQueuePersistenceTest {
    @Test fun unreadableQueueMustNotBeTreatedAsEmptyAndSavedOver(){
        val dir=Files.createTempDirectory("damaged-queue").toFile()
        try {
            val original="{interrupted JSON";File(dir,"queue.json").writeText(original)
            try{MusicStateStore(dir).queue();fail()}catch(_:IllegalStateException){}
            val gate=QueuePersistenceGate();gate.loaded(false);assertFalse(gate.canSave())
            assertEquals(original,File(dir,"queue.json").readText())
            gate.edited();assertTrue(gate.canSave())
        }finally{dir.deleteRecursively()}
    }
    @Test fun tenThousandTracksRetainShuffleAndCheapPositionCheckpoints(){
        val dir=Files.createTempDirectory("large-queue").toFile()
        try {
            val tracks=(0 until 10000).map {SavedTrack("youtube:"+it.toString().padStart(11,'0'),"Song $it","Artist ${it%50}",null)}
            val plan=ShufflePlan.fresh(tracks.map {it.id},tracks[5050].id,Random(14))
            val queue=SavedQueue(tracks,tracks[5050].id,1234,true,2,1.25f,plan);val store=MusicStateStore(dir)
            store.save(queue);val full=File(dir,"queue.json").readBytes();val lib=File(dir,"library.json").readBytes()
            repeat(15){store.save(queue.copy(currentId=plan[it+1],position=9000L+it))}
            assertArrayEquals(full,File(dir,"queue.json").readBytes());assertArrayEquals(lib,File(dir,"library.json").readBytes())
            assertTrue(File(dir,"playback-position.json").length()<512)
            val loaded=MusicStateStore(dir).queue()!!
            assertEquals(10000,loaded.tracks.size);assertEquals(plan,loaded.shuffleOrder);assertEquals(plan[15],loaded.currentId)
            assertEquals(9014L,loaded.position);assertEquals(1.25f,loaded.speed,0f);assertEquals(2,loaded.repeat)
        }finally{dir.deleteRecursively()}
    }
    @Test fun staleCursorCannotOverwriteReplacementQueueAndLegacyStateStillLoads(){
        val dir=Files.createTempDirectory("queue-generation").toFile()
        try {
            val t=SavedTrack("youtube:abcdefghijk","Song","Artist",null);val store=MusicStateStore(dir)
            val a=SavedQueue(listOf(t),t.id,55,false,0,1f);store.save(a)
            val stale=File(dir,"playback-position.json").readBytes()
            store.save(a.copy(repeat=2,position=66));File(dir,"playback-position.json").writeBytes(stale)
            assertEquals(66L,MusicStateStore(dir).queue()!!.position)
            File(dir,"queue.json").writeText("""{"tracks":[{"id":"youtube:abcdefghijk","title":"Legacy"}],"currentId":"youtube:abcdefghijk","position":77,"shuffle":true}""")
            val legacy=MusicStateStore(dir).queue()!!;assertEquals(77L,legacy.position);assertTrue(legacy.shuffleOrder.isEmpty());assertTrue(legacy.shuffle)
        }finally{dir.deleteRecursively()}
    }
}
