// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import kotlin.math.*

internal data class VisualPulse(val age:Float,val strength:Float,val glint:Float,val ambient:Float,val duration:Float=1.15f){
    companion object {val REST=VisualPulse(1f,0f,0f,0f)}
}
/** One presentation-time event drives every surface; UI frame rate cannot create extra beats. */
internal class RhythmVisuals {
    private var lastOnset=Long.MIN_VALUE;private var lastBeat=Long.MIN_VALUE
    private var pulseTime=Long.MIN_VALUE;private var pulseStrength=0f;private var lastPosition=Long.MIN_VALUE
    private var duration=1.15f
    fun clear(){lastOnset=Long.MIN_VALUE;lastBeat=Long.MIN_VALUE;pulseTime=Long.MIN_VALUE;pulseStrength=0f;lastPosition=Long.MIN_VALUE;duration=1.15f}
    fun update(frame:RhythmFrame):VisualPulse {
        if(frame===RhythmFrame.QUIET){clear();return VisualPulse.REST}
        if(lastPosition!=Long.MIN_VALUE && (frame.positionUs<lastPosition || frame.positionUs-lastPosition>500_000))clear()
        lastPosition=frame.positionUs
        if(frame.beatUs!=Long.MIN_VALUE && frame.beatUs!=lastBeat && frame.confidence>=.58f){
            lastBeat=frame.beatUs
            // Let a travelling crest finish. Fast tracks accent the note on each attack,
            // while the larger envelope follows a legible subdivision of actual beats.
            if(pulseTime==Long.MIN_VALUE || frame.beatUs-pulseTime>=650_000){
                pulseTime=frame.beatUs;pulseStrength=.55f+.45f*frame.strength
                duration=(120f/frame.bpm.coerceIn(60f,200f)).coerceIn(.95f,1.45f)
            }
        }
        if(frame.onsetUs!=Long.MIN_VALUE && frame.onsetUs!=lastOnset){
            lastOnset=frame.onsetUs
            // Dense attacks must not restart the ripple before it reaches the outer glass.
            if(frame.confidence<.58f && (pulseTime==Long.MIN_VALUE || frame.onsetUs-pulseTime>=900_000)){
                pulseTime=frame.onsetUs;pulseStrength=.30f+.30f*frame.strength;duration=1.2f
            }
        }
        val age=if(pulseTime==Long.MIN_VALUE)2f else ((frame.positionUs-pulseTime)/1_000_000f).coerceIn(0f,2f)
        val onsetAge=if(lastOnset==Long.MIN_VALUE)1f else ((frame.positionUs-lastOnset)/1_000_000f).coerceIn(0f,1f)
        val glint=frame.strength*exp(-onsetAge*7f)
        val wave=if(age<duration)pulseStrength else 0f
        return VisualPulse(age,wave,glint,(wave*exp(-age*3.5f)+glint*.10f).coerceIn(0f,1f),duration)
    }
}
