package dev.devilmusic.androidproof
import org.junit.Test
import org.junit.Assert.*
import com.grack.nanojson.JsonObject

class LyricsfileTest {
    private val sample="""
        version: '1.0'
        lines:
          - text: 'Timing fixture'
            start_ms: 1000
            end_ms: 4000
            words:
              - {text: 'Timing ', start_ms: 1000, end_ms: 1800}
              - {text: 'fixture', start_ms: 2400, end_ms: 3500}
    """.trimIndent()
    @Test fun usesRealWordIntervalsAndPreservesSilence(){
        val value=LyricsRepository.decode(JsonObject().apply {put("lyricsfile",sample);put("syncedLyrics","[00:01.00]Timing fixture")})
        assertEquals(2,value.lines.single().words.size);assertEquals(3500L,value.lines.single().words.last().end)
        assertTrue(value.lines.single().words.none {it.time<=2100 && 2100<it.end!!})
    }
    @Test fun malformedOrUnsafeYamlFallsBackToLineTiming(){
        for(yaml in listOf(sample.replace("end_ms: 1800","end_ms: 900"),"!!java.lang.ProcessBuilder {}",sample.replace("'1.0'","'9.0'"))){
            val value=LyricsRepository.decode(JsonObject().apply {put("lyricsfile",yaml);put("syncedLyrics","[00:01.00]Timing fixture")})
            assertEquals(1,value.lines.size);assertTrue(value.lines[0].words.isEmpty())
        }
    }
}
