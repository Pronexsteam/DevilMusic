// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import java.net.URI

/** Resize only recognized public image transforms; never upscale decoded thumbnail pixels. */
internal object ArtworkResolution {
    fun candidates(url:String,target:Int):List<String> {
        if(!NewPipeCatalog.validArtwork(url))return emptyList()
        val size=target.coerceIn(128,1024);val uri=URI(url)
        val upgraded=when(uri.host){
            "yt3.googleusercontent.com","lh3.googleusercontent.com" ->
                url.replace(Regex("=w\\d+-h\\d+(?=-|$)"),"=w$size-h$size")
                    .replace(Regex("=s\\d+(?=-|$)"),"=s$size")
            else -> url
        }
        return listOf(upgraded,url).distinct()
    }
    fun sample(width:Int,height:Int,target:Int):Int {
        var result=1
        while(maxOf(width,height)/(result*2)>=target)result*=2
        return result
    }
}
