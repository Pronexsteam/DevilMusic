package dev.devilmusic.androidproof

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmEnvelopeTest {
    private fun pcm(value:Int,frames:Int=960,channels:Int=2):ByteBuffer = ByteBuffer.allocate(frames*channels*2).order(ByteOrder.LITTLE_ENDIAN).apply {repeat(frames*channels){putShort(value.toShort())};flip()}
    @Test fun captureLeavesAudioAndCursorUnchangedAndWaitsForPresentation(){
        val input=pcm(6000);val copy=input.array().copyOf();val meter=PcmEnvelope()
        meter.capture(input,1_000_000,48000,2)
        assertEquals(0,input.position());assertEquals(copy.size,input.limit());assertArrayEquals(copy,input.array())
        assertEquals(0f,meter.at(900_000),0f)
        assertTrue(meter.at(1_005_000)>.5f)
        assertEquals(0f,meter.at(1_020_000),0f)
    }
    @Test fun silenceAndSeekResetDoNotLeaveStaleEnergy(){
        val meter=PcmEnvelope();meter.capture(pcm(0),0,48000,2);assertEquals(0f,meter.at(1000),0f)
        meter.capture(pcm(10000),20_000,48000,2);assertTrue(meter.at(25_000)>0)
        meter.clear();assertEquals(0f,meter.at(25_000),0f)
        meter.capture(pcm(2000),0,48000,2);assertTrue(meter.at(5000)>0)
    }
    @Test fun oppositeChannelsDoNotCancelAndReadOnlySlicesWork(){
        val b=pcm(7000);for(i in 2 until b.limit() step 4)b.putShort(i,(-7000).toShort())
        b.position(400);val view=b.asReadOnlyBuffer();val meter=PcmEnvelope()
        meter.capture(view,0,48000,2);assertTrue(meter.at(5000)>.5);assertEquals(400,view.position())
    }
    @Test fun historyIsBoundedAndTracksTheRecentPlaybackTime(){
        val meter=PcmEnvelope();val b=pcm(1000)
        repeat(2000){meter.capture(b,it*20_000L,48000,2)}
        assertEquals(0f,meter.at(0),0f)
        assertTrue(meter.at(1999*20_000L+1000)>0)
    }
    @Test fun malformedOrEmptyFormatDoesNotConsumeInput(){
        val b=pcm(1000);val meter=PcmEnvelope()
        meter.capture(b,0,0,2);meter.capture(b,0,48000,0)
        assertEquals(0,b.position());assertEquals(0f,meter.at(1),0f)
    }
}
