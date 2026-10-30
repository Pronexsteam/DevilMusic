package dev.devilmusic.androidproof

import org.junit.Assert.*
import org.junit.Test

class RhythmVisualsTest {
    @Test fun fastConfirmedBeatsAccentTheNoteWithoutRestartingEveryOuterRipple(){
        val clock=RhythmVisuals()
        val first=RhythmFrame(1_000_000,1_000_000,1_000_000,1f,.9f,180f,1f)
        val start=clock.update(first)
        val second=clock.update(first.copy(positionUs=1_333_333,onsetUs=1_333_333,beatUs=1_333_333))
        assertEquals(.333333f,second.age,.001f)
        assertEquals(1f,second.glint,.001f)
        assertTrue(start.duration>=.95f)
        val next=clock.update(first.copy(positionUs=1_666_666,onsetUs=1_666_666,beatUs=1_666_666))
        assertEquals(0f,next.age,.001f)
    }
    @Test fun denseUncertainAttacksAllowTheOuterWaveToTravel(){
        val clock=RhythmVisuals()
        val first=RhythmFrame(1_000_000,1_000_000,Long.MIN_VALUE,1f,.1f,0f,1f)
        clock.update(first)
        clock.update(first.copy(positionUs=1_100_000,onsetUs=1_100_000))
        val next=clock.update(first.copy(positionUs=1_200_000,onsetUs=1_200_000))
        assertEquals(.2f,next.age,.001f);assertTrue(next.glint>0f)
    }
    @Test fun repeatedUiFramesDoNotRetriggerAndConfirmedBeatIsStrongerThanUncertainAttack(){
        val clock=RhythmVisuals()
        val event=RhythmFrame(1_010_000,1_000_000,1_000_000,.8f,.9f,120f,.5f)
        val beat=clock.update(event)
        val later=clock.update(event.copy(positionUs=1_310_000))
        assertTrue(later.age>beat.age);assertTrue(later.ambient<beat.ambient)
        assertEquals(beat.strength,later.strength,0f)
        val uncertain=RhythmVisuals().update(event.copy(beatUs=Long.MIN_VALUE,confidence=.1f,bpm=0f))
        assertTrue(beat.strength>uncertain.strength)
    }
    @Test fun noEventsMeansNoMotionEvenIfAudioIsLoud(){
        val clock=RhythmVisuals()
        val v=clock.update(RhythmFrame(1_000_000,Long.MIN_VALUE,Long.MIN_VALUE,0f,0f,0f,1f))
        assertEquals(0f,v.strength,0f);assertEquals(0f,v.glint,0f);assertEquals(0f,v.ambient,0f)
    }
    @Test fun pauseAndStaleSamplesStopBothSurfaces(){
        val clock=RhythmVisuals();clock.update(RhythmFrame(100,0,0,1f,1f,120f,1f))
        assertEquals(VisualPulse.REST,clock.update(RhythmFrame.QUIET))
    }
    @Test fun weakOffbeatDoesNotRestartTheMainWave(){
        val clock=RhythmVisuals();val first=RhythmFrame(1_010_000,1_000_000,1_000_000,1f,.9f,120f,1f)
        clock.update(first)
        val second=clock.update(first.copy(positionUs=1_260_000,onsetUs=1_250_000,strength=.3f))
        assertEquals(.26f,second.age,.001f);assertTrue(second.glint>0f)
    }
}
