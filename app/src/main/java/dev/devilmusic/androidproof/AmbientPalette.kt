// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

/** Tiny spatial colour field. Pure arithmetic so contrast and spatial fidelity can be tested on the host. */
internal object AmbientPalette {
    const val SIDE=6
    private fun channel(color:Int,shift:Int)=(color ushr shift) and 255
    private fun rgb(r:Int,g:Int,b:Int)=0xff000000.toInt() or (r shl 16) or (g shl 8) or b
    fun tone(color:Int):Int {
        // Keep the source's proportions: a black sleeve with one red stripe stays mostly dark.
        // Even white artwork remains dark enough for pale foreground text.
        return rgb(12+channel(color,16)*96/255,14+channel(color,8)*96/255,20+channel(color,0)*96/255)
    }
    fun sample(width:Int,height:Int,pixel:(Int,Int)->Int):IntArray {
        require(width>0 && height>0)
        return IntArray(SIDE*SIDE) {index->
            val cellX=index%SIDE;val cellY=index/SIDE
            var r=0;var g=0;var b=0
            for(y in 0..3)for(x in 0..3) {
                val sx=((cellX+(x+.5)/4)*width/SIDE).toInt().coerceIn(0,width-1)
                val sy=((cellY+(y+.5)/4)*height/SIDE).toInt().coerceIn(0,height-1)
                val c=pixel(sx,sy);r+=channel(c,16);g+=channel(c,8);b+=channel(c,0)
            }
            tone(rgb(r/16,g/16,b/16))
        }
    }
    fun resting():IntArray {
        val anchors=intArrayOf(0xff176e83.toInt(),0xff6751a4.toInt(),0xff163e4a.toInt(),0xff0c3436.toInt(),0xff244769.toInt(),0xff71406b.toInt())
        return IntArray(SIDE*SIDE){i->tone(anchors[(i/SIDE)/2*2+(i%SIDE)/3])}
    }
}
