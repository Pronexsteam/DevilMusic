// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable

/** A single transient cover above the native layout; never owns or recreates playback. */
internal class CoverTransitionDrawable(private val art:Drawable):Drawable() {
    var radius=0f
    private val clip=Path()
    private val rect=RectF()
    private val imageRect=RectF()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    override fun draw(canvas:Canvas) {
        rect.set(bounds);clip.rewind();clip.addRoundRect(rect,radius,radius,Path.Direction.CW)
        val saved=canvas.save();canvas.clipPath(clip)
        val bitmap=(art as? BitmapDrawable)?.bitmap
        if(bitmap!=null) {
            canvas.drawColor(Color.rgb(8,10,14))
            val fit=bitmap.width>bitmap.height*1.25 || bitmap.height>bitmap.width*1.25
            val scale=if(fit)minOf(rect.width()/bitmap.width,rect.height()/bitmap.height) else maxOf(rect.width()/bitmap.width,rect.height()/bitmap.height)
            val halfW=bitmap.width*scale/2;val halfH=bitmap.height*scale/2
            imageRect.set(rect.centerX()-halfW,rect.centerY()-halfH,rect.centerX()+halfW,rect.centerY()+halfH)
            canvas.drawBitmap(bitmap,null,imageRect,paint)
        }else{art.bounds=bounds;art.draw(canvas)}
        canvas.restoreToCount(saved)
    }
    override fun setAlpha(alpha:Int){art.alpha=alpha;paint.alpha=alpha}
    override fun setColorFilter(colorFilter:ColorFilter?){art.colorFilter=colorFilter;paint.colorFilter=colorFilter}
    @Deprecated("Drawable opacity") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
