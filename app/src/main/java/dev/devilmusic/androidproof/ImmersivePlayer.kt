// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.*
import android.widget.*
import androidx.media3.common.Player

/** Full-screen presentation only. The existing MediaSession remains the playback owner. */
internal class ImmersivePlayer(context:Context,private val action:(String)->Unit,private val seek:(Long)->Unit):Dialog(context,android.R.style.Theme_Material_NoActionBar) {
    private fun dp(n:Int)=MusicStyle.dp(context,n)
    private val landscape=context.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private val root=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;fitsSystemWindows=true;clipChildren=false;setPadding(dp(24),dp(8),dp(24),dp(12));background=AmbientSurface()}
    private val cover=VinylArtwork(context).apply {contentDescription="Record. Swipe left or right to change song; tap for lyrics"}
    private val words=LyricsPanel(context,seek,immersive=true)
    private val slot=FrameLayout(context)
    private val name=MusicStyle.text(context,if(landscape)23f else 25f,bold=true,lines=2)
    private val artist=MusicStyle.text(context,14f,MusicStyle.muted,lines=1).apply {minHeight=dp(32);gravity=Gravity.CENTER_VERTICAL;isClickable=true;isFocusable=true;background=MusicStyle.touch(context);setOnClickListener {val credit=text.toString();dismiss();action("artist:$credit")}}
    private val position=MusicStyle.text(context,11f,MusicStyle.muted)
    private var duration=0L;private var dragging=false;private var showWords=landscape
    private var entry:android.graphics.RectF?=null
    private var switching=false
    private val artworkButton=MusicStyle.text(context,13f,bold=true).apply {text="Vinyl";gravity=Gravity.CENTER;isClickable=true;isFocusable=true;contentDescription="Show vinyl";setOnClickListener {setWords(false)}}
    private val bar=SeekBar(context).apply {max=10000;contentDescription="Playback position";progressTintList=android.content.res.ColorStateList.valueOf(MusicStyle.accent);thumbTintList=progressTintList
        setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onStartTrackingTouch(v:SeekBar){dragging=true};override fun onProgressChanged(v:SeekBar,p:Int,user:Boolean){};override fun onStopTrackingTouch(v:SeekBar){seek(duration*v.progress/10000);dragging=false}})}
    private fun glyph(symbol:String,label:String,primary:Boolean=false,click:()->Unit)=MusicGlyph(context,symbol,if(primary)MusicStyle.background else MusicStyle.ink).apply {contentDescription=label;isClickable=true;isFocusable=true;background=MusicStyle.touch(context,if(primary)MusicStyle.accent else Color.TRANSPARENT,36);setOnClickListener {click()}}
    private val play=PlayerControl(context,"play","Play or pause",true){action("toggle")}
    private val shuffle=PlayerControl(context,"shuffle","Shuffle"){action("shuffle")}
    private val repeat=PlayerControl(context,"repeat","Repeat"){action("repeat")}
    private val previous=PlayerControl(context,"previous","Previous track"){action("previous")}
    private val next=PlayerControl(context,"next","Next track"){action("next")}
    private val favorite=glyph("heart","Add to Liked songs"){action("like_current")}
    private val lyricButton=MusicStyle.text(context,13f,bold=true).apply {text="Lyrics";contentDescription="Show lyrics";gravity=Gravity.CENTER;isClickable=true;isFocusable=true;setOnClickListener {setWords(true)}}
    private val mark=WaterMusicMark(context)
    private var ambient=AmbientSurface()
    private var art:Bitmap?=null
    private var immersed=false
    private var beforeImmersiveWords=false
    private val transport=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL}
    private val immersiveButton=glyph("expand","Enter immersive mode"){setImmersive(!immersed)}
    private val controlsButton=MusicStyle.text(context,12f,bold=true,lines=2).apply {text="Hide\ncontrols";contentDescription="Hide playback controls";gravity=Gravity.CENTER;visibility=View.GONE;isClickable=true;isFocusable=true;background=MusicStyle.touch(context,radius=18);setOnClickListener {transport.visibility=if(transport.visibility==View.VISIBLE)View.GONE else View.VISIBLE;text=if(transport.visibility==View.VISIBLE)"Hide\ncontrols" else "Show\ncontrols";contentDescription=if(transport.visibility==View.VISIBLE)"Hide playback controls" else "Show playback controls"}}
    init {
        // fitsSystemWindows can replace explicit padding; keep content away from the tablet edges.
        root.setOnApplyWindowInsetsListener {view,insets->view.setPadding(dp(24)+insets.systemWindowInsetLeft,dp(8)+insets.systemWindowInsetTop,dp(24)+insets.systemWindowInsetRight,dp(12)+insets.systemWindowInsetBottom);insets.consumeSystemWindowInsets()}
        window?.setNavigationBarColor(MusicStyle.background);window?.setStatusBarColor(MusicStyle.background)
        val head=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;addView(glyph("down","Close player"){dismiss()},LinearLayout.LayoutParams(dp(48),dp(48)));addView(mark,LinearLayout.LayoutParams(dp(40),dp(40)));addView(MusicStyle.text(context,12f,bold=true).apply {text="DEVIL MUSIC"},LinearLayout.LayoutParams(0,dp(28),1f));addView(controlsButton,LinearLayout.LayoutParams(dp(88),dp(48)));addView(immersiveButton,LinearLayout.LayoutParams(dp(48),dp(48)));addView(glyph("menu","Playback options"){action("options")},LinearLayout.LayoutParams(dp(48),dp(48)))}
        root.addView(head,LinearLayout.LayoutParams(-1,dp(56)))
        val details=if(landscape)LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL} else root
        if(landscape){
            details.addView(words,LinearLayout.LayoutParams(-1,0,1f))
            val body=LinearLayout(context).apply {gravity=Gravity.CENTER;clipChildren=false;addView(cover,LinearLayout.LayoutParams(0,-1,1f).apply {rightMargin=dp(24)});addView(details,LinearLayout.LayoutParams(0,-1,1f))}
            root.addView(body,LinearLayout.LayoutParams(-1,0,1f).apply {topMargin=dp(8);bottomMargin=dp(12)})
        }else {slot.addView(cover,FrameLayout.LayoutParams(minOf(dp(520),dp(context.resources.configuration.screenWidthDp-48)),-1,Gravity.CENTER));slot.addView(words,FrameLayout.LayoutParams(-1,-1));words.visibility=View.GONE;root.addView(slot,LinearLayout.LayoutParams(-1,0,1f).apply {topMargin=dp(12);bottomMargin=dp(16)})}
        transport.addView(LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;addView(name,LinearLayout.LayoutParams(0,-2,1f));addView(favorite,LinearLayout.LayoutParams(dp(48),dp(48)))})
        transport.addView(artist,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(5);bottomMargin=dp(8)})
        transport.addView(bar,LinearLayout.LayoutParams(-1,dp(36)));transport.addView(position)
        val small=landscape && context.resources.configuration.screenWidthDp<800
        val side=if(small)48 else 56;val primary=if(small)56 else 72;val gap=if(small)0 else 10
        val buttons=LinearLayout(context).apply {gravity=Gravity.CENTER;addView(shuffle,LinearLayout.LayoutParams(dp(48),dp(48)));addView(previous,LinearLayout.LayoutParams(dp(side),dp(side)));addView(play,LinearLayout.LayoutParams(dp(primary),dp(primary)).apply {leftMargin=dp(gap);rightMargin=dp(gap)});addView(next,LinearLayout.LayoutParams(dp(side),dp(side)));addView(repeat,LinearLayout.LayoutParams(dp(48),dp(48)))}
        transport.addView(buttons,LinearLayout.LayoutParams(-1,dp(if(small)64 else 76)))
        transport.addView(LinearLayout(context).apply {gravity=Gravity.CENTER;addView(artworkButton,LinearLayout.LayoutParams(0,dp(48),1f).apply {rightMargin=dp(4)});addView(lyricButton,LinearLayout.LayoutParams(0,dp(48),1f).apply {rightMargin=dp(6)});addView(glyph("download","Save offline"){action("download")},LinearLayout.LayoutParams(dp(48),dp(48)));addView(glyph("queue","Open queue"){dismiss();action("section:queue")},LinearLayout.LayoutParams(dp(48),dp(48)))})
        details.addView(transport,LinearLayout.LayoutParams(-1,-2))
        var x=0f;var y=0f;var lastAngle=0f;var turned=false
        cover.contentDescription="Vinyl. Drag around the record to turn it; tap to show lyrics"
        cover.setOnTouchListener {v,e->
            val angle=Math.toDegrees(kotlin.math.atan2((e.y-v.height/2f).toDouble(),(e.x-v.width/2f).toDouble())).toFloat()
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{x=e.x;y=e.y;lastAngle=angle;turned=false;true}
                MotionEvent.ACTION_MOVE->{if(kotlin.math.hypot(e.x-x,e.y-y)>dp(8)){turned=true;v.parent.requestDisallowInterceptTouchEvent(true);var delta=angle-lastAngle;if(delta>180)delta-=360;if(delta< -180)delta+=360;if(kotlin.math.hypot(e.x-v.width/2f,e.y-v.height/2f)>minOf(v.width,v.height)*.12f)cover.turnBy(delta.coerceIn(-30f,30f))};lastAngle=angle;true}
                MotionEvent.ACTION_UP->{v.parent.requestDisallowInterceptTouchEvent(false);if(!turned)v.performClick();true}
                MotionEvent.ACTION_CANCEL->{v.parent.requestDisallowInterceptTouchEvent(false);true}
                else->true
            }
        }
        cover.setOnClickListener {setWords(!showWords)}
        paintTabs()
        setContentView(root);setOnShowListener {window?.setLayout(-1,-1);root.post {animateEntry()};action("lyrics")}
    }
    private fun paintTabs(){for((button,selected) in listOf(artworkButton to !showWords,lyricButton to showWords)){button.isSelected=selected;button.background=if(selected)MusicStyle.control(context) else MusicStyle.touch(context,radius=15);button.setTextColor(if(selected)MusicStyle.ink else MusicStyle.muted)}}
    private fun setWords(value:Boolean){if(immersed && !value)setImmersive(false);if(switching || showWords==value)return;showWords=value;paintTabs()
        if(landscape){
            if(android.animation.ValueAnimator.areAnimatorsEnabled())android.transition.TransitionManager.beginDelayedTransition(words.parent as ViewGroup,android.transition.AutoTransition().apply {duration=240})
            words.visibility=if(value)View.VISIBLE else View.GONE;words.alpha=1f;if(value)action("lyrics");return
        }
        fun swap(){cover.visibility=if(showWords)View.GONE else View.VISIBLE;words.visibility=if(showWords)View.VISIBLE else View.GONE}
        slot.animate().cancel();if(android.animation.ValueAnimator.areAnimatorsEnabled()){switching=true;slot.cameraDistance=dp(1800).toFloat();slot.animate().rotationY(85f).setDuration(170).withEndAction {swap();slot.rotationY=-85f;slot.animate().rotationY(0f).setDuration(220).withEndAction {switching=false}.start()}.start()}else swap()
        if(showWords)action("lyrics")
    }
    fun showLyrics(){setWords(true);action("lyrics")}
    fun lyricsVisible()=showWords
    fun liked(value:Boolean){favorite.isSelected=value;favorite.update(if(value)"heart_filled" else "heart",if(value)MusicStyle.accent else MusicStyle.muted);favorite.contentDescription=if(value)"Remove from Liked songs" else "Add to Liked songs"}
    fun isImmersive()=immersed
    fun restoreImmersive(value:Boolean){if(value)setImmersive(true)}
    private fun setImmersive(value:Boolean){
        if(immersed==value)return
        slot.animate().cancel();slot.rotationY=0f;switching=false
        if(value)beforeImmersiveWords=showWords
        immersed=value;showWords=if(value)true else beforeImmersiveWords
        cover.visibility=if(value || (!landscape && showWords))View.GONE else View.VISIBLE
        words.visibility=if(showWords)View.VISIBLE else View.GONE
        transport.visibility=View.VISIBLE;controlsButton.text="Hide\ncontrols";controlsButton.contentDescription="Hide playback controls";controlsButton.visibility=if(value)View.VISIBLE else View.GONE
        immersiveButton.contentDescription=if(value)"Exit immersive mode" else "Enter immersive mode"
        immersiveButton.isSelected=value;paintTabs();paintBackground();if(value)action("lyrics")
    }
    private fun paintBackground(){root.background=if(immersed && art!=null)ImmersiveArtwork(art!!) else ambient}
    fun rotationPhase()=cover.rotationPhase()
    fun restorePhase(value:Float)=cover.restorePhase(value)
    fun restoreWords(value:Boolean){showWords=value;paintTabs();if(landscape){words.visibility=if(value)View.VISIBLE else View.GONE;words.alpha=1f}else {cover.visibility=if(value)View.GONE else View.VISIBLE;words.visibility=if(value)View.VISIBLE else View.GONE}}
    fun origin(rect:android.graphics.RectF?){entry=rect}
    fun coverOrigin():android.graphics.RectF? {
        if(!cover.isShown)return null
        val p=IntArray(2);cover.getLocationOnScreen(p);val m=context.resources.displayMetrics
        return android.graphics.RectF(p[0].toFloat()/m.widthPixels,p[1].toFloat()/m.heightPixels,(p[0]+cover.width).toFloat()/m.widthPixels,(p[1]+cover.height).toFloat()/m.heightPixels)
    }
    private fun animateEntry(){
        if(!android.animation.ValueAnimator.areAnimatorsEnabled())return
        val from=entry
        if(from!=null && cover.isShown && cover.width>0){val p=IntArray(2);cover.getLocationOnScreen(p);val m=context.resources.displayMetrics
            cover.pivotX=cover.width/2f;cover.pivotY=cover.height/2f;cover.translationX=from.centerX()*m.widthPixels-p[0]-cover.width/2f;cover.translationY=from.centerY()*m.heightPixels-p[1]-cover.height/2f
            val scale=(minOf(from.width()*m.widthPixels,from.height()*m.heightPixels)/minOf(cover.width,cover.height)).coerceIn(.15f,1.5f);cover.scaleX=scale;cover.scaleY=scale
            cover.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
        root.alpha=.4f;root.animate().alpha(1f).setDuration(320).start()
    }
    override fun dismiss(){slot.animate().cancel();cover.animate().cancel();root.animate().cancel();super.dismiss()}
    fun lyrics(value:Lyrics,key:String)=words.show(value,key)
    fun lyricsStatus(value:String)=words.status(value)
    fun artwork(bitmap:Bitmap?){art=bitmap;if(bitmap!=null){cover.setImageBitmap(bitmap);val colors=AmbientPalette.sample(bitmap.width,bitmap.height,bitmap::getPixel);ambient=AmbientSurface(colors);mark.tint(colors[colors.size/2])}else cover.setImageDrawable(MusicFallback(name.text.toString(),false));paintBackground()}
    fun pulse(value:VisualPulse){if(isShowing){cover.tick(value!=VisualPulse.REST);cover.pulse(value);mark.pulse(value);ambient.pulse(value.ambient)}}
    fun bind(player:Player?){name.text=player?.mediaMetadata?.title ?: "Your music";artist.text=player?.mediaMetadata?.artist ?: "";artist.contentDescription="Open artist ${artist.text}";play.update(if(player?.isPlaying==true)"pause" else "play");shuffle.mode(player?.shuffleModeEnabled==true,"Shuffle: ${if(player?.shuffleModeEnabled==true)"on" else "off"}");repeat.mode(player?.repeatMode in setOf(Player.REPEAT_MODE_ALL,Player.REPEAT_MODE_ONE),"Repeat: ${when(player?.repeatMode){Player.REPEAT_MODE_ONE->"one track";Player.REPEAT_MODE_ALL->"all tracks";else->"off"}}",if(player?.repeatMode==Player.REPEAT_MODE_ONE)"repeat_one" else "repeat")
        val selected=player?.currentMediaItem!=null
        for(button in listOf(play,shuffle,repeat))button.isEnabled=selected
        previous.isEnabled=selected && (player?.hasPreviousMediaItem()==true || player?.isCurrentMediaItemSeekable==true);next.isEnabled=selected && player?.hasNextMediaItem()==true
        progress(player)}
    fun progress(player:Player?){duration=player?.duration?.takeIf {it>0} ?: 0;val ms=player?.currentPosition?.coerceAtLeast(0) ?: 0;bar.isEnabled=duration>0 && player?.isCurrentMediaItemSeekable==true;if(!dragging)bar.progress=if(duration>0)(ms*10000/duration).toInt() else 0;position.text="${time(ms)}  /  ${if(duration>0)time(duration)else "–:–"}";words.position(ms)}
    private fun time(ms:Long)="%d:%02d".format(ms/60000,ms/1000%60)
}

