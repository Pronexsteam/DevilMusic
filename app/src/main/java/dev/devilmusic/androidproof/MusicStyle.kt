// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.TextView

internal object MusicStyle {
    val background=Color.rgb(8,9,12)
    val surface=Color.rgb(16,17,21)
    val ink=Color.rgb(241,244,246)
    val muted=Color.rgb(180,182,192)
    val accent=Color.rgb(239,242,245)
    private var appFace:Typeface?=null
    fun face(context:Context):Typeface=appFace ?: context.resources.getFont(R.font.manrope).also {appFace=it}
    fun dp(context:Context,n:Int)=(n*context.resources.displayMetrics.density).toInt()
    fun box(context:Context,color:Int=surface,radius:Int=18,line:Boolean=false)=GradientDrawable().apply {
        setColor(color);cornerRadius=dp(context,radius).toFloat()
        if(line)setStroke(dp(context,1).coerceAtLeast(1),Color.argb(22,255,255,255))
    }
    fun touch(context:Context,color:Int=Color.TRANSPARENT,radius:Int=18,line:Boolean=false)=RippleDrawable(ColorStateList.valueOf(Color.argb(35,210,245,232)),box(context,color,radius,line),box(context,Color.WHITE,radius))
    fun control(context:Context,primary:Boolean=false,inset:Int=0):Drawable {
        val surface=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,if(primary)intArrayOf(0xfff3f6f5.toInt(),0xffd7e1df.toInt()) else intArrayOf(0xff262c34.toInt(),0xff1a1e25.toInt())).apply {
            cornerRadius=dp(context,15).toFloat();setStroke(dp(context,1).coerceAtLeast(1),if(primary)0x80ffffff.toInt() else 0x26c4d5dc)
        }
        val ripple=RippleDrawable(ColorStateList.valueOf(if(primary)0x25809b97 else 0x22dce9e7),surface,box(context,Color.WHITE,15))
        return if(inset>0)android.graphics.drawable.InsetDrawable(ripple,dp(context,inset)) else ripple
    }
    fun panel(context:Context,radius:Int=22)=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(0xd0101115.toInt(),0xd90d0e13.toInt())).apply {
        cornerRadius=dp(context,radius).toFloat();setStroke(dp(context,1).coerceAtLeast(1),0x20dce4f3)
    }
    fun text(context:Context,size:Float,color:Int=ink,bold:Boolean=false,lines:Int=1)=TextView(context).apply {
        textSize=size;setTextColor(color);typeface=face(context)
        fontVariationSettings="'wght' ${if(bold)650 else 450}"
        letterSpacing=if(size>=24f)-.035f else if(size>=18f)-.02f else 0f
        maxLines=lines;ellipsize=android.text.TextUtils.TruncateAt.END;includeFontPadding=false
    }

}

