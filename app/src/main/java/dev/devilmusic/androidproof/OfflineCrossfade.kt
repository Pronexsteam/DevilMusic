// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

internal object CrossfadeCurve {
    fun gains(elapsed:Long,duration:Long):Pair<Float,Float> {val f=(elapsed.toFloat()/duration.coerceAtLeast(1)).coerceIn(0f,1f);return (1f-f) to f}
    fun local(id:String?)=id?.startsWith("download:")==true || id?.startsWith("content:")==true
}
/** Optional local-file overlap only. Online tracks retain Media3 preloading without a second network stream. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class OfflineCrossfade(private val context:Context,private val mainPlayer:ExoPlayer,private val allowed:()->Boolean) {
    private val handler=Handler(Looper.getMainLooper());private var tail:ExoPlayer?=null
    private var source:String?=null;private var next:String?=null;private var started=0L;private var gain=1f
    private val tick=object:Runnable {override fun run(){update();handler.postDelayed(this,if(tail!=null)50 else 250)}}
    init{handler.post(tick)}
    private fun reset(){tail?.release();tail=null;if(started>0)mainPlayer.volume=gain;started=0;source=null;next=null}
    private fun update(){
        val p=mainPlayer
        if(!allowed() || !p.playWhenReady || p.playerError!=null || p.playbackParameters.speed!=1f || p.repeatMode==Player.REPEAT_MODE_ONE){reset();return}
        if(started>0){
            if(p.currentMediaItem?.mediaId!=next || p.currentPosition>6000){reset();return}
            val elapsed=SystemClock.elapsedRealtime()-started;val levels=CrossfadeCurve.gains(elapsed,3000)
            tail?.volume=gain*levels.first;p.volume=gain*levels.second
            if(elapsed>=3000)reset();return
        }
        val nextIndex=p.nextMediaItemIndex
        if(nextIndex<0 || !CrossfadeCurve.local(p.currentMediaItem?.mediaId) || !CrossfadeCurve.local(p.getMediaItemAt(nextIndex).mediaId) || p.duration<15000){reset();return}
        val remaining=p.duration-p.currentPosition
        val id=p.currentMediaItem!!.mediaId;val nextId=p.getMediaItemAt(nextIndex).mediaId
        if(source!=null && (source!=id || next!=nextId || remaining>9000)){reset()}
        if(remaining in 3500..7000 && tail==null){
            source=id;next=nextId
            tail=ExoPlayer.Builder(context).build().apply {
                // No audio focus or media session: this short-lived decoder is only the outgoing tail.
                volume=0f;setMediaItem(p.currentMediaItem!!);seekTo((p.duration-3000).coerceAtLeast(0));prepare()
            }
        }
        if(remaining in 2700..3000 && tail?.playbackState==Player.STATE_READY && p.isPlaying){
            gain=p.volume;started=SystemClock.elapsedRealtime();tail?.volume=gain;tail?.play();p.volume=0f;p.seekToNextMediaItem()
        }else if(remaining<2700 && started==0L)reset()
    }
    fun release(){handler.removeCallbacksAndMessages(null);reset()}
}
