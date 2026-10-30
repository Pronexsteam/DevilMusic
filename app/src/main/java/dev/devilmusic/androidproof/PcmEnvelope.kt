// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import java.nio.ByteBuffer
import kotlin.math.*

/** Audio-thread-only, fixed-size envelope history. No PCM is retained or modified. */
internal class PcmEnvelope {
    private val starts=LongArray(512)
    private val ends=LongArray(512)
    private val levels=FloatArray(512)
    private var head=0
    private var count=0
    fun clear(){head=0;count=0}
    fun capture(buffer:ByteBuffer,pts:Long,rate:Int,channels:Int){
        if(rate<=0 || channels !in 1..32)return
        val stride=channels*2;val frames=buffer.remaining()/stride
        val window=maxOf(1,rate/50);val base=buffer.position()
        var frame=0
        while(frame<frames){
            val end=minOf(frames,frame+window);var sum=0.0;var n=0
            val step=maxOf(1,(end-frame)/128)
            var f=frame
            while(f<end){for(ch in 0 until channels){
                val index=base+f*stride+ch*2
                val bits=(buffer.get(index).toInt() and 255) or (buffer.get(index+1).toInt() shl 8)
                val v=bits.toShort().toDouble()/32768;sum+=v*v;n++
            };f+=step}
            val slot=(head+count)%starts.size
            starts[slot]=pts+frame*1_000_000L/rate;ends[slot]=pts+end*1_000_000L/rate
            levels[slot]=if(n==0)0f else ((sqrt(sum/n)-.003).coerceAtLeast(0.0)*3.8).coerceAtMost(1.0).toFloat()
            if(count==starts.size)head=(head+1)%starts.size else count++
            frame=end
        }
    }
    fun at(positionUs:Long):Float {
        while(count>0 && ends[head]<=positionUs){head=(head+1)%starts.size;count--}
        return if(count>0 && positionUs>=starts[head] && positionUs<ends[head])levels[head] else 0f
    }
}

/** Only scalar energy crosses to the UI; no microphone, recording or per-buffer UI callbacks. */
internal object MusicEnergy {
    @Volatile var requested=false
    @Volatile private var value=0f
    @Volatile private var stamp=0L
    fun publish(level:Float){value=level;stamp=System.nanoTime()}
    fun read():Float=if(System.nanoTime()-stamp in 0..250_000_000L)value else 0f
}
