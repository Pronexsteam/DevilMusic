// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import android.graphics.*
import android.graphics.drawable.Drawable

/** Scalable collection covers, sharing the player's restrained metal and record materials. */
internal class CollectionArtwork(private val liked:Boolean):Drawable(){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val heart=Path().apply {moveTo(128f,189f);cubicTo(36f,135f,62f,57f,128f,105f);cubicTo(194f,57f,220f,135f,128f,189f);close()}
    override fun draw(c:Canvas){
        c.save();c.translate(bounds.left.toFloat(),bounds.top.toFloat());c.scale(bounds.width()/256f,bounds.height()/256f)
        p.style=Paint.Style.FILL;p.color=Color.WHITE;p.shader=LinearGradient(0f,0f,256f,256f,if(liked)intArrayOf(0xff14272b.toInt(),0xff253c40.toInt(),0xff10171d.toInt())else intArrayOf(0xff303942.toInt(),0xff181e28.toInt(),0xff10151d.toInt()),null,Shader.TileMode.CLAMP);c.drawRect(0f,0f,256f,256f,p)
        p.shader=null;p.style=Paint.Style.STROKE;p.strokeWidth=.65f;p.color=0x18d8e8e7
        for(i in 0..7)c.drawCircle(212f,36f,60f+i*24f,p)
        fun disc(x:Float,y:Float,r:Float,light:Boolean){
            p.style=Paint.Style.FILL;p.shader=null;p.color=0x60000000;c.drawCircle(x,y+5,r+2,p)
            p.color=Color.WHITE;p.shader=LinearGradient(x-r,y-r,x+r,y+r,intArrayOf(0xff4d5b60.toInt(),0xff151b20.toInt(),0xff090f14.toInt(),0xff344247.toInt()),floatArrayOf(0f,.22f,.65f,1f),Shader.TileMode.CLAMP);c.drawCircle(x,y,r,p)
            p.shader=null;p.style=Paint.Style.STROKE;p.strokeWidth=.65f;p.color=0x2479898d
            for(i in 0..8)c.drawCircle(x,y,r*.48f+i*r*.052f,p)
            p.strokeWidth=1.1f;p.color=0x8094a6aa.toInt();c.drawCircle(x,y,r-1,p)
            p.style=Paint.Style.FILL;p.color=Color.WHITE;p.shader=LinearGradient(x-r*.3f,y-r*.3f,x+r*.3f,y+r*.3f,if(light)0xffe1e8e7.toInt() else 0xff83989b.toInt(),0xff3c5359.toInt(),Shader.TileMode.CLAMP);c.drawCircle(x,y,r*.26f,p)
            p.shader=null;p.color=0xff11171d.toInt();c.drawCircle(x,y,r*.055f,p)
        }
        if(liked){
            p.shader=null;p.style=Paint.Style.STROKE;p.strokeWidth=1f;p.color=0x257f9c9f
            for(i in 0..4)c.drawCircle(128f,133f,83f+i*7f,p)
            c.save();c.translate(0f,5f);p.style=Paint.Style.FILL;p.color=0x6603090d;c.drawPath(heart,p);c.restore()
            p.color=Color.WHITE;p.shader=LinearGradient(90f,75f,155f,190f,intArrayOf(0xfff8fbfa.toInt(),0xffcedfdd.toInt(),0xff7b9699.toInt(),0xffdce8e5.toInt()),floatArrayOf(0f,.35f,.72f,1f),Shader.TileMode.CLAMP);c.drawPath(heart,p)
            p.shader=null;p.style=Paint.Style.STROKE;p.strokeWidth=1.3f;p.color=0xb0f5ffff.toInt();c.drawPath(heart,p)
        }else{
            disc(155f,98f,68f,false);disc(128f,128f,68f,false);disc(101f,158f,68f,true)
        }
        p.shader=null;p.style=Paint.Style.STROKE;p.strokeWidth=1f;p.color=0x16f4ffff;c.drawRect(.5f,.5f,255.5f,255.5f,p);c.restore()
    }
    override fun setAlpha(alpha:Int){}
    override fun setColorFilter(colorFilter:ColorFilter?){}
    @Deprecated("Drawable opacity") override fun getOpacity()=PixelFormat.OPAQUE
}
