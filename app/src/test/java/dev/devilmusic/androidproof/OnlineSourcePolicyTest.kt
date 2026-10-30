// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class OnlineSourcePolicyTest {
    private val id="u9lj-c29dxI"
    private fun request(p:OnlineSourcePolicy,path:String="visitor_id",client:String="ANDROID",host:String="youtubei.googleapis.com") =
        p.request("POST",URI("https://$host/youtubei/v1/$path"),emptySet(),client,200,id)
    private fun failure(expected:String,block:()->Unit) { try { block();fail("Expected $expected") } catch(e:OnlineSourceException) { assertEquals(expected,e.reason) } }
    @Test fun youtubeInputPreservesIdentity() {
        assertEquals(id,YouTubeInput.parse("https://youtu.be/$id"))
        assertEquals(id,YouTubeInput.parse("https://www.youtube.com/watch?v=$id&list=ignored"))
        assertEquals(id,YouTubeInput.fromLogical(YouTubeInput.logical(id)))
    }
    @Test fun externalUrisCannotBecomeSources() {
        listOf("http://youtu.be/$id","https://youtube.com.evil/watch?v=$id","https://a@youtu.be/$id","file:///private","https://www.youtube.com/watch?v=$id&v=$id").forEach { assertNull(YouTubeInput.parse(it)) }
        assertNull(YouTubeInput.fromLogical("devilmusic-youtube://evil/$id"))
    }
    @Test fun deniedHttpStopsAllFurtherRequests() { for(status in listOf(401,403,429)) { val p=OnlineSourcePolicy();request(p);failure("http_$status"){p.response(status)};failure("http_$status"){request(p,"reel/reel_item_watch")};failure("http_$status"){p.playability("OK",true)} } }
    @Test fun missingOrDeniedPlayerIsNotSuccess() { for((status,reason) in mapOf("" to "missing_playability","LOGIN_REQUIRED" to "access_denied","UNPLAYABLE" to "unavailable","ERROR" to "upstream_error")) { val p=OnlineSourcePolicy();failure(reason){p.playability(status,true)};failure(reason){request(p)} } }
    @Test fun normalAdditionalFormatQueryRequiresInitialOk() {
        val p=OnlineSourcePolicy();request(p);request(p,"reel/reel_item_watch");failure("additional_client_before_ok"){request(p,client="VISIONOS",host="www.youtube.com")}
        val good=OnlineSourcePolicy();request(good);request(good,"reel/reel_item_watch");good.playability("OK",true)
        request(good,client="VISIONOS",host="www.youtube.com");request(good,"player","VISIONOS");good.playability("OK",true);assertNull(good.failure)
    }
    @Test fun scriptFallbackCannotFollowRefusal() {
        val p=OnlineSourcePolicy();p.playability("OK",true);p.request("GET",URI("https://www.youtube.com/iframe_api"),emptySet(),null,0,id)
        failure("http_403"){p.response(403)};failure("http_403"){p.request("GET",URI("https://www.youtube.com/embed/$id"),emptySet(),null,0,id)}
    }
    @Test fun cookiesAndWrongIdentityRejected() {
        val p=OnlineSourcePolicy();failure("unsupported_headers"){p.request("POST",URI("https://youtubei.googleapis.com/youtubei/v1/visitor_id"),setOf("Cookie"),"ANDROID",200,id)}
        failure("wrong_track"){OnlineSourcePolicy().playability("OK",false)}
    }
    @Test fun operationDeadlineStopsWithoutRetry() { var now=0L;val p=OnlineSourcePolicy { now };now=45_000_000_000L;failure("timeout"){p.check()} }
    @Test fun titleOfElevenLettersIsStillSearch() { assertNull(YouTubeInput.fromSearchInput("Radioactive"));assertEquals(id,YouTubeInput.fromSearchInput("https://youtu.be/$id")) }
    @Test fun byteRangesMatchTheRequestedPosition() {
        assertEquals(100L,AudioRangePolicy.response(206,200,-1,"bytes 200-299/300",100))
        assertEquals(100L,AudioRangePolicy.response(200,0,-1,null,100))
        failure("audio_range_mismatch"){AudioRangePolicy.response(206,200,-1,"bytes 0-99/300",100)}
        failure("audio_range_mismatch"){AudioRangePolicy.response(206,200,-1,"bytes 200-299/300",99)}
        failure("audio_range_mismatch"){AudioRangePolicy.response(206,200,-1,"bytes 200-299/299",100)}
        failure("audio_range_mismatch"){AudioRangePolicy.response(200,200,-1,null,100)}
    }
    @Test fun artworkOnlyUsesPublicImageHosts() {
        assertTrue(NewPipeCatalog.validArtwork("https://i.ytimg.com/vi/$id/hqdefault.jpg"))
        assertFalse(NewPipeCatalog.validArtwork("https://i.ytimg.com.evil/vi/$id/hqdefault.jpg"))
    }
    @Test fun terminalOnlineErrorsRemainTerminalWhenWrapped() {
        assertTrue(OnlineLoadErrorPolicy.terminal(java.io.IOException("wrapper",OnlineSourceException("audio_http_403"))))
        assertFalse(OnlineLoadErrorPolicy.terminal(java.io.FileNotFoundException("local file")))
    }
}
