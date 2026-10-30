// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import java.nio.ByteBuffer
import kotlin.math.*

/** Bounded pre-volume observer: a short analysis window only, no recordings or buffer mutation. */
internal class PcmRhythm {
    private val size=512
    private val hop=128
    private val left=DoubleArray(size)
    private val right=DoubleArray(size)
    private val re=DoubleArray(size)
    private val im=DoubleArray(size)
    private val power=DoubleArray(size/2)
    private val previous=DoubleArray(size/2)
    private val window=DoubleArray(size){.5-.5*cos(2*PI*it/(size-1))}
    private val cosine=DoubleArray(size/2){cos(2*PI*it/size)}
    private val sine=DoubleArray(size/2){sin(2*PI*it/size)}
    private val times=LongArray(1024)
    private val onsetTimes=LongArray(1024){Long.MIN_VALUE}
    private val beatTimes=LongArray(1024){Long.MIN_VALUE}
    private val strengths=FloatArray(1024)
    private val confidences=FloatArray(1024)
    private val tempos=FloatArray(1024)
    private val envelopes=FloatArray(1024)
    private val tracker=PulseTracker()
    private var rate=0;private var channels=0;private var step=1;private var counter=0
    private var filled=0;private var write=0;private var sinceHop=0
    private var lpL=0.0;private var lpR=0.0;private var lp2L=0.0;private var lp2R=0.0
    private var expected=Long.MIN_VALUE
    private var mean=.04;private var variance=.001;private var priorNovelty=0.0;private var olderNovelty=0.0
    private var priorTime=0L;private var priorGate=false;private var priorThreshold=.1
    private var logPower=0.0;private var warmed=false;private var meanRms=.02
    private var onset=Long.MIN_VALUE;private var beat=Long.MIN_VALUE;private var onsetStrength=0f
    private var head=0;private var count=0
    fun clear(){
        left.fill(0.0);right.fill(0.0);previous.fill(0.0);tracker.clear()
        filled=0;write=0;counter=0;sinceHop=0;lpL=0.0;lpR=0.0;lp2L=0.0;lp2R=0.0
        expected=Long.MIN_VALUE;mean=.04;variance=.001;priorNovelty=0.0;olderNovelty=0.0
        priorGate=false;warmed=false;meanRms=.02;onset=Long.MIN_VALUE;beat=Long.MIN_VALUE;onsetStrength=0f;head=0;count=0
    }
    fun capture(buffer:ByteBuffer,pts:Long,sampleRate:Int,channelCount:Int){
        if(sampleRate !in 8000..384000 || channelCount !in 1..32)return
        if(rate!=sampleRate || channels!=channelCount || (expected!=Long.MIN_VALUE && abs(pts-expected)>3000)) {
            clear();rate=sampleRate;channels=channelCount;step=max(1,rate/12000)
        }
        val frames=buffer.remaining()/(channels*2);val base=buffer.position()
        val alpha=1-exp(-2*PI*(rate.toDouble()/step*.38)/rate)
        for(f in 0 until frames){
            val index=base+f*channels*2
            fun sample(at:Int):Double {val bits=(buffer.get(at).toInt() and 255) or (buffer.get(at+1).toInt() shl 8);return bits.toShort()/32768.0}
            val l=sample(index);val r=if(channels>1)sample(index+2) else 0.0
            // Two low-pass stages only in the analysis branch, before decimation.
            lpL+=alpha*(l-lpL);lpR+=alpha*(r-lpR);lp2L+=alpha*(lpL-lp2L);lp2R+=alpha*(lpR-lp2R)
            if(++counter<step)continue
            counter=0;left[write]=lp2L;right[write]=lp2R;write=(write+1)%size
            filled=min(size,filled+1);sinceHop++
            if(filled==size && sinceHop>=hop){
                sinceHop=0
                // Center timestamp compensates the FFT window. Decoded lookahead is not presented early.
                analyze(pts+f*1_000_000L/rate-size/2*step*1_000_000L/rate)
            }
        }
        expected=pts+frames*1_000_000L/rate
    }
    private fun transform(input:DoubleArray,add:Boolean){
        for(i in 0 until size){re[i]=input[(write+i)%size]*window[i];im[i]=0.0}
        var j=0
        for(i in 1 until size){var bit=size shr 1;while(j and bit!=0){j=j xor bit;bit=bit shr 1};j=j xor bit
            if(i<j){val x=re[i];re[i]=re[j];re[j]=x}}
        var length=2
        while(length<=size){val half=length/2;val stride=size/length
            for(start in 0 until size step length)for(k in 0 until half){
                val a=start+k;val b=a+half;val c=cosine[k*stride];val sn=sine[k*stride]
                val x=re[b]*c+im[b]*sn;val y=im[b]*c-re[b]*sn
                re[b]=re[a]-x;im[b]=im[a]-y;re[a]+=x;im[a]+=y
            };length*=2
        }
        for(k in 1 until size/2){val p=re[k]*re[k]+im[k]*im[k];power[k]=if(add)power[k]+p else p}
    }
    private fun analyze(time:Long){
        transform(left,false);if(channels>1)transform(right,true)
        var total=0.0;for(k in 1 until power.size)total+=power[k]
        val rms=sqrt(total)/(size*.43)
        val normalizer=sqrt(total).coerceAtLeast(1e-9)
        var flux=0.0;var mass=0.0
        for(k in 1 until power.size){
            val v=sqrt(power[k])/normalizer
            // Equalized relative spectral change hears attacks outside the bass band too.
            flux+=max(0.0,v-previous[k]);mass+=v;previous[k]=v
        }
        val lp=ln(total.coerceAtLeast(1e-12))
        val novelty=if(warmed && rms>.0003)(flux/mass.coerceAtLeast(.1)+max(0.0,lp-logPower)*.08).coerceAtMost(2.0) else 0.0
        val threshold=mean+max(.055,sqrt(variance)*1.4)
        if(priorGate && priorNovelty>priorThreshold && priorNovelty>=olderNovelty && priorNovelty>novelty &&
            (onset==Long.MIN_VALUE || priorTime-onset>=100_000)) {
            onset=priorTime;onsetStrength=((priorNovelty-priorThreshold)/.35+.25).coerceIn(.2,1.0).toFloat()
            if(tracker.onset(onset,onsetStrength))beat=onset
        }
        tracker.frame(time)
        val delta=novelty-mean;mean+=.02*delta;variance=.98*variance+.02*delta*delta
        olderNovelty=priorNovelty;priorNovelty=novelty;priorThreshold=threshold;priorTime=time;priorGate=rms>.0003
        logPower=lp;warmed=true;meanRms+=.008*(rms-meanRms)
        val slot=(head+count)%times.size
        times[slot]=time;onsetTimes[slot]=onset;beatTimes[slot]=beat;strengths[slot]=onsetStrength
        confidences[slot]=tracker.confidence;tempos[slot]=tracker.bpm
        envelopes[slot]=if(rms>.0003)(rms/(meanRms.coerceAtLeast(.002)*2)).coerceIn(0.0,1.0).toFloat() else 0f
        if(count==times.size)head=(head+1)%times.size else count++
    }
    fun at(positionUs:Long):RhythmFrame {
        while(count>1 && times[(head+1)%times.size]<=positionUs){head=(head+1)%times.size;count--}
        if(count==0 || times[head]>positionUs || positionUs-times[head]>100_000)return RhythmFrame.QUIET
        return RhythmFrame(positionUs,onsetTimes[head],beatTimes[head],strengths[head],confidences[head],tempos[head],envelopes[head])
    }
}

