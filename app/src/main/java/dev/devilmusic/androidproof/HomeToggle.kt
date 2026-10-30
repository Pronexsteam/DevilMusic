package dev.devilmusic.androidproof

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Checkable

/** Same geometry on every Android skin; the full 52 × 48 dp target remains accessible. */
internal class HomeToggle(context:Context):View(context),Checkable {
    private var checked=false
    private var fraction=0f
    private var motion:ValueAnimator?=null
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    var changed:(Boolean)->Unit={}
    init {isClickable=true;isFocusable=true;background=MusicStyle.touch(context,radius=16);setOnClickListener {toggle()}}
    override fun isChecked()=checked
    override fun toggle(){isChecked=!checked}
    override fun setChecked(value:Boolean){
        if(checked==value)return
        checked=value;motion?.cancel()
        val target=if(value)1f else 0f
        if(isLaidOut && ValueAnimator.areAnimatorsEnabled()){
            motion=ValueAnimator.ofFloat(fraction,target).apply {duration=160;addUpdateListener {fraction=it.animatedValue as Float;invalidate()};start()}
        }else {fraction=target;invalidate()}
        refreshDrawableState();changed(value);sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(info);info.className="android.widget.Switch";info.isCheckable=true;info.isChecked=checked}
    override fun onDetachedFromWindow(){motion?.cancel();fraction=if(checked)1f else 0f;super.onDetachedFromWindow()}
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        val d=resources.displayMetrics.density;val left=width/2f-22*d;val top=height/2f-13*d
        paint.style=Paint.Style.FILL;paint.color=Color.rgb((43+fraction*78).toInt(),(49+fraction*97).toInt(),(57+fraction*85).toInt())
        canvas.drawRoundRect(left,top,left+44*d,top+26*d,13*d,13*d,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=d;paint.color=0x40c4d5dc;canvas.drawRoundRect(left+.5f*d,top+.5f*d,left+43.5f*d,top+25.5f*d,13*d,13*d,paint)
        val x=left+(13+18*fraction)*d;val y=height/2f
        paint.style=Paint.Style.FILL;paint.color=0x40000000;canvas.drawCircle(x,y+d,10*d,paint)
        paint.color=MusicStyle.ink;canvas.drawCircle(x,y,9.5f*d,paint)
        if(fraction>.5f){paint.color=Color.argb(((fraction-.5f)*2*255).toInt(),43,65,61);paint.style=Paint.Style.STROKE;paint.strokeWidth=1.6f*d;paint.strokeCap=Paint.Cap.ROUND;canvas.drawLine(x-3*d,y,x-d,y+2*d,paint);canvas.drawLine(x-d,y+2*d,x+3*d,y-2*d,paint)}
    }
}
