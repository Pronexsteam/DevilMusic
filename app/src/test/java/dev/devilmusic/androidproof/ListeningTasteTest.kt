package dev.devilmusic.androidproof

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ListeningTasteTest {
    private val song=SavedTrack("youtube:abcdefghijk","Song","Artist",null)
    @Test fun countsActualPlaybackAndNotBufferingOrSeekPosition() {
        val events=mutableListOf<TasteSignal>();val meter=ListeningMeter {_,s,_->events.add(s)}
        meter.sample(song,false,180_000,0,1)
        meter.sample(song,true,180_000,60_000,2)
        meter.sample(song,false,180_000,70_000,3)
        meter.sample(song,false,180_000,170_000,4)
        assertTrue(events.isEmpty())
        meter.sample(song,true,180_000,180_000,5)
        meter.sample(song,true,180_000,200_000,6)
        assertEquals(listOf(TasteSignal.HEARD),events)
        meter.sample(song,true,180_000,210_000,7)
        assertEquals(1,events.size)
    }
    @Test fun errorsAndAutomaticTransitionsAreNotDislikes() {
        fun signals(manual:Boolean,error:Boolean):List<TasteSignal> {
            val events=mutableListOf<TasteSignal>();val meter=ListeningMeter {_,s,_->events.add(s)}
            meter.sample(song,true,180_000,0,1)
            meter.sample(song,false,180_000,10_000,2,error)
            meter.finish(10_000,3,manual);return events
        }
        assertEquals(listOf(TasteSignal.EARLY_SKIP),signals(true,false))
        assertTrue(signals(true,true).isEmpty());assertTrue(signals(false,false).isEmpty())
    }
    @Test fun completedAndRepeatSessionsCountOnceEach() {
        val events=mutableListOf<TasteSignal>();val meter=ListeningMeter {_,s,_->events.add(s)}
        repeat(2){i->val start=i*40_000L;meter.sample(song,true,40_000,start,1);meter.sample(song,true,40_000,start+30_000,2);meter.finish(start+40_000,3,false)}
        assertEquals(listOf(TasteSignal.HEARD,TasteSignal.COMPLETED,TasteSignal.HEARD,TasteSignal.COMPLETED),events)
    }
    @Test fun partialTinyListenAndStoppingAppDoNotBecomeDislikes() {
        val events=mutableListOf<TasteSignal>();val meter=ListeningMeter {_,s,_->events.add(s)}
        meter.sample(song,true,180_000,0,1);meter.finish(500,2,true)
        meter.sample(song,true,180_000,1000,3);meter.finish(10_000,4,false)
        assertTrue(events.isEmpty())
    }
    @Test fun finishingMostOfAShortSongIsNotAnEarlySkip() {
        val events=mutableListOf<TasteSignal>();val meter=ListeningMeter {_,s,_->events.add(s)}
        meter.sample(song,true,20_000,0,1);meter.finish(16_000,2,true)
        assertEquals(listOf(TasteSignal.HEARD,TasteSignal.COMPLETED),events)
    }
    @Test fun profileIsSeparateAndResetPreservesLibraryAndQueue() {
        val dir=Files.createTempDirectory("taste-state").toFile()
        try {
            val state=MusicStateStore(dir);val queue=SavedQueue(listOf(song),song.id,22000,true,2,1.5f);state.save(queue)
            val taste=ListeningTaste(dir);taste.record(song,TasteSignal.HEARD,1000);taste.record(song.copy(id="download:abcdefghijk"),TasteSignal.COMPLETED,1100)
            taste.feedback(song,1,1200)
            val entry=ListeningTaste(dir).read().single();assertEquals(song.id,entry.track.id);assertTrue(entry.good>1);assertEquals(1,entry.feedback)
            assertEquals(queue,state.queue());assertEquals(listOf(song),state.library())
            taste.reset();assertTrue(taste.read().isEmpty());assertEquals(queue,state.queue())
        }finally{dir.deleteRecursively()}
    }
    @Test fun oldSignalsFadeButExplicitPreferencePersists() {
        val entry=TasteEntry(song,8.0,0.0,1000,1000,1)
        val fresh=HomeRanking.weight(entry,1000);val aged=HomeRanking.weight(entry,1000+180*ListeningTaste.DAY)
        assertTrue(aged<fresh);assertTrue(aged>=3.0)
    }
}
