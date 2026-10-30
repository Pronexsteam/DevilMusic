package dev.devilmusic.androidproof

import org.junit.Assert.*
import org.junit.Test

class UpdateReleaseTest {
    private fun release(tag:String="v0.0.19",preview:Boolean=true,draft:Boolean=false,file:String="Devil-Music-${tag.removePrefix("v")}-android.apk",url:String="${UpdateRelease.REPOSITORY}/releases/download/$tag/$file",state:String="uploaded",size:Long=7000000)=
        """{"tag_name":"$tag","prerelease":$preview,"draft":$draft,"body":"New controls","assets":[{"name":"$file","browser_download_url":"$url","state":"$state","size":$size}]}"""
    private fun find(vararg releases:String,current:String="0.0.18-preview")=UpdateRelease.find(releases.joinToString(",","[","]"),current)

    @Test fun selectsNewestVersionNotReleaseCreationOrder(){
        assertEquals("v0.0.20",find(release("v0.0.19"),release("v0.0.20"),release("v0.0.9"))?.tag)
        assertNull(find(release("v0.0.18")))
        assertNull(find(release("v0.0.17")))
    }
    @Test fun stableClientsStayStableAndPreviewCanGraduate(){
        assertNull(find(release(),current="0.0.18"))
        assertEquals("v0.0.19",find(release(preview=false),current="0.0.18")?.tag)
        assertNotNull(find(release("v0.0.18",preview=false)))
        assertNull(find(release("v0.0.18",preview=false),current="0.0.18"))
    }
    @Test fun draftsPartialUploadsAndOtherAssetsAreNotOffered(){
        assertNull(find(release(draft=true)))
        assertNull(find(release(state="new")))
        assertNull(find(release(size=0)))
        assertNull(find(release(size=251L*1024*1024)))
        assertNull(find(release(file="other.apk")))
    }
    @Test fun rejectsLookalikeHostsRepositoriesAndUnsafeUrls(){
        for(url in listOf("http://github.com/Pronexsteam/DevilMusic/releases/download/v0.0.19/Devil-Music-0.0.19-android.apk",
            "https://github.com.attacker.test/Pronexsteam/DevilMusic/releases/download/v0.0.19/Devil-Music-0.0.19-android.apk",
            "https://github.com/other/DevilMusic/releases/download/v0.0.19/Devil-Music-0.0.19-android.apk",
            "file:///sdcard/update.apk","javascript:alert(1)"))assertNull(find(release(url=url)))
    }
    @Test fun malformedVersionsDoNotCreateDowngrades(){
        for(value in listOf("unknown","v0.0.99999999999999999999","../19","01.0.1","0.0.19-beta","0.0.19/"))assertNull(AppVersion.parse(value))
        assertNull(find(release(),current="unknown"))
        assertNull(find("""{"tag_name":"v0.0.19","draft":false,"prerelease":true}"""))
        assertNull(find("{}"))
    }
    @Test fun capsNotesAndChecksResponseBeforeParsing(){
        val json=release().replace("New controls","n".repeat(20000))
        assertEquals(16000,find(json)?.notes?.length)
        try {UpdateRelease.find(" ".repeat(UpdateRelease.MAX_BYTES+1),"0.0.18-preview");fail("Oversized response accepted")}catch(_:IllegalArgumentException){}
    }
}
