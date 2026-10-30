// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import kotlin.random.Random

/** Stable IDs, no audio, artwork or network work. Rebuilt only when the queue/order changes. */
internal object ShufflePlan {
    fun fresh(ids:List<String>,current:String?,random:Random):List<String> {
        val unique=ids.distinct();val rest=unique.filter {it!=current}.shuffled(random)
        return if(current in unique)listOf(current!!)+rest else rest
    }
    fun reconcile(saved:List<String>,ids:List<String>,current:String?,random:Random):List<String> {
        val valid=ids.toHashSet();val seen=HashSet<String>()
        val survivors=saved.filter {it in valid && seen.add(it)}
        if(survivors.isEmpty())return fresh(ids,current,random)
        val added=ids.filter {seen.add(it)}.shuffled(random)
        if(added.isEmpty())return survivors
        // Merge additions only into the unplayed suffix, without O(n²) list insertions.
        val split=(survivors.indexOf(current)+1).coerceAtLeast(0)
        val tail=survivors.drop(split);val out=ArrayList<String>(valid.size)
        out.addAll(survivors.take(split));var a=0;var b=0
        while(a<tail.size || b<added.size){
            if(a<tail.size && (b==added.size || random.nextInt(tail.size-a+added.size-b)<tail.size-a))out.add(tail[a++]) else out.add(added[b++])
        }
        return out
    }
    fun move(order:List<String>,source:String,target:String):List<String> {
        val from=order.indexOf(source);val to=order.indexOf(target)
        if(from<0 || to<0 || from==to)return order
        return order.toMutableList().apply {removeAt(from);add(to,source)}
    }
}