internal class AmbientSurface(colors:IntArray=AmbientPalette.resting()):Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val mesh=Bitmap.createBitmap(colors,AmbientPalette.SIDE,AmbientPalette.SIDE,Bitmap.Config.ARGB_8888)
    private val destination=RectF()
    private var shade:Shader?=null
    private var glow:Shader?=null
    private val glowColor=colors[colors.size-5].let {Color.rgb((Color.red(it)*2).coerceAtMost(220),(Color.green(it)*2).coerceAtMost(220),(Color.blue(it)*2).coerceAtMost(230))}
    private var pulse=0f
    fun pulse(value:Float){if(kotlin.math.abs(value-pulse)>.005f){pulse=value.coerceIn(0f,1f);invalidateSelf()}}
    override fun onBoundsChange(bounds:Rect) {
        destination.set(bounds)
        shade=LinearGradient(0f,bounds.top.toFloat(),0f,bounds.bottom.toFloat().coerceAtLeast(1f),intArrayOf(0x08080a0e,0x20080a0e,0x88080a0e.toInt()),floatArrayOf(0f,.5f,1f),Shader.TileMode.CLAMP)
        glow=RadialGradient(bounds.exactCenterX(),bounds.bottom.toFloat(),maxOf(bounds.width(),bounds.height(),1).toFloat(),intArrayOf(glowColor,Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
    }
    override fun draw(canvas:Canvas){val alpha=paint.alpha;paint.shader=null;canvas.drawColor(0xff08090c.toInt());paint.alpha=(alpha*.38f).toInt();canvas.drawBitmap(mesh,null,destination,paint);paint.alpha=alpha;paint.shader=shade;canvas.drawRect(bounds,paint)
        if(pulse>.005f){paint.shader=glow;paint.alpha=(alpha*pulse*.26f).toInt();canvas.drawRect(bounds,paint)}
        paint.alpha=alpha;paint.shader=null}
    override fun setAlpha(alpha:Int){paint.alpha=alpha}
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter}
    @Deprecated("Drawable opacity") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

internal class MusicGlyph(context:Context,var symbol:String,private var tone:Int=MusicStyle.ink):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val forwardTriangle=Path().apply {moveTo(19f,16f);lineTo(31f,24f);lineTo(19f,32f);close()}
    private val reverseTriangle=Path().apply {moveTo(29f,16f);lineTo(17f,24f);lineTo(29f,32f);close()}
    private val brandCurve=Path().apply {moveTo(19f,28f);cubicTo(20f,13f,27f,35f,29f,20f)}
    override fun getAccessibilityClassName():CharSequence="android.widget.Button"
    fun update(name:String,color:Int=tone){if(symbol==name && tone==color)return;symbol=name;tone=color;invalidate()}
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas);val scale=minOf(width,height)/48f
        canvas.save();canvas.translate(width/2f-24*scale,height/2f-24*scale);canvas.scale(scale,scale)
        paint.color=tone;paint.alpha=if(isEnabled)255 else 75;paint.strokeWidth=1.8f;paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.ROUND;paint.style=Paint.Style.STROKE
        fun line(x:Float,y:Float,a:Float,b:Float){paint.style=Paint.Style.STROKE;canvas.drawLine(x,y,a,b,paint)}
        fun triangle(reverse:Boolean=false){paint.style=Paint.Style.FILL;canvas.drawPath(if(reverse)reverseTriangle else forwardTriangle,paint)}
        when(symbol) {
            "close"->{line(19f,19f,29f,29f);line(29f,19f,19f,29f)}
            "expand"->{line(14f,21f,14f,14f);line(14f,14f,21f,14f);line(27f,14f,34f,14f);line(34f,14f,34f,21f);line(34f,27f,34f,34f);line(34f,34f,27f,34f);line(21f,34f,14f,34f);line(14f,34f,14f,27f)}
            "refresh"->{canvas.drawArc(RectF(14f,14f,34f,34f),40f,290f,false,paint);line(28f,19f,34f,19f);line(34f,13f,34f,19f)}
            "discover"->{canvas.drawCircle(24f,24f,11f,paint);val needle=Path().apply {moveTo(29f,18f);lineTo(26f,27f);lineTo(19f,30f);lineTo(22f,21f);close()};canvas.drawPath(needle,paint);paint.style=Paint.Style.FILL;canvas.drawCircle(24f,24f,1.3f,paint)}
            "collection"->{canvas.drawRoundRect(15f,17f,30f,34f,3f,3f,paint);line(20f,13f,34f,13f);line(34f,13f,34f,29f);line(20f,23f,25f,23f);line(20f,28f,25f,28f)}
            "offline"->{line(24f,13f,24f,28f);line(19f,23f,24f,28f);line(24f,28f,29f,23f);canvas.drawRoundRect(14f,31f,34f,35f,2f,2f,paint)}
            "upnext"->{line(23f,16f,34f,16f);line(23f,24f,34f,24f);line(14f,32f,34f,32f);paint.style=Paint.Style.FILL;val p=Path().apply {moveTo(14f,14f);lineTo(20f,20f);lineTo(14f,26f);close()};canvas.drawPath(p,paint)}
            "play"->triangle()
            "pause"->{paint.style=Paint.Style.FILL;canvas.drawRoundRect(17f,15f,22f,33f,1.5f,1.5f,paint);canvas.drawRoundRect(27f,15f,32f,33f,1.5f,1.5f,paint)}
            "next"->{triangle();line(33f,16f,33f,32f)}
            "previous"->{triangle(true);line(15f,16f,15f,32f)}
            "down"->{line(17f,21f,24f,28f);line(24f,28f,31f,21f)}
            "back"->{line(27f,17f,20f,24f);line(20f,24f,27f,31f)}
            "forward"->{line(17f,24f,31f,24f);line(25f,18f,31f,24f);line(31f,24f,25f,30f)}
            "menu"->{paint.style=Paint.Style.FILL;for(i in 0..2)canvas.drawCircle(17f+i*7f,24f,1.6f,paint)}
            "add"->{line(24f,15f,24f,33f);line(15f,24f,33f,24f)}
            "heart","heart_filled"->{if(symbol=="heart_filled")paint.style=Paint.Style.FILL;val heart=Path().apply {moveTo(24f,35f);cubicTo(5f,24f,12f,9f,24f,18f);cubicTo(36f,9f,43f,24f,24f,35f);close()};canvas.drawPath(heart,paint)}
            "search"->{canvas.drawCircle(22f,22f,7f,paint);line(27f,27f,33f,33f)}
            "clear"->{line(16f,18f,32f,18f);line(20f,14f,28f,14f);line(18f,21f,19f,34f);line(19f,34f,29f,34f);line(29f,34f,30f,21f)}
            "shuffle"->{line(14f,17f,32f,31f);line(14f,31f,32f,17f);line(28f,17f,33f,17f);line(33f,17f,33f,22f);line(28f,31f,33f,31f)}
            "repeat","repeat_one"->{line(15f,18f,33f,18f);line(33f,18f,33f,26f);line(33f,30f,15f,30f);line(15f,30f,15f,22f);line(29f,14f,33f,18f);line(19f,34f,15f,30f);if(symbol=="repeat_one"){paint.style=Paint.Style.FILL;paint.textSize=10f;paint.textAlign=Paint.Align.CENTER;canvas.drawText("1",24f,28f,paint)}}
            "home","brand"->{canvas.drawCircle(24f,24f,11f,paint);canvas.drawPath(brandCurve,paint);if(symbol=="brand")canvas.drawCircle(24f,24f,16f,paint)}
            "library"->{canvas.drawRoundRect(14f,15f,21f,33f,2f,2f,paint);canvas.drawRoundRect(24f,15f,31f,33f,2f,2f,paint);line(34f,16f,37f,32f)}
            "download"->{line(24f,13f,24f,29f);line(18f,24f,24f,30f);line(24f,30f,30f,24f);line(14f,30f,14f,35f);line(14f,35f,34f,35f);line(34f,35f,34f,30f)}
            "queue"->{for(i in 0..2){val y=17f+i*7f;line(21f,y,34f,y);paint.style=Paint.Style.FILL;canvas.drawCircle(15f,y,1f,paint)}}
            "grip"->{paint.style=Paint.Style.FILL;for(x in listOf(20f,28f))for(y in listOf(17f,24f,31f))canvas.drawCircle(x,y,1.5f,paint)}
            "tune"->{for(i in 0..2){val y=17f+i*7f;line(14f,y,34f,y);paint.style=Paint.Style.FILL;canvas.drawCircle(if(i==1)28f else 20f,y,3f,paint)}}
        }
        canvas.restore()
    }
}

