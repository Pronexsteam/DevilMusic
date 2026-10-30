// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

/** Retained across DataSource.close/open (retry/seek), independent of resolver LRU eviction. */
internal class OnlineLeaseBinding<T>(private val resolve:(String)->T,private val validate:(String,T)->Unit) {
    private var bound:Pair<String,T>?=null
    fun open(id:String):T {
        val source=bound?.takeIf {it.first==id}?.second ?: resolve(id).also {bound=id to it}
        validate(id,source)
        return source
    }
}
