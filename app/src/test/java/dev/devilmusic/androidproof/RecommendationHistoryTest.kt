package dev.devilmusic.androidproof
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class RecommendationHistoryTest {
    private val now=20*ListeningTaste.DAY
    private fun card(i:Int,artist:String="Artist ${i%12}")=MusicCard(i.toString().padStart(11,'0'),"song","Track $i",artist,null)
    @Test fun publicShelvesDoNotRepeatTheSameSongAcrossRows(){
        val output=HomeRanking.rotatePublic(listOf(MusicShelf("A",listOf(card(1),card(2))),MusicShelf("B",listOf(card(1),card(3)))),1).flatMap {it.items}
        assertEquals(3,output.size);assertEquals(3,output.map {it.id}.toSet().size)
    }
    @Test fun recentRecommendationsRotateWithoutInventingDislikes(){
        val profile=(0..5).map {i->TasteEntry(SavedTrack("youtube:${card(i).id}","Track $i",card(i).subtitle,null),4.0,0.0,now,0,1)}
        val candidates=(0..240).map {card(it)}
        val first=HomeRanking.personalized(profile,candidates,now,1,null).single().items
        val next=HomeRanking.personalized(profile,candidates,now+1000,2,null,first.associate {it.id to now}).single().items
        assertTrue(next.count {c->first.none {it.id==c.id}}>=8)
        assertTrue(next.groupingBy {it.subtitle}.eachCount().values.all {it<=3})
        assertTrue(next.any {it.id in (0..5).map {n->card(n).id}})
        assertEquals(16,next.size)
    }
    @Test fun unknownArtistsAreNotAllMistakenForTheSameArtist(){
        val profile=listOf(TasteEntry(SavedTrack("youtube:${card(0).id}","Known","Known artist",null),4.0,0.0,now))
        val selected=HomeRanking.personalized(profile,(1..40).map {card(it,"")},now,9,null).single().items
        assertEquals(16,selected.size)
    }
    @Test fun boundedExposureHistoryIsIndependentFromLibraryAndTaste(){
        val dir=Files.createTempDirectory("exposure-history").toFile()
        try {
            val untouched=File(dir,"listening-taste.json").apply {writeText("{\"tracks\":[]}")};val before=untouched.readBytes()
            val history=RecommendationHistory(dir);history.record((0..1200).map {card(it)},now)
            assertEquals(RecommendationHistory.LIMIT,history.read(now).size)
            assertTrue(history.read(now+RecommendationHistory.TTL+1).isEmpty());assertTrue(history.read(now-1).isEmpty())
            assertArrayEquals(before,untouched.readBytes());history.reset();assertTrue(history.read(now).isEmpty())
        }finally{dir.deleteRecursively()}
    }
}
