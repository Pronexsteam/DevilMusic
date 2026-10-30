package dev.devilmusic.androidproof

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout

/** One cover-led focus, using the already loaded thumbnail for a restrained local tint. */
internal class HomeFeature(context:Context,item:MusicCard,availableDp:Float,open:()->Unit,menu:(View)->Unit):LinearLayout(context) {
    private fun dp(n:Int)=MusicStyle.dp(context,n)
    private fun surface(color:Int)=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(color,0xff11151b.toInt(),0xff111319.toInt())).apply {
        cornerRadius=dp(22).toFloat();setStroke(dp(1).coerceAtLeast(1),0x20dce4f3)
    }
    val artwork=object:ImageView(context){
        override fun setImageBitmap(bitmap:Bitmap?){
            super.setImageBitmap(bitmap)
            if(bitmap==null || bitmap.isRecycled)return
            // Tiny sample; never decode another image or blur the full screen.
            var red=0;var green=0;var blue=0
            for(x in 1..4)for(y in 1..4){val c=bitmap.getPixel((bitmap.width*x/5).coerceAtMost(bitmap.width-1),(bitmap.height*y/5).coerceAtMost(bitmap.height-1));red+=Color.red(c);green+=Color.green(c);blue+=Color.blue(c)}
            this@HomeFeature.background=surface(Color.rgb(18+red/64,20+green/64,24+blue/64))
        }
    }
    init {
        val wide=availableDp>=600
        val narrow=availableDp<400
        orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
        setPadding(dp(if(narrow)14 else 22),dp(22),dp(if(narrow)14 else 22),dp(22))
        background=surface(0xff252c34.toInt());elevation=dp(3).toFloat()
        val size=if(wide)188 else if(narrow)100 else 144
        artwork.apply {scaleType=ImageView.ScaleType.CENTER_CROP;background=MusicStyle.box(context,MusicStyle.surface,12);clipToOutline=true;setImageDrawable(MusicFallback(item.title,true));isClickable=true;isFocusable=true;contentDescription="Open ${item.title}";setOnClickListener {open()}}
        addView(artwork,LayoutParams(dp(size),dp(size)))
        val info=LinearLayout(context).apply {orientation=VERTICAL}
        info.addView(MusicStyle.text(context,10f,MusicStyle.muted,true).apply {text=when(item.kind){"album"->"ALBUM SPOTLIGHT";"playlist"->"MIX SPOTLIGHT";else->"YOUR NEXT PLAY"};letterSpacing=.13f})
        info.addView(MusicStyle.text(context,if(wide)30f else 20f,bold=true,lines=3).apply {text=item.title},LayoutParams(-1,-2).apply {topMargin=dp(8)})
        info.addView(MusicStyle.text(context,12f,MusicStyle.muted,lines=2).apply {text=item.subtitle},LayoutParams(-1,-2).apply {topMargin=dp(7)})
        val actions=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
        val isTrack=item.kind in setOf("song","online","local","download")
        actions.addView(MusicStyle.text(context,12f,MusicStyle.background,true).apply {
            text=if(isTrack)"Play" else "Explore";gravity=Gravity.CENTER;setPadding(dp(16),0,dp(16),0)
            background=MusicStyle.touch(context,MusicStyle.accent,24);isClickable=true;isFocusable=true;contentDescription="${if(isTrack)"Play" else "Explore"} ${item.title}";setOnClickListener {open()}
        },LayoutParams(-2,dp(48)))
        actions.addView(MusicGlyph(context,"menu",MusicStyle.ink).apply {contentDescription="Actions for ${item.title}";background=MusicStyle.touch(context,radius=24);isClickable=true;isFocusable=true;setOnClickListener {menu(this)}},LayoutParams(dp(48),dp(48)).apply {leftMargin=dp(4)})
        info.addView(actions,LayoutParams(-1,-2).apply {topMargin=dp(14)})
        addView(info,LayoutParams(0,-2,1f).apply {leftMargin=dp(if(narrow)14 else 24)})
    }
}
