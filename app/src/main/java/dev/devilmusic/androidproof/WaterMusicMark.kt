// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof
import android.content.Context
import android.graphics.*
import android.view.View

/** Quiet smoked-glass signature. Music animates the player material, not the brand silhouette. */
internal class WaterMusicMark(context:Context):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val contour=Path().apply {
        moveTo(40f,18f);lineTo(74f,10f);quadTo(79f,9f,79f,15f);lineTo(79f,61f)
        cubicTo(79f,69f,72f,74f,65f,74f);cubicTo(57f,74f,54f,70f,56f,64f)
        cubicTo(59f,58f,66f,57f,72f,58f);lineTo(72f,29f);lineTo(45f,35f);lineTo(45f,69f)
        cubicTo(45f,78f,37f,84f,29f,84f);cubicTo(21f,84f,18f,80f,20f,74f)
        cubicTo(22f,67f,30f,64f,37f,65f);lineTo(37f,23f);quadTo(37f,19f,40f,18f);close()
    }

    private val bounds=RectF().also {contour.computeBounds(it,true)}
    private val plate=LinearGradient(0f,0f,96f,96f,intArrayOf(0xff24282c.toInt(),0xff111316.toInt(),0xff1b1e22.toInt()),null,Shader.TileMode.CLAMP)
    private val silver=LinearGradient(0f,12f,80f,90f,intArrayOf(0xfff1f2f3.toInt(),0xffb6bec3.toInt(),0xffe0e5e7.toInt()),null,Shader.TileMode.CLAMP)
    init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    fun tint(color:Int) { /* Neutral material deliberately stays legible across cover colours. */ }
    fun pulse(value:VisualPulse) { /* No extra brand animation or per-frame invalidation. */ }
    override fun onDraw(canvas:Canvas) {
        val s=minOf(width,height)/108f;canvas.save();canvas.translate((width-96*s)/2,(height-96*s)/2);canvas.scale(s,s)
        paint.alpha=255;paint.style=Paint.Style.FILL;paint.shader=plate;canvas.drawRoundRect(0f,0f,96f,96f,23f,23f,paint)
        paint.shader=null;paint.style=Paint.Style.STROKE;paint.strokeWidth=.7f;paint.color=0x397b858c;canvas.drawRoundRect(.5f,.5f,95.5f,95.5f,23f,23f,paint)
        canvas.translate(48f,48f);canvas.scale(.8f,.8f);canvas.translate(-bounds.centerX(),-bounds.centerY())
        paint.alpha=255;paint.style=Paint.Style.FILL;paint.shader=silver;canvas.drawPath(contour,paint);paint.shader=null;canvas.restore()
    }
}
