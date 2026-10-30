package dev.devilmusic.androidproof

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class HomeRankingTest {
    @Test fun personalShelvesDoNotRepeatTracksOrInventReleaseDates(){
        val profile=(0..10).map {entry(it)}
        val shelves=HomeRanking.shelves(profile,(0..70).map {card(it)},now,7,null)
        assertTrue(shelves.size>=2)
        val ids=shelves.flatMap {it.items}.map {it.id}
        assertEquals(ids.distinct(),ids)
        assertFalse(shelves.any {it.title.contains("release",true) || it.title.contains("trending",true)})
        assertTrue(HomeRanking.shelves(emptyList(),(0..70).map {card(it)},now,7,null).isEmpty())
    }
    @Test fun homeCacheIgnoresRegionalShelvesAndStaleUnrelatedArtistSearches(){
        val root=Files.createTempDirectory("home-provenance").toFile()
        try {
            val cache=HomeDiscovery(root)
            cache.savePublic(listOf(MusicShelf("Local top",listOf(card(99,"Unrelated")))),now)
            cache.saveArtist("Loved artist",listOf(card(1,"Loved artist"),card(2,"Other artist"),card(3,"Loved artist & Friend")),now)
            cache.saveArtist("Old taste",listOf(card(4,"Old taste")),now)
            assertEquals(listOf(card(1,"Loved artist"),card(3,"Loved artist & Friend")),cache.candidatesFor(setOf("loved artist")))
            assertTrue(cache.candidatesFor(emptySet()).isEmpty())
        }finally{root.deleteRecursively()}
    }
    private val now=10*ListeningTaste.DAY
    private fun card(n:Int,artist:String="Artist ${n%5}")=MusicCard(n.toString().padStart(11,'0'),"song","Song $n",artist,null)
    private fun entry(n:Int,feedback:Int=0,played:Long=0)=TasteEntry(SavedTrack("youtube:${card(n).id}",card(n).title,card(n).subtitle,null),4.0,0.0,now,played,feedback)
    @Test fun personalizedRowsExcludeDislikesRecentAndCurrentAndLimitArtists() {
        val profile=listOf(entry(0),entry(1,-1),entry(2,played=now-1000),entry(3))
        val output=HomeRanking.personalized(profile,(0..50).map {card(it)},now,1,"download:${card(3).id}").flatMap {it.items}
        assertTrue(output.isNotEmpty())
        assertFalse(output.any {it.id in setOf(card(1).id,card(2).id,card(3).id)})
        assertTrue(output.groupingBy {it.subtitle}.eachCount().values.all {it<=3})
        assertEquals(output.size,output.distinctBy {it.id}.size)
        assertTrue(output.any {it.id==card(0).id});assertTrue(output.any {it.id!=card(0).id})
    }
    @Test fun refreshChangesPublicOrderAndPersonalSelectionWithoutDuplicatingCards() {
        val candidates=(0..60).map {card(it)};val profile=(0..4).map {entry(it)}
        val a=HomeRanking.personalized(profile,candidates,now,1,null)
        val b=HomeRanking.personalized(profile,candidates,now,500,null)
        assertNotEquals(a,b)
        val public=listOf(MusicShelf("Explore",candidates))
        assertNotEquals(HomeRanking.rotatePublic(public,1),HomeRanking.rotatePublic(public,2))
    }
    @Test fun emptyProfileDoesNotLabelOldCachedCandidatesAsPersonal() {
        assertTrue(HomeRanking.personalized(emptyList(),listOf(card(1)),now,0,null).isEmpty())
        assertTrue(HomeRanking.seeds(emptyList(),now,0).isEmpty())
    }
    @Test fun hiddenSongsCannotReappearInPublicDiscovery() {
        val output=HomeRanking.rotatePublic(listOf(MusicShelf("Discover",listOf(card(1),card(2)))),0,setOf("youtube:${card(1).id}"))
        assertEquals(listOf(card(2)),output.single().items)
    }
    @Test fun seedsRotateAcrossLikedArtistsInsteadOfOnlyTopOne() {
        val profile=(0..4).map {entry(it)}
        assertEquals(2,HomeRanking.seeds(profile,now,0).size)
        assertNotEquals(HomeRanking.seeds(profile,now,0),HomeRanking.seeds(profile,now,2))
    }
    @Test fun cacheExpiresAndRejectsStreamUrlsWithoutTouchingProfile() {
        val dir=Files.createTempDirectory("home-cache").toFile()
        try {
            val cache=HomeDiscovery(dir);assertTrue(cache.public().second.isEmpty())
            assertTrue(cache.due("Artist",now));cache.saveArtist("Artist",listOf(card(1).copy(artwork="https://bad.googlevideo.com/token")),now)
            assertFalse(cache.due("Artist",now+1000));assertTrue(cache.due("Artist",now+HomeDiscovery.TTL+1));assertNull(cache.candidates().single().artwork)
            cache.savePublic(listOf(MusicShelf("Discover",listOf(card(2)))),now)
            assertEquals(card(2),HomeDiscovery(dir).public().second.single().items.single())
            assertEquals(1,cache.candidates().size)
        }finally{dir.deleteRecursively()}
    }
}
