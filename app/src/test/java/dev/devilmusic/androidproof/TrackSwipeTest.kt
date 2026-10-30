package dev.devilmusic.androidproof
import org.junit.Assert.*
import org.junit.Test
class TrackSwipeTest {
    @Test fun horizontalTitlesNavigateButVerticalScrollAndTapsDoNot(){
        assertEquals(1,TrackSwipe.direction(-80f,12f,48f))
        assertEquals(-1,TrackSwipe.direction(80f,-12f,48f))
        assertEquals(0,TrackSwipe.direction(12f,2f,48f))
        assertEquals(0,TrackSwipe.direction(80f,95f,48f))
    }
}
