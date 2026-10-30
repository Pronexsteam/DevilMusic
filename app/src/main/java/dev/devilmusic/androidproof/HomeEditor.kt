package dev.devilmusic.androidproof

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** Draft edits are applied once; dismissing the panel leaves the saved layout untouched. */
internal object HomeEditor {
    fun show(context:Context,source:List<String>,saved:HomeArrangement,apply:(HomeArrangement)->Unit){
        fun dp(n:Int)=MusicStyle.dp(context,n)
        val dialog=Dialog(context)
        val order=(saved.order.filter {it in source}+source).distinct().toMutableList()
        val hidden=saved.hidden.toMutableSet()
        val sheet=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,intArrayOf(0xff191d25.toInt(),0xff101218.toInt())).apply {cornerRadius=dp(26).toFloat();setStroke(dp(1).coerceAtLeast(1),0x30dce4f3)}
        val body=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(16),dp(20),dp(20));background=sheet;elevation=dp(18).toFloat()}
        fun button(label:String,primary:Boolean=false,click:()->Unit)=MusicStyle.text(context,14f,if(primary)MusicStyle.background else MusicStyle.ink,true).apply {
            text=label;gravity=Gravity.CENTER;isClickable=true;isFocusable=true;minHeight=dp(48);background=MusicStyle.control(context,primary);setOnClickListener {click()}
        }
        val head=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
        head.addView(MusicStyle.text(context,24f,bold=true).apply {text="Your Home"},LinearLayout.LayoutParams(0,-2,1f))
        head.addView(MusicGlyph(context,"close",MusicStyle.muted).apply {contentDescription="Close without saving";isClickable=true;isFocusable=true;background=MusicStyle.control(context,inset=4);setOnClickListener {dialog.dismiss()}},LinearLayout.LayoutParams(dp(48),dp(48)))
        body.addView(head)
        body.addView(MusicStyle.text(context,13f,MusicStyle.muted,lines=3).apply {text="Choose what you see. Drag a handle to change the order.";setPadding(0,dp(8),0,dp(18))})
        val content=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL}
        val scroll=ScrollView(context).apply {isFillViewport=false;addView(content)}
        val drag=HomeSectionDrag(content,scroll,order)
        fun redraw(){
            val y=scroll.scrollY;content.removeAllViews()
            if(order.isEmpty())content.addView(MusicStyle.text(context,14f,MusicStyle.muted,lines=3).apply {text="Your sections will appear after music has loaded."})
            for(name in order){
                val row=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(10),dp(10),dp(10));background=MusicStyle.box(context,Color.argb(110,27,32,41),18,true)}
                val titleRow=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
                titleRow.addView(MusicStyle.text(context,15f,bold=true,lines=2).apply {text=name},LinearLayout.LayoutParams(0,-2,1f).apply {rightMargin=dp(8)})
                titleRow.addView(HomeToggle(context).apply {contentDescription="Show $name";isChecked=name !in hidden;changed={checked->if(checked)hidden.remove(name)else hidden.add(name)}},LinearLayout.LayoutParams(dp(52),dp(48)))
                row.addView(titleRow)
                val controls=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
                controls.addView(MusicStyle.text(context,11f,MusicStyle.muted).apply {text=when(name){"Recently played"->"Return to your recent music";"Pinned collections"->"Albums and mixes you kept";else->"Discover something worth playing"}},LinearLayout.LayoutParams(0,-2,1f))
                val handle=MusicGlyph(context,"grip",MusicStyle.muted).apply {background=MusicStyle.control(context,inset=4)}
                drag.bind(handle,row,name)
                controls.addView(handle,LinearLayout.LayoutParams(dp(48),dp(48)).apply {leftMargin=dp(8)})
                row.addView(controls);content.addView(row,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(10)})
            }
            scroll.post {scroll.scrollTo(0,y)}
        }
        redraw();body.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val footer=LinearLayout(context)
        footer.addView(button("Reset"){order.clear();order.addAll(source.distinct());hidden.clear();redraw()},LinearLayout.LayoutParams(0,dp(50),1f).apply {rightMargin=dp(10)})
        footer.addView(button("Save layout",true){apply(HomeArrangement(order.toList(),hidden.toSet()));dialog.dismiss()},LinearLayout.LayoutParams(0,dp(50),2f))
        body.addView(footer,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(14)})
        dialog.setContentView(body);dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));dialog.show()
        val m=context.resources.displayMetrics
        val tablet=context.resources.configuration.smallestScreenWidthDp>=600
        dialog.window?.apply {setGravity(if(tablet)Gravity.CENTER else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL);setLayout(minOf(m.widthPixels-dp(24),dp(540)),minOf((m.heightPixels*.85f).toInt(),dp(700)));addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);attributes=attributes.apply {dimAmount=.5f}}
    }
}
