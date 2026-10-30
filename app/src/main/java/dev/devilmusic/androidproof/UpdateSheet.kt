// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.widget.*

internal object UpdateSheet {
    fun show(context:Context,current:String,release:UpdateRelease?,failed:Boolean,download:(String)->Unit,retry:()->Unit) {
        fun dp(v:Int)=MusicStyle.dp(context,v)
        val dialog=Dialog(context)
        val surface=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,intArrayOf(0xfa242a32.toInt(),0xfd101419.toInt())).apply {cornerRadius=dp(26).toFloat();setStroke(dp(1).coerceAtLeast(1),0x30dce4f3)}
        val body=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;background=surface;setPadding(dp(22),dp(20),dp(22),dp(18))}
        body.addView(MusicStyle.text(context,24f,bold=true,lines=2).apply {text=if(release!=null)"A new version is ready" else if(failed)"Couldn’t check for updates" else "You’re up to date"})
        body.addView(MusicStyle.text(context,13f,MusicStyle.muted,lines=2).apply {text="Installed: $current";setPadding(0,dp(8),0,dp(14))})
        val text=MusicStyle.text(context,14f,lines=Int.MAX_VALUE).apply {
            ellipsize=null;setLineSpacing(dp(4).toFloat(),1f)
            this.text=if(release!=null)"Devil Music ${release.label} · ${"%.1f".format(java.util.Locale.US,release.size/1048576.0)} MB\n\n${release.notes.ifBlank {"Improvements and fixes."}}\n\nDownload the APK, open it and confirm Update in Android. Keep the existing app installed to retain your library and settings."
                else if(failed)"GitHub is temporarily unavailable. Your music keeps playing. Try again when you’re online."
                else "No newer published version was found for your channel. Devil Music checks GitHub once a day while open and shows an update button when a newer APK is available."
        }
        body.addView(ScrollView(context).apply {addView(text)},LinearLayout.LayoutParams(-1,0,1f))
        val buttons=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
        fun button(label:String,primary:Boolean,click:()->Unit)=MusicStyle.text(context,14f,if(primary)MusicStyle.background else MusicStyle.ink,true).apply {
            this.text=label;gravity=Gravity.CENTER;isClickable=true;isFocusable=true;background=MusicStyle.control(context,primary);setOnClickListener {dialog.dismiss();click()}
        }
        buttons.addView(button("Close",false){},LinearLayout.LayoutParams(0,dp(50),1f).apply {rightMargin=dp(10)})
        if(release!=null)buttons.addView(button("Download APK",true){download(release.apk)},LinearLayout.LayoutParams(0,dp(50),1.6f))
        else if(failed)buttons.addView(button("Try again",true,retry),LinearLayout.LayoutParams(0,dp(50),1.3f))
        body.addView(buttons,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(18)})
        dialog.setContentView(body);dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));dialog.show()
        val metrics=context.resources.displayMetrics
        dialog.window?.apply {setGravity(Gravity.CENTER);setLayout(minOf(metrics.widthPixels-dp(32),dp(550)),minOf((metrics.heightPixels*.8f).toInt(),dp(if(release!=null)620 else 360)));addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);attributes=attributes.apply {dimAmount=.6f}}
    }
}
