package dev.devilmusic.androidproof

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class AmbientPaletteTest {
    @Test fun smallArtworkUsesValidCoordinatesAndBoundedWork() {
        var samples=0
        val colors=AmbientPalette.sample(1,1){x,y->assertEquals(0,x);assertEquals(0,y);samples++;0xff123456.toInt()}
        assertEquals(576,samples)
        assertEquals(36,colors.size)
        assertTrue(colors.all {it==AmbientPalette.tone(0xff123456.toInt())})
    }
    @Test fun retainsSpatialColoursInsteadOfTintingEverythingWithAccent() {
        val colors=AmbientPalette.sample(60,60){x,_->if(x<10)0xffff0000.toInt() else 0xff000000.toInt()}
        assertEquals(6,colors.count {it==AmbientPalette.tone(0xffff0000.toInt())})
        assertEquals(30,colors.count {it==AmbientPalette.tone(0xff000000.toInt())})
    }
    @Test fun brightestArtworkStillSupportsMainTextContrast() {
        fun luminance(c:Int):Double {
            fun channel(shift:Int):Double {val v=((c ushr shift) and 255)/255.0;return if(v<=.04045)v/12.92 else ((v+.055)/1.055).pow(2.4)}
            return .2126*channel(16)+.7152*channel(8)+.0722*channel(0)
        }
        val white=AmbientPalette.tone(0xffffffff.toInt())
        assertTrue((luminance(0xfff1f4f6.toInt())+.05)/(luminance(white)+.05)>=4.5)
        assertTrue(AmbientPalette.resting().toSet().size>=4)
    }
}
