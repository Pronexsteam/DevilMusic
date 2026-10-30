package dev.devilmusic.androidproof

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class PcmRhythmTest {
    private fun analyze(seconds:Double=8.0,gain:Double=.55,hz:Double=2400.0,chunk:Int=480,opposite:Boolean=false,steady:Boolean=false,rate:Int=48000):List<RhythmFrame>{
        val frames=(seconds*rate).toInt();val meter=PcmRhythm();val result=ArrayList<RhythmFrame>()
        var start=0
        while(start<frames){val n=min(chunk,frames-start);val b=ByteBuffer.allocate(n*4).order(ByteOrder.LITTLE_ENDIAN)
            for(i in 0 until n){val t=(start+i).toDouble()/rate;val phase=t%.5
                val envelope=if(steady)1.0 else if(phase<.07)sin(PI*phase/.07).pow(2) else 0.0
                val value=(32767*gain*envelope*sin(2*PI*hz*t)).roundToInt().toShort()
                b.putShort(value);b.putShort(if(opposite)(-value).toShort() else value)
            };b.flip();val original=b.array().copyOf();val pts=start*1_000_000L/rate
            meter.capture(b.asReadOnlyBuffer(),pts,rate,2);assertArrayEquals(original,b.array());assertEquals(0,b.position())
            // Observe behind the decode cursor, as the audio sink does.
            val until=(start+n)*1_000_000L/rate-50_000
            if(until>=0)result.add(meter.at(until))
            start+=n
        };return result
    }
    private fun events(frames:List<RhythmFrame>)=frames.map {it.onsetUs}.filter {it!=Long.MIN_VALUE}.distinct()
    @Test fun highFrequencyAttacksLockToActualPulseAndOppositeStereoDoesNotCancel(){
        val frames=analyze(opposite=true);val events=events(frames)
        assertTrue("events=${events.size}",events.size>=12)
        val locked=frames.filter {it.confidence>=.58 && it.bpm>0}
        assertTrue("No confident pulse",locked.isNotEmpty())
        assertEquals(120.0,locked.last().bpm.toDouble(),4.0)
        assertTrue(frames.any {it.beatUs!=Long.MIN_VALUE})
    }
    @Test fun inputGainDoesNotChangeAttackTiming(){
        val quiet=events(analyze(gain=.10));val loud=events(analyze(gain=.75))
        assertEquals(loud.size,quiet.size)
        quiet.zip(loud).forEach {(a,b)->assertTrue("$a vs $b",abs(a-b)<=11_000)}
    }
    @Test fun pulseWorksAcrossBassTrebleAndCommonSampleRates(){
        for((rate,hz) in listOf(44100 to 80.0,48000 to 6000.0,96000 to 1200.0)){
            val frames=analyze(rate=rate,hz=hz);val locked=frames.filter {it.confidence>=.58f && it.bpm>0}
            assertTrue("No pulse at $rate / $hz",locked.isNotEmpty())
            assertEquals("$rate / $hz",120.0,locked.last().bpm.toDouble(),5.0)
        }
    }
    @Test fun decoderChunkBoundariesDoNotChangeDetectedEvents(){
        val small=events(analyze(chunk=173));val large=events(analyze(chunk=1920))
        // Different UI observation cadence can skip the very first/last event, so compare the interior.
        val a=small.filter {it in 1_000_000..6_000_000};val b=large.filter {it in 1_000_000..6_000_000}
        assertEquals(a.size,b.size);a.zip(b).forEach {(x,y)->assertTrue("Timestamp rounding: $x / $y",abs(x-y)<=1)}
    }
    @Test fun steadyToneAndSilenceDoNotInventAMetronome(){
        val constant=analyze(steady=true)
        assertTrue(events(constant).size<=1);assertTrue(constant.all {it.bpm==0f && it.beatUs==Long.MIN_VALUE})
        val silent=analyze(gain=0.0);assertTrue(events(silent).isEmpty());assertTrue(silent.all {it.energy==0f})
    }
    @Test fun historyDoesNotPresentDecodedFutureAndResetRemovesOldBeats(){
        val meter=PcmRhythm();val b=ByteBuffer.allocate(4800*2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(4800){b.putShort((10000*sin(it*.35)).toInt().toShort())};b.flip()
        meter.capture(b,5_000_000,48000,1)
        assertEquals(RhythmFrame.QUIET,meter.at(4_000_000));assertTrue(meter.at(5_050_000).energy>0)
        meter.clear();assertEquals(RhythmFrame.QUIET,meter.at(5_050_000))
    }
    @Test fun trackerDropsConfidenceAfterSilence(){
        val tracker=PulseTracker();var next=0L
        for(i in 0..800){val t=i*10_000L;if(t>=next){tracker.onset(t,1f);next+=500_000};tracker.frame(t)}
        assertEquals(120f,tracker.bpm,3f)
        for(i in 801..1000)tracker.frame(i*10_000L)
        assertEquals(0f,tracker.confidence,0f);assertEquals(0f,tracker.bpm,0f)
    }
    @Test fun trackerCanFollowATempoChange(){
        val tracker=PulseTracker();var next=0L
        for(i in 0..2000){val t=i*10_000L;if(t>=next){tracker.onset(t,1f);next+=if(t<8_000_000)500_000 else 666_667};tracker.frame(t)}
        assertTrue(tracker.confidence>.58f);assertEquals(90f,tracker.bpm,4f)
    }
}
