// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

internal fun playbackIndices(player:Player):List<Int> {
    val timeline=player.currentTimeline;val order=ArrayList<Int>(player.mediaItemCount)
    var i=timeline.getFirstWindowIndex(player.shuffleModeEnabled)
    while(i!=C.INDEX_UNSET && order.size<player.mediaItemCount){order.add(i);i=timeline.getNextWindowIndex(i,Player.REPEAT_MODE_OFF,player.shuffleModeEnabled)}
    return order
}

/** Media3 owns transport/history. This preserves its permutation across edits and process restart. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class QueueShuffle(private val player:Player,private val setOrder:(ShuffleOrder)->Unit):Player.Listener {
    private var ids=emptyList<String>()
    private var positions=emptyMap<String,Int>()
    private var order=emptyList<String>()
    private var previous:String?=null
    private var applying=false
    private var restoring=false
    init {player.addListener(this)}
    fun snapshot():List<String> = if(player.shuffleModeEnabled)order else emptyList()
    private fun install(next:List<String>){
        if(next.size!=ids.size || next.toSet().size!=ids.size)return
        val indexes=ids.withIndex().associate {it.value to it.index}
        if(next.any {it !in indexes})return
        order=next;positions=next.withIndex().associate {it.value to it.index}
        applying=true
        try {setOrder(ShuffleOrder.DefaultShuffleOrder(next.map {indexes.getValue(it)}.toIntArray(),Random.nextLong()))} finally {applying=false}
    }
    override fun onTimelineChanged(timeline:Timeline,reason:Int){
        if(applying)return
        val next=(0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaId}
        val changed=ids!=next;ids=next
        if(player.shuffleModeEnabled && !restoring){
            val nextOrder=if(changed)ShufflePlan.reconcile(order,ids,player.currentMediaItem?.mediaId,Random.Default) else order
            if(playbackIndices(player).map {ids[it]}!=nextOrder)install(nextOrder)
            else {order=nextOrder;positions=order.withIndex().associate {it.value to it.index}}
        }else {order=playbackIndices(player).map {ids[it]};positions=order.withIndex().associate {it.value to it.index}}
    }
    override fun onShuffleModeEnabledChanged(enabled:Boolean){
        if(enabled && !restoring)install(ShufflePlan.fresh(ids,player.currentMediaItem?.mediaId,Random.Default))
        previous=player.currentMediaItem?.mediaId
    }
    override fun onMediaItemTransition(item:MediaItem?,reason:Int){
        val current=item?.mediaId;val old=previous;previous=current
        if(!restoring && player.shuffleModeEnabled && reason==Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && current!=null && old!=null && current!=old){
            val p=positions[old]
            // Next/Previous retain the permutation; an explicit jump moves the chosen song after the prior one.
            if(p!=null && order.getOrNull(p+1)!=current && order.getOrNull(p-1)!=current && !(player.repeatMode==Player.REPEAT_MODE_ALL && ((p==order.lastIndex && order.firstOrNull()==current)||(p==0 && order.lastOrNull()==current)))){
                val next=order.filter {it!=current}.toMutableList();val at=next.indexOf(old)
                if(at>=0){next.add(at+1,current);install(next)}
            }
        }
    }
    fun move(source:String,target:String){install(ShufflePlan.move(order,source,target))}
    fun playNext(id:String):Boolean {
        val indexes=ids.withIndex().associate {it.value to it.index}
        val target=indexes[id] ?: return false
        val next=PlayNextPolicy.shuffled(order.mapNotNull {indexes[it]},player.currentMediaItemIndex,target) ?: return false
        install(next.map {ids[it]});return true
    }
    fun restorePosition(id:String,before:String?,after:String?,fallback:Int){
        val next=order.filter {it!=id}.toMutableList()
        next.add(QueueUndoPolicy.insertion(next,before,after,fallback),id);install(next)
    }
    fun restore(saved:SavedQueue,items:List<MediaItem>,index:Int){
        restoring=true
        try {
            player.playWhenReady=false;player.setMediaItems(items,index.coerceAtLeast(0),if(index>=0)saved.position else 0)
            ids=items.map {it.mediaId};player.shuffleModeEnabled=saved.shuffle
            if(saved.shuffle)install(ShufflePlan.reconcile(saved.shuffleOrder,ids,player.currentMediaItem?.mediaId,Random.Default))
            previous=player.currentMediaItem?.mediaId
        }finally{restoring=false}
    }
    fun release(){player.removeListener(this)}
}

