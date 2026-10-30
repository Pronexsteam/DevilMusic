package dev.devilmusic.androidproof
import org.junit.Assert.*
import org.junit.Test
class ArtworkResolutionTest {
 @Test fun smallMusicCoverRequestsLargeSourceAndRetainsOriginalFallback(){
  val source="https://yt3.googleusercontent.com/cover=w120-h120-l90-rj"
  assertEquals(listOf("https://yt3.googleusercontent.com/cover=w1024-h1024-l90-rj",source),ArtworkResolution.candidates(source,1024))
  assertTrue(ArtworkResolution.candidates(source,384).first().contains("w384-h384"))
 }
 @Test fun unrelatedOrSignedVideoUrlsAreNotRewritten(){
  val source="https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg?sqp=example&rs=signature"
  assertEquals(listOf(source),ArtworkResolution.candidates(source,1024))
  assertTrue(ArtworkResolution.candidates("https://example.com/cover=w120-h120",1024).isEmpty())
 }
 @Test fun decodingRetainsEnoughPixelsWithoutUpscalingSmallSource(){
  assertEquals(1,ArtworkResolution.sample(120,120,1024))
  assertEquals(1,ArtworkResolution.sample(1200,1200,1024))
  assertEquals(4,ArtworkResolution.sample(4096,4096,1024))
 }
}
