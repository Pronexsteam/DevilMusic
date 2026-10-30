// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

internal class SleepPlan {
    var deadline=0L;private set
    var endOfTrack=false;private set
    fun set(minutes:Int,end:Boolean,now:Long){require(minutes in 0..180);deadline=if(minutes>0)now+minutes*60_000L else 0;endOfTrack=end && minutes==0}
    fun cancel(){deadline=0;endOfTrack=false}
    fun expired(now:Long)=deadline>0 && now>=deadline
    fun gain(now:Long)=if(deadline==0L)1f else ((deadline-now)/3000f).coerceIn(0f,1f)
}
internal object QueueUndoPolicy {
    fun insertion(ids:List<String>,before:String?,after:String?,fallback:Int):Int {
        after?.let {val i=ids.indexOf(it);if(i>=0)return i}
        before?.let {val i=ids.indexOf(it);if(i>=0)return i+1}
        return fallback.coerceIn(0,ids.size)
    }
}
internal object PlayNextPolicy {
    fun shuffled(order:List<Int>,current:Int,target:Int):IntArray? {
        if(current==target || current !in order || target !in order || order.toSet()!=order.indices.toSet())return null
        val next=order.filter {it!=target}.toMutableList()
        next.add(next.indexOf(current)+1,target)
        return next.toIntArray()
    }
}
