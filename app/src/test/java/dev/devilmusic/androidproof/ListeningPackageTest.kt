package dev.devilmusic.androidproof

import org.junit.Test
import org.junit.Assert.*
import java.net.*
import java.io.*
import com.grack.nanojson.JsonParser

class ListeningPackageTest {
    @Test fun livePagingOnlyWhenExplicitlyEnabled(){
        org.junit.Assume.assumeTrue(System.getenv("DEVILMUSIC_PAGING_PROOF")=="1")
        val first=CatalogPaging.search("Daft Punk","songs")
        assertTrue(first.shelves.flatMap {it.items}.isNotEmpty());assertNotNull(first.next)
        val second=first.next!!.invoke();val merged=CatalogPaging.merge(first.shelves,second.shelves).flatMap {it.items}
        assertTrue(merged.size>first.shelves.sumOf {it.items.size});assertEquals(merged.size,merged.map {it.kind+it.id}.distinct().size)
        println("Public search paging: ${first.shelves.sumOf {it.items.size}} → ${merged.size} unique cards")
    }
    @Test fun crossfadeDoesNotBoostAndExcludesRemoteStreams(){
        for(t in -100L..3500L step 10){val (a,b)=CrossfadeCurve.gains(t,3000);assertTrue(a in 0f..1f && b in 0f..1f);assertEquals(1f,a+b,.0001f)}
        assertFalse(CrossfadeCurve.local("youtube:abcdefghijk"));assertTrue(CrossfadeCurve.local("download:abcdefghijk"));assertTrue(CrossfadeCurve.local("content://media/test"))
    }
    @Test fun lrcKeepsRealWordsAndDoesNotInventThem(){
        val lines=Lrc.parse("[offset:100]\n[00:01.50]<00:01.50>Hello <00:02.0>world\n[00:05.00]Whole line\n[00:07.00][00:09.00]Chorus")
        assertEquals(listOf(1600L,5100L,7100L,9100L),lines.map {it.time});assertEquals(listOf(1600L,2100L),lines[0].words.map {it.time})
        assertTrue(lines[1].words.isEmpty());assertEquals(-1,Lrc.index(lines,0));assertEquals(1,Lrc.index(lines,6000));assertEquals(0,Lrc.index(lines,2000))
        assertTrue(Lrc.parse("[00:99]bad").isEmpty())
    }
    @Test fun continuationFiltersDuplicatesAndStaleTokens(){
        val root=JsonParser.`object`().from("""{"a":{"nextContinuationData":{"continuation":"abc"}},"b":{"continuationCommand":{"token":"abc"}},"menu":{"continuationCommand":{"token":"private"}}}""")
        assertEquals(listOf("abc"),CatalogPaging.tokens(root))
        val a=MusicCard("one","song","One","A",null);val b=a.copy(id="two")
        assertEquals(listOf(a,b),CatalogPaging.merge(listOf(MusicShelf("A",listOf(a))),listOf(MusicShelf("B",listOf(a,b)))).flatMap {it.items})
    }
    private class Reply(val code:Int,val location:String?=null):HttpURLConnection(URL("https://a.googlevideo.com/videoplayback")){
        override fun connect(){};override fun disconnect(){};override fun usingProxy()=false
        override fun getResponseCode()=code
        override fun getHeaderField(name:String):String?=when(name){"Location"->location;"Content-Range"->"bytes 5-7/8";"Content-Length"->"3";else->null}
        override fun getInputStream():InputStream=ByteArrayInputStream(byteArrayOf(1,2,3))
    }
    @Test fun cdnRedirectKeepsBytePositionAndRange(){
        val replies=ArrayDeque(listOf(Reply(302,"https://b.googlevideo.com/videoplayback?id=test"),Reply(206)))
        val used=mutableListOf<Reply>();val stream=OnlineHttpStream(mapOf("User-Agent" to "test")){replies.removeFirst().also(used::add)}
        assertEquals(3L,stream.open(URI("https://a.googlevideo.com/videoplayback"),5,3));assertEquals(listOf("bytes=5-7","bytes=5-7"),used.map {it.getRequestProperty("Range")})
        val bytes=ByteArray(3);assertEquals(3,stream.read(bytes,0,3));assertArrayEquals(byteArrayOf(1,2,3),bytes);stream.close()
    }
    @Test fun redirectsCannotLeaveCdnAndLoopsStop(){
        for(target in listOf("http://b.googlevideo.com/videoplayback","https://evil.test/videoplayback","https://x.googlevideo.com.evil.test/videoplayback","https://b.googlevideo.com/login","https://user@b.googlevideo.com/videoplayback")){
            var requests=0;val stream=OnlineHttpStream(emptyMap()){requests++;Reply(302,target)}
            try{stream.open(URI("https://a.googlevideo.com/videoplayback"),0);fail()}catch(e:OnlineSourceException){assertEquals("audio_redirect_origin",e.reason)};assertEquals(1,requests)
        }
        val stream=OnlineHttpStream(emptyMap()){Reply(302,"https://a.googlevideo.com/videoplayback")}
        try{stream.open(URI("https://a.googlevideo.com/videoplayback"),0);fail()}catch(e:OnlineSourceException){assertEquals("audio_redirect_loop",e.reason)}
    }
}
