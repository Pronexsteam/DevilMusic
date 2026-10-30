package dev.devilmusic.androidproof
import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files

class ArtworkDiskCacheTest {
    @Test fun returningToArtworkReusesBytesAfterRestartAndEvictsLeastRecentlyUsed(){
        val root=Files.createTempDirectory("covers").toFile()
        try {val cache=ArtworkDiskCache(root,10);cache.put("a",byteArrayOf(1,2,3,4),1000);cache.put("b",byteArrayOf(5,6,7,8),2000)
            assertArrayEquals(byteArrayOf(1,2,3,4),ArtworkDiskCache(root,10).get("a",3000))
            cache.put("c",byteArrayOf(9,10,11,12),4000)
            assertNull(cache.get("b",5000));assertNotNull(cache.get("a",5000));assertNotNull(cache.get("c",5000))
            assertTrue(root.listFiles()!!.sumOf {it.length()}<=10)
            assertNull(cache.get("a",5000+15L*24*3600000))
        }finally{root.deleteRecursively()}
    }
}
