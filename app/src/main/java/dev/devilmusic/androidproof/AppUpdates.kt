// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

internal data class UpdateResult(val available:UpdateRelease?,val failed:Boolean=false)

/** One small request per day while the app is open. No playback worker, account or library data. */
internal object AppUpdates {
    private val worker=Executors.newSingleThreadExecutor {r->Thread(r,"app-updates").apply {isDaemon=true}}
    private var flight:CompletableFuture<UpdateResult>?=null
    @Synchronized fun check(context:Context,installed:String,manual:Boolean):CompletableFuture<UpdateResult> {
        flight?.let {return it}
        val app=context.applicationContext
        val result=CompletableFuture<UpdateResult>();flight=result
        worker.execute {
            val preferences=app.getSharedPreferences("application-updates",Context.MODE_PRIVATE)
            val cache=File(app.cacheDir,"official-releases.json")
            fun cached()=runCatching {if(cache.exists() && cache.length()<=UpdateRelease.MAX_BYTES)UpdateRelease.find(cache.readText(),installed) else null}.getOrNull()
            val now=System.currentTimeMillis()
            val last=preferences.getLong("checked",0)
            val delay=if(preferences.getBoolean("failed",false))60*60*1000L else 24*60*60*1000L
            val outcome=if(!manual && last>0 && now>=last && now-last<delay)UpdateResult(cached(),preferences.getBoolean("failed",false)) else {
                runCatching {
                    val json=fetch()
                    val release=UpdateRelease.find(json,installed)
                    atomicWrite(cache,json.toByteArray(Charsets.UTF_8))
                    preferences.edit().putLong("checked",now).putBoolean("failed",false).apply()
                    UpdateResult(release)
                }.getOrElse {
                    preferences.edit().putLong("checked",now).putBoolean("failed",true).apply()
                    UpdateResult(cached(),true)
                }
            }
            synchronized(this){flight=null}
            result.complete(outcome)
        }
        return result
    }
    private fun fetch():String {
        val connection=URL(UpdateRelease.ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout=8000;connection.readTimeout=10000;connection.instanceFollowRedirects=false
            connection.setRequestProperty("Accept","application/vnd.github+json")
            connection.setRequestProperty("User-Agent","DevilMusic-Android-UpdateCheck")
            check(connection.responseCode==200){"Update check unavailable"}
            require(connection.contentLengthLong<=UpdateRelease.MAX_BYTES)
            return connection.inputStream.use {input->
                val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true){val n=input.read(buffer);if(n<0)break;require(bytes.size()+n<=UpdateRelease.MAX_BYTES);bytes.write(buffer,0,n)}
                bytes.toString("UTF-8")
            }
        } finally {connection.disconnect()}
    }
}
