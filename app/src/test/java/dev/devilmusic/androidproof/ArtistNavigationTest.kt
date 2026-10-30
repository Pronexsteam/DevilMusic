package dev.devilmusic.androidproof

import org.junit.Assert.*
import org.junit.Test

class ArtistNavigationTest {
    @Test fun opensOnlyOneExactArtistAndKeepsBandNamesIntact(){
        val artist=MusicCard("UCfirst","artist","Earth, Wind & Fire","Artist",null)
        assertEquals(artist,ArtistNavigation.exact("Earth, Wind & Fire - Topic",listOf(artist)))
        assertEquals("Earth, Wind & Fire",ArtistNavigation.name("Earth, Wind & Fire - Topic"))
        assertEquals(artist,ArtistNavigation.exact("Earth, Wind & Fire · 10M plays · Album",listOf(artist)))
        assertNull(ArtistNavigation.exact("Earth",listOf(artist)))
        assertNull(ArtistNavigation.exact(artist.title,listOf(artist,artist.copy(id="UCnamesake"))))
        assertEquals(artist,ArtistNavigation.exact(artist.title,listOf(artist,artist)))
    }
}
