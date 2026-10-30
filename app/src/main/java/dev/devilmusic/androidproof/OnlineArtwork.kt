// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL

internal object OnlineArtwork {
    @Volatile var disk:ArtworkDiskCache?=null
    @Volatile var debugTracing=false
    fun trace(event:String){if(debugTracing)android.util.Log.d("DevilMusicArtwork",event)}
    fun load(url:String,target:Int=1024):Bitmap? {
        val candidates=ArtworkResolution.candidates(url,target)
        for(candidate in candidates){disk?.get("${target.coerceIn(128,1024)}:$candidate")?.let {bytes->runCatching {decode(bytes,target.coerceIn(128,1024))}.getOrNull()?.let {trace("disk_hit");return it}}}
        for(candidate in candidates) {
            if(Thread.currentThread().isInterrupted)return null
            val bitmap=runCatching {fetch(candidate,target.coerceIn(128,1024))}.getOrNull()
            if(bitmap!=null)return bitmap
        }
        return null
    }
    private fun fetch(url:String,target:Int):Bitmap? {
        if(!NewPipeCatalog.validArtwork(url))return null
        val key="$target:$url"
        disk?.get(key)?.let {bytes->decode(bytes,target)?.let {return it}}
        val c=URL(url).openConnection() as HttpURLConnection
        trace("network_fetch")
        try {
            c.instanceFollowRedirects=false;c.connectTimeout=5000;c.readTimeout=5000
            if(c.responseCode!=200)return null
            val bytes=c.inputStream.use {input->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){if(Thread.currentThread().isInterrupted)return null;val n=input.read(buffer);if(n<0)break;if(out.size()+n>4*1024*1024)return null;out.write(buffer,0,n)};out.toByteArray()}
            return decode(bytes,target)?.also {disk?.put(key,bytes)}
        } finally {c.disconnect()}
    }
    private fun decode(bytes:ByteArray,target:Int):Bitmap? {
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            if(bounds.outWidth<=0 || bounds.outHeight<=0 || bounds.outWidth>8192 || bounds.outHeight>8192)return null
            val sample=ArtworkResolution.sample(bounds.outWidth,bounds.outHeight,target)
            val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=sample}) ?: return null
            val largest=maxOf(decoded.width,decoded.height)
            if(largest<=target)return decoded
            return Bitmap.createScaledBitmap(decoded,(decoded.width*target/largest).coerceAtLeast(1),(decoded.height*target/largest).coerceAtLeast(1),true).also {if(it!==decoded)decoded.recycle()}
    }
}
