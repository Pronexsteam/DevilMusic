// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof
import android.content.Context
import android.graphics.*
import android.widget.ImageView

/** Same picture-disc treatment as desktop: artwork fills the disc, with a fine rim and centre hole. */
internal class VinylArtwork(context:Context):ImageView(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG);private val clip=Path();private var angle=0f;private var last=0L
    private var reflection:Shader?=null
    private var bevel:Shader?=null
    private var hub:Shader?=null
    private var light=0f
    private val source=Rect()
    private val printArea=RectF()
    private val artworkPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val material=material(context)
    private val materialArea=RectF()
    companion object {
        @Volatile private var sharedMaterial:Bitmap?=null
        private fun material(context:Context):Bitmap = sharedMaterial ?: synchronized(this) {
            sharedMaterial ?: BitmapFactory.decodeResource(context.resources,R.drawable.vinyl_material,
                BitmapFactory.Options().apply {inScaled=false}).also {sharedMaterial=it}
        }
    }
    fun pulse(value:VisualPulse){light=light*.85f+value.glint*.15f;if(isShown)invalidate()}
    init {scaleType=ScaleType.CENTER_CROP}
    fun tick(playing:Boolean){val now=android.os.SystemClock.uptimeMillis();if(playing && last!=0L)angle=(angle+(now-last).coerceAtMost(100)*.018f)%360;last=if(playing)now else 0;if(playing)invalidate()}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
        val radius=minOf(w,h)/2f
        materialArea.set(w/2f-radius,h/2f-radius,w/2f+radius,h/2f+radius)
        printArea.set(w/2f-radius*.89f,h/2f-radius*.89f,w/2f+radius*.89f,h/2f+radius*.89f)
        clip.reset();clip.addCircle(w/2f,h/2f,minOf(w,h)*.445f,Path.Direction.CW)
        reflection=SweepGradient(w/2f,h/2f,intArrayOf(0x00ffffff,0x35d9e2e7,0x00ffffff,0x00ffffff,0x20d9e2e7,0x00ffffff),floatArrayOf(0f,.13f,.25f,.5f,.64f,1f))
        bevel=LinearGradient(w/2f-radius,h/2f-radius,w/2f+radius,h/2f+radius,intArrayOf(0xffa1a8ac.toInt(),0xff25292c.toInt(),0xff060709.toInt(),0xff667176.toInt()),floatArrayOf(0f,.25f,.65f,1f),Shader.TileMode.CLAMP)
        hub=RadialGradient(w/2f,h/2f,(minOf(w,h)*.09f).coerceAtLeast(1f),intArrayOf(0x33000000,0x66101418,0x18ffffff,0x00000000),floatArrayOf(0f,.72f,.88f,1f),Shader.TileMode.CLAMP)
    }
    fun turnBy(degrees:Float){angle=(angle+degrees)%360;invalidate()}
    fun rotationPhase()=angle
    fun restorePhase(value:Float){angle=value%360;invalidate()}
    override fun onDraw(canvas:Canvas){
        val x=width/2f;val y=height/2f;val r=minOf(width,height)/2f
        paint.shader=null;paint.style=Paint.Style.FILL;paint.color=0x55000000;canvas.drawCircle(x,y+r*.025f,r*.97f,paint)
        paint.shader=bevel;canvas.drawCircle(x,y,r*.955f,paint);paint.shader=null
        paint.color=0xff111316.toInt();canvas.drawCircle(x,y,r*.94f,paint)
        val save=canvas.save();canvas.clipPath(clip);canvas.rotate(angle,x,y)
        // ImageView CENTER_CROP uses the entire rectangular slot, causing different zoom
        // in portrait, landscape and mini layouts. Print to the same disc-relative square.
        val picture=drawable
        val bitmap=(picture as? android.graphics.drawable.BitmapDrawable)?.bitmap
        if(bitmap!=null && !bitmap.isRecycled){
            val side=minOf(bitmap.width,bitmap.height);val left=(bitmap.width-side)/2;val top=(bitmap.height-side)/2
            source.set(left,top,left+side,top+side);canvas.drawBitmap(bitmap,source,printArea,artworkPaint)
        }else if(picture!=null){
            val old=Rect(picture.bounds);picture.setBounds(printArea.left.toInt(),printArea.top.toInt(),printArea.right.toInt(),printArea.bottom.toInt());picture.draw(canvas);picture.bounds=old
        }
        canvas.restoreToCount(save)
        // One shared Blender-rendered lacquer/edge texture; live cover remains independent.
        // The material has transparent printing area and fixed studio lighting.
        canvas.drawBitmap(material,null,materialArea,artworkPaint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=(r*.002f).coerceAtLeast(.6f)
        for(n in 0..34){paint.color=if(n%3==0)0x197f8b94 else 0x19000000;canvas.drawCircle(x,y,r*(.40f+n*.014f),paint)}
        paint.color=0x806f7980.toInt();canvas.drawCircle(x,y,r*.925f,paint)
        // Studio reflection stays in screen space while the printed disc rotates underneath it.
        paint.style=Paint.Style.FILL;paint.shader=reflection;paint.alpha=(light*60).toInt().coerceIn(0,60);canvas.drawCircle(x,y,r*.92f,paint);paint.shader=null;paint.alpha=255
        paint.style=Paint.Style.STROKE;paint.color=0x466d7780;paint.strokeWidth=r*.012f;canvas.drawCircle(x,y,r*.16f,paint)
        paint.style=Paint.Style.FILL;paint.shader=hub;canvas.drawCircle(x,y,r*.18f,paint);paint.shader=null
        paint.color=0x99a7adb1.toInt();paint.strokeWidth=r*.006f;canvas.drawCircle(x,y,r*.047f,paint)
        paint.style=Paint.Style.FILL;paint.color=0xff08090c.toInt();canvas.drawCircle(x,y,r*.041f,paint)
    }
}
