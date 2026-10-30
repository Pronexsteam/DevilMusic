package dev.devilmusic.androidproof
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

class CompleteAudioDownloadTest {
    private fun response(range:String,body:String,status:Int=206)="HTTP/1.1 $status Test\r\nContent-Length: ${body.length}\r\nContent-Range: bytes $range\r\nConnection: close\r\n\r\n$body"
    private fun fixture(responses:List<String>,block:(CompleteAudioDownload,MutableList<String>)->Unit) {
        val server=ServerSocket(0,4,InetAddress.getByName("127.0.0.1")).apply {soTimeout=5000}
        val ranges=java.util.Collections.synchronizedList(mutableListOf<String>());val failure=AtomicReference<Throwable?>()
        val thread=Thread {
            try {responses.forEach {response->server.accept().use {socket->socket.soTimeout=5000;val reader=socket.getInputStream().bufferedReader();while(true){val line=reader.readLine() ?: break;if(line.isEmpty())break;if(line.startsWith("Range:",true))ranges.add(line.substringAfter(':').trim())};socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII));socket.getOutputStream().flush()}}}
            catch(e:Throwable){failure.set(e)}
        }.apply {isDaemon=true;start()}
        try {block(CompleteAudioDownload {URL("http://127.0.0.1:${server.localPort}/").openConnection() as HttpURLConnection},ranges);thread.join(6000);assertFalse(thread.isAlive);failure.get()?.let {throw AssertionError(it)}}
        finally {server.close();thread.join(1000)}
    }
    @Test fun capped206ResponsesBecomeOneCompleteFile() {
        val dir=Files.createTempDirectory("complete-download").toFile();val file=java.io.File(dir,"audio")
        try {fixture(listOf(response("0-3/10","abcd"),response("4-9/10","efghij"))) {download,ranges->
            val size=download.run(URI("https://test.googlevideo.com/videoplayback"),emptyMap(),file,{}, {_,_->})
            assertEquals(10L,size);assertEquals("abcdefghij",file.readText());assertEquals(listOf("bytes=0-","bytes=4-"),ranges)
            assertFalse(java.io.File(dir,"audio.part").exists())
        }}finally {dir.deleteRecursively()}
    }
    @Test fun denialAfterFirstChunkNeverPublishesPartialFile() {
        val dir=Files.createTempDirectory("denied-download").toFile();val file=java.io.File(dir,"audio")
        try {fixture(listOf(response("0-3/10","abcd"),response("4-9/10","",403))) {download,ranges->
            try {download.run(URI("https://test.googlevideo.com/videoplayback"),emptyMap(),file,{}, {_,_->});fail("Expected refusal")}
            catch(e:OnlineSourceException){assertEquals("audio_http_403",e.reason)}
            assertFalse(file.exists());assertFalse(java.io.File(dir,"audio.part").exists());assertEquals(2,ranges.size)
        }}finally {dir.deleteRecursively()}
    }
    @Test fun changedTotalCannotBePublished() {
        val dir=Files.createTempDirectory("changed-download").toFile();val file=java.io.File(dir,"audio")
        try {fixture(listOf(response("0-3/10","abcd"),response("4-9/11","efghij"))) {download,_->
            try {download.run(URI("https://test.googlevideo.com/videoplayback"),emptyMap(),file,{}, {_,_->});fail("Expected mismatch")}
            catch(e:OnlineSourceException){assertEquals("download_size_unverified",e.reason)}
            assertFalse(file.exists());assertFalse(java.io.File(dir,"audio.part").exists())
        }}finally {dir.deleteRecursively()}
    }
}
