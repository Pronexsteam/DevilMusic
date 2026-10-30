package dev.devilmusic.androidproof

import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

/** Exercises service listener wiring with Media3's actual permutation; no audio/device claims. */
class QueueShuffleIntegrationTest {
    private class Harness {
        var items=emptyList<MediaItem>();var index=0;var enabled=false;var installs=0
        var order:ShuffleOrder=ShuffleOrder.DefaultShuffleOrder(0,1L)
        val listeners=mutableListOf<Player.Listener>()
        val timeline=object:Timeline(){
            override fun getWindowCount()=items.size
            override fun getPeriodCount()=items.size
            override fun getWindow(i:Int,w:Window,p:Long)=w
            override fun getPeriod(i:Int,p:Period,setIds:Boolean)=p
            override fun getIndexOfPeriod(uid:Any)=uid as Int
            override fun getUidOfPeriod(i:Int):Any=i
            override fun getFirstWindowIndex(shuffle:Boolean)=if(items.isEmpty())-1 else if(shuffle)order.firstIndex else 0
            override fun getLastWindowIndex(shuffle:Boolean)=if(items.isEmpty())-1 else if(shuffle)order.lastIndex else items.lastIndex
            override fun getNextWindowIndex(i:Int,repeat:Int,shuffle:Boolean)=if(shuffle)order.getNextIndex(i) else (i+1).takeIf {it<items.size} ?: -1
        }
        val player=Proxy.newProxyInstance(Player::class.java.classLoader,arrayOf(Player::class.java)){_,m,a->when(m.name){
            "addListener"->{listeners.add(a!![0] as Player.Listener);null}
            "removeListener"->{listeners.remove(a!![0]);null}
            "getCurrentTimeline"->timeline
            "getCurrentMediaItemIndex"->index
            "getCurrentMediaItem"->items.getOrNull(index)
            "getMediaItemCount"->items.size
            "getMediaItemAt"->items[a!![0] as Int]
            "getShuffleModeEnabled"->enabled
            "getRepeatMode"->Player.REPEAT_MODE_OFF
            "setShuffleModeEnabled"->{enabled=a!![0] as Boolean;listeners.toList().forEach {it.onShuffleModeEnabledChanged(enabled)};null}
            "setShuffleOrder"->{order=a!![0] as ShuffleOrder;installs++;changed();null}
            "setMediaItems"->{@Suppress("UNCHECKED_CAST") val next=a!![0] as List<MediaItem>;items=next;index=a[1] as Int;order=ShuffleOrder.DefaultShuffleOrder(items.size,1L);changed();listeners.toList().forEach {it.onMediaItemTransition(items.getOrNull(index),Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)};null}
            "setPlayWhenReady"->null
            else->throw AssertionError("Unexpected player call ${m.name}")
        }} as Player
        fun install(value:ShuffleOrder){order=value;installs++;changed()}
        fun changed(){listeners.toList().forEach {it.onTimelineChanged(timeline,Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)}}
        fun transition(i:Int){index=i;listeners.toList().forEach {it.onMediaItemTransition(items[i],Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)}}
    }
    private fun item(i:Int)=MediaItem.Builder().setMediaId("youtube:"+i.toString().padStart(11,'0')).build()
    @Test fun restorePlayNextMetadataAndRemovalKeepServiceOrderCoherent(){
        val h=Harness();val owner=QueueShuffle(h.player,h::install);val items=(0..7).map(::item)
        val ids=items.map {it.mediaId};val saved=listOf(3,5,1,7,0,2,6,4).map {ids[it]}
        owner.restore(SavedQueue(emptyList(),ids[1],0,true,0,1f,saved),items,1)
        assertEquals(saved,owner.snapshot())
        val before=h.installs;h.transition(7);assertEquals(before,h.installs) // Ordinary next is O(1), no reshuffle.
        assertTrue(owner.playNext(ids[6]));assertEquals(ids[6],owner.snapshot()[owner.snapshot().indexOf(ids[7])+1])
        val expected=owner.snapshot();h.order=ShuffleOrder.DefaultShuffleOrder(8,999L);h.changed()
        assertEquals(expected,owner.snapshot());assertEquals(expected,playbackIndices(h.player).map {h.items[it].mediaId})
        val current=h.items[h.index].mediaId;h.items=h.items.filter {it.mediaId!=ids[0]};h.index=h.items.indexOfFirst {it.mediaId==current};h.order=ShuffleOrder.DefaultShuffleOrder(7,4L);h.changed()
        assertEquals(expected.filter {it!=ids[0]},owner.snapshot());owner.release()
    }
    @Test fun enablingShuffleAnchorsCurrentAndExplicitJumpDoesNotDropRemainingSongs(){
        val h=Harness();val owner=QueueShuffle(h.player,h::install);val items=(0..19).map(::item)
        owner.restore(SavedQueue(emptyList(),items[10].mediaId,0,false,0,1f),items,10)
        h.player.shuffleModeEnabled=true;assertEquals(items[10].mediaId,owner.snapshot().first())
        val before=owner.snapshot();val target=before[9];h.transition(items.indexOfFirst {it.mediaId==target})
        assertEquals(listOf(items[10].mediaId,target),owner.snapshot().take(2))
        assertEquals(before.toSet(),owner.snapshot().toSet());assertEquals(20,owner.snapshot().size)
    }
}