/** Autocorrelation of real attacks, with confidence gating; never emits free-running beats. */
internal class PulseTracker {
    private val values=FloatArray(768)
    private var head=0;private var count=0;private var frames=0;private var lastFrame=0L;private var hopUs=10667.0
    private var eventCount=0;private var lastOnset=Long.MIN_VALUE;private var lastBeat=Long.MIN_VALUE
    private var pending=0f;private var tail=0f;private var period=0.0
    var confidence=0f;private set
    var bpm=0f;private set
    fun clear(){values.fill(0f);head=0;count=0;frames=0;lastFrame=0;eventCount=0;lastOnset=Long.MIN_VALUE;lastBeat=Long.MIN_VALUE;pending=0f;tail=0f;period=0.0;confidence=0f;bpm=0f;hopUs=10667.0}
    fun onset(time:Long,strength:Float):Boolean {
        pending=max(pending,strength);lastOnset=time;eventCount++
        if(confidence<.58f || period<=0)return false
        if(lastBeat==Long.MIN_VALUE){lastBeat=time;return true}
        val delta=time-lastBeat
        val beats=round(delta/period).coerceAtLeast(1.0)
        if(delta>=period*.65 && abs(delta-beats*period)<=period*.20){lastBeat=time;return true}
        return false
    }
    fun frame(time:Long){
        if(lastFrame>0 && time>lastFrame)hopUs=.9*hopUs+.1*(time-lastFrame);lastFrame=time
        // Three-sample attack window tolerates timing quantization without adding fake attacks.
        if(pending>0 && count>0){val p=(head-1+values.size)%values.size;values[p]=max(values[p],pending*.5f)}
        values[head]=max(pending,tail);tail=pending*.5f;pending=0f;head=(head+1)%values.size;count=min(values.size,count+1)
        if(lastOnset!=Long.MIN_VALUE && time-lastOnset>1_500_000){confidence=0f;bpm=0f;lastBeat=Long.MIN_VALUE}
        if(++frames%24!=0 || eventCount<5 || count*hopUs<2_000_000 || lastOnset==Long.MIN_VALUE || time-lastOnset>1_500_000)return
        var best=0.0;var bestLag=0
        val from=ceil(300_000/hopUs).toInt().coerceAtLeast(2);val to=floor(1_000_000/hopUs).toInt().coerceAtMost(count/2)
        for(lag in from..to){var dot=0.0;var a=0.0;var b=0.0
            for(i in lag until count){val x=values[(head-1-i+values.size*2)%values.size].toDouble();val y=values[(head-1-i+lag+values.size*2)%values.size].toDouble();dot+=x*y;a+=x*x;b+=y*y}
            val score=if(a*b>1e-8)dot/sqrt(a*b) else 0.0
            if(score>best+.025){best=score;bestLag=lag}
        }
        confidence=best.toFloat()
        if(best>=.58 && bestLag>0){val candidate=bestLag*hopUs
            if(period==0.0 || abs(candidate-period)>period*.14){period=candidate;lastBeat=Long.MIN_VALUE}else period=.8*period+.2*candidate
            bpm=(60_000_000/period).toFloat()
        }else {bpm=0f;lastBeat=Long.MIN_VALUE}
    }
}

internal data class RhythmFrame(val positionUs:Long,val onsetUs:Long,val beatUs:Long,val strength:Float,val confidence:Float,val bpm:Float,val energy:Float){
    companion object {val QUIET=RhythmFrame(0,Long.MIN_VALUE,Long.MIN_VALUE,0f,0f,0f,0f)}
}
internal object MusicRhythm {
    @Volatile private var frame=RhythmFrame.QUIET
    @Volatile private var stamp=0L
    fun publish(value:RhythmFrame){frame=value;stamp=System.nanoTime()}
    fun read():RhythmFrame=if(System.nanoTime()-stamp in 0..250_000_000L)frame else RhythmFrame.QUIET
}
