package dev.devilmusic.androidproof
import org.junit.Assert.*
import org.junit.Test
class PlaybackToolPolicyTest {
    @Test fun playNextUsesActualShuffleOrderAndPreservesAllOtherSongs(){
        assertArrayEquals(intArrayOf(4,1,0,3,2),PlayNextPolicy.shuffled(listOf(4,0,3,1,2),4,1))
        assertArrayEquals(intArrayOf(4,3,1,2,0),PlayNextPolicy.shuffled(listOf(4,0,3,1,2),2,0))
        assertNull(PlayNextPolicy.shuffled(listOf(1,1,2),1,2))
        assertNull(PlayNextPolicy.shuffled(listOf(0,1,2),1,1))
    }
    @Test fun timerUsesMonotonicDeadlineAndOnlyFadesAtTheEnd(){
        val p=SleepPlan();p.set(1,false,9000);assertEquals(69000,p.deadline)
        assertEquals(1f,p.gain(40000),0f);assertEquals(.5f,p.gain(67500),.001f)
        assertFalse(p.expired(68999));assertTrue(p.expired(69000));p.cancel();assertFalse(p.expired(100000));assertEquals(1f,p.gain(100000),0f)
    }
    @Test fun endOfTrackAndTimedModesReplaceEachOther(){
        val p=SleepPlan();p.set(0,true,1);assertTrue(p.endOfTrack);assertEquals(0,p.deadline)
        p.set(15,false,2);assertFalse(p.endOfTrack);assertEquals(900002,p.deadline)
    }
    @Test fun undoFindsSurvivingNeighboursAfterQueueChanges(){
        assertEquals(1,QueueUndoPolicy.insertion(listOf("new","after"),"before","after",99))
        assertEquals(2,QueueUndoPolicy.insertion(listOf("new","before"),"before","gone",0))
        assertEquals(0,QueueUndoPolicy.insertion(emptyList(),"gone","gone",99))
    }
}