/** One existing cover bitmap, scaled by the GPU; no per-frame blur or extra full-screen bitmap. */
private class ImmersiveArtwork(private val image:Bitmap):android.graphics.drawable.Drawable(){
    private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val target=android.graphics.RectF()
    private val shade=android.graphics.Paint()
    override fun onBoundsChange(b:android.graphics.Rect){
        val scale=maxOf(b.width().toFloat()/image.width,b.height().toFloat()/image.height)
        val w=image.width*scale;val h=image.height*scale;target.set(b.exactCenterX()-w/2,b.exactCenterY()-h/2,b.exactCenterX()+w/2,b.exactCenterY()+h/2)
        shade.shader=android.graphics.LinearGradient(0f,b.top.toFloat(),0f,b.bottom.toFloat(),intArrayOf(0xb8080a10.toInt(),0xc7080a10.toInt(),0xf5080a10.toInt()),floatArrayOf(0f,.5f,1f),android.graphics.Shader.TileMode.CLAMP)
    }
    override fun draw(canvas:android.graphics.Canvas){canvas.save();canvas.clipRect(bounds);canvas.drawBitmap(image,null,target,paint);canvas.drawRect(bounds,shade);canvas.restore()}
    override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(filter:android.graphics.ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
    @Deprecated("Drawable opacity") override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
}