/** Deterministic abstract artwork, not a fabricated album cover or a source label. */
internal class MusicFallback(private val title:String="",private val compact:Boolean=false):Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val seed=title.hashCode().toLong().and(0xffffffffL)
    private val hue=(seed%360).toFloat()
    private val shade=Color.HSVToColor(floatArrayOf(hue,.48f,.36f))
    private val highlight=Color.HSVToColor(floatArrayOf((hue+45)%360,.32f,.82f))
    private val shader=LinearGradient(0f,0f,256f,256f,intArrayOf(shade,Color.HSVToColor(floatArrayOf((hue+65)%360,.4f,.19f)),Color.rgb(11,17,22)),null,Shader.TileMode.CLAMP)
    private val curve=Path().apply {moveTo(-30f,184f);cubicTo(40f,-30f,150f,300f,290f,54f)}
    private val face=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    private val initials=title.trim().split(Regex("\\s+")).filter {it.isNotBlank()}.take(2).joinToString(""){it.take(1)}.uppercase().ifBlank {"M"}
    override fun draw(canvas:Canvas) {
        canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat());canvas.scale(bounds.width()/256f,bounds.height()/256f)
        paint.style=Paint.Style.FILL;paint.shader=shader;canvas.drawRect(0f,0f,256f,256f,paint);paint.shader=null
        canvas.save();canvas.rotate((seed%120-60).toFloat(),128f,128f)
        paint.color=highlight;paint.alpha=48;canvas.drawCircle(176f,64f,96f,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=26f;paint.alpha=70;canvas.drawPath(curve,paint)
        paint.strokeWidth=1.5f;paint.alpha=100;canvas.translate(0f,24f);canvas.drawPath(curve,paint);canvas.restore()
        paint.style=Paint.Style.FILL;paint.color=MusicStyle.ink;paint.alpha=220;paint.textSize=if(compact)38f else 48f;paint.typeface=face
        canvas.drawText(initials,22f,if(compact)220f else 202f,paint)
        if(!compact){paint.textSize=10f;paint.alpha=130;canvas.drawText("DEVIL MUSIC",24f,225f,paint)}
        canvas.restore()
    }
    override fun setAlpha(alpha:Int){paint.alpha=alpha}
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter}
    @Deprecated("Drawable opacity") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
