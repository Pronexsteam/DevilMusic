// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult

/** Service-owned controls: no Activity lifetime, second player or account state. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackTools(context:Context,private val player:ExoPlayer,private val shuffle:QueueShuffle,private val publish:(Bundle)->Unit):Player.Listener {
    companion object {const val COMMAND="dev.devilmusic.PLAYBACK_TOOLS"}
    private val main=Handler(Looper.getMainLooper())
    private val prefs=context.getSharedPreferences("playback-tools",Context.MODE_PRIVATE)
    private val sleep=SleepPlan()
    private var fadeVolume:Float?=null
    private var endTrack:String?=null
    private var preload=prefs.getBoolean("preload",true)
    private var crossfade=prefs.getBoolean("crossfade",false)
    private val transition=OfflineCrossfade(context,player){crossfade && sleep.deadline==0L && !sleep.endOfTrack}
    private data class Removed(val item:MediaItem,val index:Int,val before:String?,val after:String?,val expires:Long,val shuffleIndex:Int,val shuffleBefore:String?,val shuffleAfter:String?)
    private var removed:Removed?=null
    private val expireUndo=Runnable {removed=null;emit()}
    private val timer=object:Runnable {override fun run(){
        val now=SystemClock.elapsedRealtime()
        if(sleep.expired(now)){player.pause();cancelSleep();return}
        val gain=sleep.gain(now)
        if(gain<1f && player.isPlaying){if(fadeVolume==null)fadeVolume=player.volume;player.volume=fadeVolume!!*gain}
        if(sleep.deadline>0)main.postDelayed(this,250)
    }}
    init {player.addListener(this);applyPreload()}
    private fun ids()=(0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaId}
    private fun applyPreload(){player.preloadConfiguration=if(preload)ExoPlayer.PreloadConfiguration(3_000_000L) else ExoPlayer.PreloadConfiguration.DEFAULT}
    fun extras()=Bundle().apply {
        putLong("sleepDeadline",sleep.deadline);putBoolean("sleepEnd",sleep.endOfTrack);putBoolean("preload",preload);putBoolean("crossfade",crossfade)
        removed?.let {putString("undoTitle",it.item.mediaMetadata.title?.toString() ?: "Track");putLong("undoExpires",it.expires)}
    }
    fun emit(){publish(extras())}
    private fun cancelSleep(){main.removeCallbacks(timer);sleep.cancel();endTrack=null;player.pauseAtEndOfMediaItems=false;fadeVolume?.let {player.volume=it};fadeVolume=null;emit()}
    fun command(args:Bundle):SessionResult {
        when(args.getString("action")){
            "move"->{
                val source=args.getString("id") ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
                val target=args.getString("target") ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
                val ids=ids();val from=ids.indexOf(source);val to=ids.indexOf(target)
                if(from<0 || to<0)return SessionResult(SessionError.ERROR_INVALID_STATE)
                if(player.shuffleModeEnabled)shuffle.move(source,target) else player.moveMediaItem(from,to)
            }
            "sleep"->{
                val minutes=args.getInt("minutes");val end=args.getBoolean("end")
                if(minutes !in 0..180 || (end && player.currentMediaItem==null))return SessionResult(SessionError.ERROR_BAD_VALUE)
                cancelSleep();sleep.set(minutes,end,SystemClock.elapsedRealtime());endTrack=if(end)player.currentMediaItem?.mediaId else null
                player.pauseAtEndOfMediaItems=sleep.endOfTrack
                if(sleep.deadline>0)main.post(timer)
            }
            "crossfade"->{crossfade=args.getBoolean("enabled");prefs.edit().putBoolean("crossfade",crossfade).apply()}
            "preload"->{preload=args.getBoolean("enabled");prefs.edit().putBoolean("preload",preload).apply();applyPreload()}
            "next"->{
                val index=ids().indexOf(args.getString("id"));val current=player.currentMediaItemIndex
                if(index<0 || current<0 || current>=player.mediaItemCount || index==current)return SessionResult(SessionError.ERROR_INVALID_STATE)
                if(player.shuffleModeEnabled){
                    if(!shuffle.playNext(player.getMediaItemAt(index).mediaId))return SessionResult(SessionError.ERROR_INVALID_STATE)
                }else player.moveMediaItem(index,if(index<current)current else current+1)
            }
            "remove"->{
                val id=args.getString("id") ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
                val list=ids();val index=list.indexOf(id)
                if(index<0 || index==player.currentMediaItemIndex)return SessionResult(SessionError.ERROR_INVALID_STATE)
                val order=shuffle.snapshot();val shuffledIndex=order.indexOf(id)
                removed=Removed(player.getMediaItemAt(index),index,list.getOrNull(index-1),list.getOrNull(index+1),SystemClock.elapsedRealtime()+10_000,shuffledIndex,order.getOrNull(shuffledIndex-1),order.getOrNull(shuffledIndex+1))
                player.removeMediaItem(index);main.removeCallbacks(expireUndo);main.postDelayed(expireUndo,10_000)
            }
            "undo"->{
                val item=removed ?: return SessionResult(SessionError.ERROR_INVALID_STATE)
                if(SystemClock.elapsedRealtime()>item.expires){removed=null;emit();return SessionResult(SessionError.ERROR_INVALID_STATE)}
                val list=ids();if(item.item.mediaId !in list)player.addMediaItem(QueueUndoPolicy.insertion(list,item.before,item.after,item.index),item.item)
                if(player.shuffleModeEnabled && item.shuffleIndex>=0)shuffle.restorePosition(item.item.mediaId,item.shuffleBefore,item.shuffleAfter,item.shuffleIndex)
                removed=null;main.removeCallbacks(expireUndo)
            }
            else->return SessionResult(SessionError.ERROR_NOT_SUPPORTED)
        }
        emit();return SessionResult(SessionResult.RESULT_SUCCESS)
    }
    override fun onPlayWhenReadyChanged(playWhenReady:Boolean,reason:Int){
        if(!playWhenReady && reason==Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleep.endOfTrack)cancelSleep()
    }
    override fun onMediaItemTransition(item:MediaItem?,reason:Int){
        if(sleep.endOfTrack && (item==null || (reason==Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && item.mediaId!=endTrack)))cancelSleep()
    }
    override fun onPlaybackStateChanged(state:Int){if(state==Player.STATE_ENDED && sleep.endOfTrack){player.pause();cancelSleep()}}
    override fun onTimelineChanged(timeline:androidx.media3.common.Timeline,reason:Int){if(timeline.isEmpty){removed=null;main.removeCallbacks(expireUndo);cancelSleep()}}
    fun release(){transition.release();main.removeCallbacksAndMessages(null);player.removeListener(this);fadeVolume?.let {player.volume=it}}
}



