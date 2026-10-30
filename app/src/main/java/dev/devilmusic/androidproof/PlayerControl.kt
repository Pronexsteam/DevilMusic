// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable

/** Shared transport styling; the hit area stays still while the inset surface responds. */
internal class PlayerControl(context:Context,symbol:String,label:String,private val primary:Boolean=false,click:()->Unit):MusicGlyph(context,symbol) {
    init {
        contentDescription=label;isClickable=true;isFocusable=true
        tooltipText=label
        setOnClickListener {click()}
        background=surface(false)
        update(symbol,if(primary)MusicStyle.background else MusicStyle.ink)
    }
    private fun surface(active:Boolean):android.graphics.drawable.Drawable {
        val light=primary || active
        val shape=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,if(light)intArrayOf(0xfff2f7f5.toInt(),0xffd2dfda.toInt()) else intArrayOf(0x503a4149,0x3821262e)).apply {
            cornerRadius=MusicStyle.dp(context,if(primary)40 else 20).toFloat()
            setStroke(MusicStyle.dp(context,1).coerceAtLeast(1),if(light)0x90ffffff.toInt() else 0x20dce3e8)
        }
        return InsetDrawable(RippleDrawable(ColorStateList.valueOf(if(light)0x30718880 else 0x30dce3e8),shape,null),MusicStyle.dp(context,4))
    }
    fun mode(active:Boolean,label:String,name:String=symbol) {
        if(isSelected!=active){isSelected=active;background=surface(active)}
        update(name,if(primary || active)MusicStyle.background else MusicStyle.muted)
        contentDescription=label;tooltipText=label
        if(android.os.Build.VERSION.SDK_INT>=30)stateDescription=label.substringAfter(": ",label)
    }
    override fun setEnabled(enabled:Boolean){if(isEnabled==enabled)return;animate().cancel();super.setEnabled(enabled);alpha=if(enabled)1f else .35f;invalidate()}
    override fun setPressed(pressed:Boolean) {
        super.setPressed(pressed)
        // Keep button bounds stable: no layout or translation changes on press.
        if(isEnabled && android.animation.ValueAnimator.areAnimatorsEnabled()){
            animate().alpha(if(pressed).72f else 1f).setDuration(if(pressed)90 else 160).start()
        }
    }
    override fun onDetachedFromWindow(){animate().cancel();alpha=if(isEnabled)1f else .35f;super.onDetachedFromWindow()}
}
