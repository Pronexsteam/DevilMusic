// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof
internal object AudioRangePolicy {
    fun response(status:Int,position:Long,requested:Long,range:String?,contentLength:Long):Long {
        fun invalid():Nothing=throw OnlineSourceException("audio_range_mismatch","open")
        if(position<0 || requested< -1 || contentLength< -1)invalid()
        if(status==200) {if(position!=0L)invalid();return if(requested>=0 && contentLength>=0)minOf(requested,contentLength) else if(requested>=0)requested else contentLength}
        if(status!=206)invalid()
        val match=Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)").matchEntire(range ?: "") ?: invalid()
        val start=match.groupValues[1].toLongOrNull() ?: invalid();val end=match.groupValues[2].toLongOrNull() ?: invalid()
        if(start!=position || end<start || end==Long.MAX_VALUE)invalid()
        val count=end-start+1
        if(contentLength>=0 && contentLength!=count)invalid()
        if(requested>=0 && count>requested)invalid()
        if(match.groupValues[3]!="*") {val total=match.groupValues[3].toLongOrNull() ?: invalid();if(total<=end)invalid()}
        return count
    }
}
