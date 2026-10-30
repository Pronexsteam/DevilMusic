// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/** Observe only decoded PCM16; delegate every playback operation unchanged. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class EnergyAudioSink(sink:AudioSink):ForwardingAudioSink(sink){
    private val envelope=PcmEnvelope()
    private val rhythm=PcmRhythm()
    private var observing=false
    private var rate=0
    private var channels=0
    private var pcm=false
    private var lastBuffer:ByteBuffer?=null
    private var lastPts=Long.MIN_VALUE
    override fun configure(format:Format,bufferSize:Int,outputChannels:IntArray?){
        super.configure(format,bufferSize,outputChannels)
        clear();rate=format.sampleRate;channels=format.channelCount
        pcm=format.sampleMimeType==MimeTypes.AUDIO_RAW && format.pcmEncoding==C.ENCODING_PCM_16BIT
    }
    override fun handleBuffer(buffer:ByteBuffer,presentationTimeUs:Long,encodedAccessUnitCount:Int):Boolean {
        if(!MusicEnergy.requested){if(observing)clear()}
        else if(pcm && (lastBuffer!==buffer || lastPts!=presentationTimeUs)){
            observing=true
            envelope.capture(buffer,presentationTimeUs,rate,channels)
            rhythm.capture(buffer,presentationTimeUs,rate,channels)
            lastBuffer=buffer;lastPts=presentationTimeUs
        }
        return super.handleBuffer(buffer,presentationTimeUs,encodedAccessUnitCount)
    }
    override fun getCurrentPositionUs(sourceEnded:Boolean):Long {
        val position=super.getCurrentPositionUs(sourceEnded)
        MusicEnergy.publish(if(MusicEnergy.requested && pcm && position!=AudioSink.CURRENT_POSITION_NOT_SET)envelope.at(position) else 0f)
        MusicRhythm.publish(if(MusicEnergy.requested && pcm && position!=AudioSink.CURRENT_POSITION_NOT_SET)rhythm.at(position) else RhythmFrame.QUIET)
        return position
    }
    private fun clear(){envelope.clear();rhythm.clear();observing=false;lastBuffer=null;lastPts=Long.MIN_VALUE;MusicEnergy.publish(0f);MusicRhythm.publish(RhythmFrame.QUIET)}
    override fun pause(){super.pause();MusicEnergy.publish(0f);MusicRhythm.publish(RhythmFrame.QUIET)}
    override fun flush(){super.flush();clear()}
    override fun reset(){super.reset();clear()}
    override fun release(){super.release();clear()}
}
