// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import android.content.Context
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.CharacterStyle
import android.text.TextPaint
import android.view.*
import android.widget.*

/** Virtual rows; no synthetic word timestamps. User scrolling suspends follow until explicitly resumed. */
internal class LyricsPanel(context:Context,private val seek:(Long)->Unit,private val immersive:Boolean=false):LinearLayout(context) {
    private class WordGlow:CharacterStyle(){override fun updateDrawState(p:TextPaint){p.color=MusicStyle.ink;p.setShadowLayer(p.textSize*.18f,0f,0f,0x8097bbc7.toInt())}}
    private var lyrics=Lyrics();private var active=-2;private var activeWord=-2;private var following=true
    private var offset=0L;private var key="";private val prefs=context.getSharedPreferences("lyrics-offset",Context.MODE_PRIVATE)
    private val caption=MusicStyle.text(context,11f,MusicStyle.muted,lines=2)
    private val follow=button("Follow"){following=true;scroll()}
    private val adapter=object:BaseAdapter(){
        override fun getCount()=lyrics.lines.size
        override fun getItem(i:Int)=lyrics.lines[i]
        override fun getItemId(i:Int)=i.toLong()
        override fun getView(i:Int,recycled:View?,parent:ViewGroup):View {
            val text=(recycled as? TextView) ?: MusicStyle.text(context,23f,bold=true,lines=8).apply {setPadding(18,18,18,18);gravity=Gravity.CENTER;ellipsize=null}
            val line=lyrics.lines[i];val value=line.text.ifBlank {"♪"}
            text.text=if(i==active && line.words.isNotEmpty())SpannableString(value).apply {
                var start=0;for((n,w) in line.words.withIndex()){val at=value.indexOf(w.text.trim(),start);if(at>=0){val end=(at+w.text.trim().length).coerceAtMost(length);setSpan(if(n==activeWord)WordGlow() else ForegroundColorSpan(if(n<activeWord)MusicStyle.ink else MusicStyle.muted),at,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);start=end}}
            }else value
            text.setTextColor(if(i==active)MusicStyle.ink else MusicStyle.muted);text.alpha=if(i==active)1f else .62f
            text.background=null
            text.setOnClickListener {seek((line.time-offset).coerceAtLeast(0));following=true}
            return text
        }
    }
    private val list=ListView(context).apply {adapter=this@LyricsPanel.adapter;divider=null;clipToPadding=false;setPadding(0,12,0,64);isVerticalScrollBarEnabled=true
        if(immersive){isVerticalFadingEdgeEnabled=true;setFadingEdgeLength(MusicStyle.dp(context,32));isVerticalScrollBarEnabled=false}
        setOnTouchListener {_,e->if(e.actionMasked==MotionEvent.ACTION_MOVE){following=false;follow.text="Follow current line"};false}}
    private val empty=MusicStyle.text(context,17f,MusicStyle.muted,lines=300).apply {gravity=Gravity.CENTER;setPadding(16,24,16,24);ellipsize=null}
    private val plain=ScrollView(context).apply {addView(empty)}
    init {orientation=VERTICAL;background=if(immersive)null else MusicStyle.panel(context,22)
        if(immersive){setPadding(MusicStyle.dp(context,12),0,MusicStyle.dp(context,12),0);caption.setPadding(0,MusicStyle.dp(context,3),0,MusicStyle.dp(context,12))}
        addView(LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;addView(button("−0.2s"){adjust(-200)},LayoutParams(0,MusicStyle.dp(context,44),1f));addView(follow,LayoutParams(0,MusicStyle.dp(context,44),2f));addView(button("+0.2s"){adjust(200)},LayoutParams(0,MusicStyle.dp(context,44),1f))})
        addView(caption,LayoutParams(-1,-2));addView(FrameLayout(context).apply {addView(list);addView(plain)},LayoutParams(-1,0,1f));status("Open lyrics to find the words")
    }
    private fun button(title:String,click:()->Unit)=MusicStyle.text(context,11f,bold=true).apply {text=title;gravity=Gravity.CENTER;minimumHeight=MusicStyle.dp(context,44);isClickable=true;isFocusable=true;background=MusicStyle.touch(context);setOnClickListener {click()}}
    private fun adjust(delta:Long){offset=(offset+delta).coerceIn(-10000,10000);if(key.isNotEmpty())prefs.edit().putLong(key,offset).apply();label();active=-2}
    private fun label(){caption.text="${lyrics.source} · ${offset/1000f}s\n${if(lyrics.lines.any {it.words.isNotEmpty()})"Word glow · timed words available" else "Line timing only · word timestamps unavailable"}"}
    fun status(message:String){lyrics=Lyrics();active=-2;list.visibility=GONE;plain.visibility=VISIBLE;empty.text=message;caption.text=""}
    fun show(value:Lyrics,id:String){lyrics=value;key=id;offset=prefs.getLong(key,0);active=-2;activeWord=-2;following=true
        list.visibility=if(value.lines.isEmpty())GONE else VISIBLE;plain.visibility=if(value.lines.isEmpty())VISIBLE else GONE
        empty.text=when {value.instrumental->"Instrumental · enjoy the music";value.plain.isNotBlank()->value.plain;else->"No lyrics found for this recording"}
        if(value.lines.isNotEmpty())label()else caption.text=if(value.plain.isNotBlank())"${value.source} · text without timing" else value.source
        adapter.notifyDataSetChanged()
    }
    fun position(ms:Long){if(!isShown || lyrics.lines.isEmpty())return;val position=ms+offset;val index=Lrc.index(lyrics.lines,position);val word=lyrics.lines.getOrNull(index)?.words?.indexOfLast {it.time<=position && (it.end==null || position<it.end)} ?: -1
        if(index==active && word==activeWord)return;val moved=index!=active;active=index;activeWord=word;adapter.notifyDataSetChanged();if(moved && following)scroll()}
    private fun scroll(){if(active>=0){if(android.animation.ValueAnimator.areAnimatorsEnabled())list.smoothScrollToPositionFromTop(active,height/3,400)else list.setSelectionFromTop(active,height/3)};follow.text="Following"}
}
