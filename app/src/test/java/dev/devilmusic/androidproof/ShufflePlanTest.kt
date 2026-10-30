package dev.devilmusic.androidproof
import androidx.media3.exoplayer.source.ShuffleOrder
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ShufflePlanTest {
    @Test fun fullNativeTraversalHasNoRepeatsAtLargeSizesAndPreviousIsInverse(){
        for(size in listOf(1,1420,5000,10000)){
            val ids=(0 until size).map {"track:$it"};val current=ids[size/2]
            val started=System.nanoTime();val plan=ShufflePlan.fresh(ids,current,Random(17))
            val physical=ids.withIndex().associate {it.value to it.index}
            val native=ShuffleOrder.DefaultShuffleOrder(plan.map {physical.getValue(it)}.toIntArray(),12L)
            val seen=HashSet<Int>();var at=native.firstIndex;var previous=-1
            while(at!=-1){assertTrue(seen.add(at));assertEquals(previous,native.getPreviousIndex(at));previous=at;at=native.getNextIndex(at)}
            assertEquals(size,seen.size);assertEquals(size/2,native.firstIndex);assertEquals(-1,native.getNextIndex(native.lastIndex))
            println("Host shuffle construction + full Media3 traversal: $size tracks, ${(System.nanoTime()-started)/1_000_000} ms (not device timing)")
        }
    }
    @Test fun queueEditsPreserveSurvivorOrderAndInsertOnlyAfterCurrent(){
        val ids=(0 until 10000).map {"track:$it"};val original=ShufflePlan.fresh(ids,ids[42],Random(4))
        val current=original[3000];val removed=original.filterIndexed {i,_->i%31==0 && i!=3000}.toSet()
        val changed=ids.filter {it !in removed}+listOf("new:a","new:b","new:a")
        val restored=ShufflePlan.reconcile(original,changed,current,Random(2))
        assertEquals(changed.toSet(),restored.toSet());assertEquals(restored.size,restored.distinct().size)
        assertEquals(original.filter {it !in removed},restored.filter {!it.startsWith("new:")})
        assertTrue(restored.indexOf("new:a")>restored.indexOf(current));assertTrue(restored.indexOf("new:b")>restored.indexOf(current))
        assertEquals(restored,ShufflePlan.reconcile(restored,changed.reversed(),current,Random(99)))
    }
    @Test fun smallQueuesCorruptOrdersAndExplicitMovesRemainValid(){
        assertTrue(ShufflePlan.fresh(emptyList(),null,Random(0)).isEmpty())
        assertEquals(listOf("a"),ShufflePlan.fresh(listOf("a","a"),"a",Random(0)))
        assertEquals(listOf("a","b","c"),ShufflePlan.reconcile(listOf("bad","a","a","b","c"),listOf("c","b","a"),"b",Random(1)))
        assertEquals(listOf("a","c","b"),ShufflePlan.move(listOf("a","b","c"),"c","b"))
        val original=(0..99).map {"id:$it"}
        assertNotEquals(ShufflePlan.fresh(original,"id:8",Random(1)),ShufflePlan.fresh(original,"id:8",Random(2)))
    }
}
