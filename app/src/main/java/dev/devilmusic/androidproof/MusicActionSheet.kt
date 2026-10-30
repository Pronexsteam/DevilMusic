// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.widget.*

internal data class MusicAction(val title:String,val detail:String="",val enabled:Boolean=true,val run:()->Unit)
internal object MusicActionSheet {
    fun show(context:Context,title:String,subtitle:String="",groups:List<Pair<String,List<MusicAction>>>){
        fun dp(v:Int)=MusicStyle.dp(context,v)
        val dialog=Dialog(context)
        // Overlapping text needs a denser surface than non-overlapping app panels.
        val sheetSurface=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,intArrayOf(0xf52a2e39.toInt(),0xfa11151d.toInt())).apply {cornerRadius=dp(26).toFloat();setStroke(dp(1).coerceAtLeast(1),0x30dce4f3)}
        val body=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;background=sheetSurface;elevation=dp(18).toFloat();setPadding(dp(20),dp(12),dp(20),dp(18))}
        val head=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
        head.addView(MusicStyle.text(context,22f,bold=true,lines=2).apply {text=title},LinearLayout.LayoutParams(0,-2,1f))
        head.addView(MusicStyle.text(context,22f).apply {text="×";gravity=Gravity.CENTER;contentDescription="Close menu";background=MusicStyle.touch(context);setOnClickListener {dialog.dismiss()}},LinearLayout.LayoutParams(dp(48),dp(48)))
        body.addView(head)
        if(subtitle.isNotBlank())body.addView(MusicStyle.text(context,13f,MusicStyle.muted,lines=2).apply {text=subtitle;setPadding(0,0,0,dp(12))})
        val content=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL}
        for((label,actions) in groups){
            content.addView(MusicStyle.text(context,11f,MusicStyle.muted,true).apply {text=label.uppercase();letterSpacing=.08f;setPadding(dp(10),dp(16),0,dp(7))})
            for(action in actions){
                val row=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(54);setPadding(dp(12),dp(9),dp(12),dp(9));background=MusicStyle.touch(context,Color.argb(75,36,43,55),16);isEnabled=action.enabled;alpha=if(action.enabled)1f else .4f;isFocusable=true;contentDescription=action.title+". "+action.detail;setOnClickListener {dialog.dismiss();action.run()}}
                row.addView(MusicStyle.text(context,15f,bold=true).apply {text=action.title})
                if(action.detail.isNotBlank())row.addView(MusicStyle.text(context,12f,MusicStyle.muted,lines=2).apply {text=action.detail})
                content.addView(row,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(5)})
            }
        }
        body.addView(ScrollView(context).apply {isFillViewport=false;addView(content)},LinearLayout.LayoutParams(-1,0,1f))
        dialog.setContentView(body);dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));dialog.show()
        val metrics=context.resources.displayMetrics
        dialog.window?.apply {setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL);setLayout(minOf(metrics.widthPixels-dp(24),dp(570)),minOf((metrics.heightPixels*.82f).toInt(),dp(680)));addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);attributes=attributes.apply {dimAmount=.36f}}
    }
}
