package dev.devilmusic.androidproof

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class EnergyAudioSinkTest {
    @Test fun observerDoesNotChangeBuffersOrRetrySemanticsAndFollowsOutputClock(){
        var clock=990_000L;var calls=0;var flushes=0
        val bytes=ByteBuffer.allocate(1920).order(ByteOrder.LITTLE_ENDIAN).apply {repeat(960){putShort(9000.toShort())};flip()}
        val original=bytes.array().copyOf()
        val delegate=Proxy.newProxyInstance(AudioSink::class.java.classLoader,arrayOf(AudioSink::class.java)){_,method,args->
            when(method.name){
                "getCurrentPositionUs"->clock
                "handleBuffer"->{calls++;assertSame(bytes,args[0]);assertEquals(1_000_000L,args[1]);assertEquals(1,args[2]);assertArrayEquals(original,bytes.array());false}
                "flush"->{flushes++;null}
                else->null
            }
        } as AudioSink
        val observer=EnergyAudioSink(delegate)
        try {
            observer.configure(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelCount(1).build(),0,null)
            MusicEnergy.requested=true
            assertFalse(observer.handleBuffer(bytes,1_000_000,1));assertFalse(observer.handleBuffer(bytes,1_000_000,1));assertEquals(2,calls)
            assertEquals(clock,observer.getCurrentPositionUs(false));assertEquals(0f,MusicEnergy.read(),0f)
            clock=1_005_000;observer.getCurrentPositionUs(false);assertTrue(MusicEnergy.read()>.5f)
            observer.pause();assertEquals(0f,MusicEnergy.read(),0f)
            observer.flush();assertEquals(1,flushes);observer.getCurrentPositionUs(false);assertEquals(0f,MusicEnergy.read(),0f)
            assertEquals(0,bytes.position())
        } finally {MusicEnergy.requested=false;MusicEnergy.publish(0f)}
    }
}
