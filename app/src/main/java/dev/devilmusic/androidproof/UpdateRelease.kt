// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser

internal data class AppVersion(val major:Int,val minor:Int,val patch:Int,val preview:Boolean):Comparable<AppVersion> {
    override fun compareTo(other:AppVersion):Int=compareValuesBy(this,other,{it.major},{it.minor},{it.patch},{!it.preview})
    companion object {
        private val pattern=Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-preview)?$")
        fun parse(value:String):AppVersion? {
            val m=pattern.matchEntire(value) ?: return null
            return runCatching {AppVersion(m.groupValues[1].toInt(),m.groupValues[2].toInt(),m.groupValues[3].toInt(),m.groupValues[4].isNotEmpty())}.getOrNull()
        }
    }
}

internal data class UpdateRelease(val version:AppVersion,val tag:String,val notes:String,val apk:String,val size:Long) {
    val label get()=tag.removePrefix("v")+if(version.preview)" preview" else ""
    companion object {
        const val REPOSITORY="https://github.com/Pronexsteam/DevilMusic"
        const val ENDPOINT="https://api.github.com/repos/Pronexsteam/DevilMusic/releases?per_page=30"
        const val MAX_BYTES=1024*1024
        /** Ignore drafts, other assets and stale releases. The API's creation order is not version order. */
        fun find(json:String,installed:String):UpdateRelease? {
            require(json.toByteArray(Charsets.UTF_8).size<=MAX_BYTES)
            val current=AppVersion.parse(installed) ?: return null
            return JsonParser.array().from(json).filterIsInstance<JsonObject>().mapNotNull {r->
                if(r.getBoolean("draft",true))return@mapNotNull null
                val tag=r.getString("tag_name","");val parsed=AppVersion.parse(tag) ?: return@mapNotNull null
                val version=parsed.copy(preview=parsed.preview || r.getBoolean("prerelease",false))
                if(version<=current || (!current.preview && version.preview))return@mapNotNull null
                val file="Devil-Music-${tag.removePrefix("v").removeSuffix("-preview")}-android.apk"
                val url="$REPOSITORY/releases/download/$tag/$file"
                val assets=r.getArray("assets") ?: return@mapNotNull null
                val asset=assets.filterIsInstance<JsonObject>().firstOrNull {
                    it.getString("name","")==file && it.getString("state","")=="uploaded" &&
                        it.getString("browser_download_url","")==url && it.getLong("size",0) in 1..250L*1024*1024
                } ?: return@mapNotNull null
                UpdateRelease(version,tag,r.getString("body","").take(16000),url,asset.getLong("size"))
            }.maxByOrNull {it.version}
        }
    }
}
