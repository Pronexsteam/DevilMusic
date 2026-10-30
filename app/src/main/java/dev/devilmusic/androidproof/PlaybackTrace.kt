package dev.devilmusic.androidproof

import androidx.media3.common.Player
import dev.devilmusic.proof.QueueTrace

/** Shared only within this application process; no logcat, disk persistence or upload. */
object PlaybackTrace {
    private data class Snapshot(val timeline:androidx.media3.common.Timeline,val ids:List<String>)
    private val snapshots=java.util.WeakHashMap<Player,Snapshot>()
    val trace = QueueTrace()
    var serviceSnapshot: (() -> String)? = null
    private data class OnlineFailure(val id:String,val reason:String,val phase:String)
    @Volatile private var lastOnlineFailure:OnlineFailure?=null
    internal fun recordOnlineFailure(id:String?,failure:OnlineSourceException) {
        if(id!=null && failure.reason!="audio_cancelled")lastOnlineFailure=OnlineFailure(id,failure.reason,failure.phase)
    }
    fun onlineFailureReport(mediaId:String?):String {
        val last=lastOnlineFailure
        return if(last!=null && mediaId=="youtube:${last.id}")"lastTransportFailure=${last.reason}\nfailurePhase=${last.phase}" else "lastTransportFailure=none"
    }
    fun record(scope: QueueTrace.Scope, event: QueueTrace.Event, player: Player,
        requestedId: String? = null, targetIndex: Int? = null, reason: Int? = null) {
        val snapshot=snapshots[player]?.takeIf {it.timeline===player.currentTimeline} ?: Snapshot(player.currentTimeline,(0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaId}).also {snapshots[player]=it}
        trace.record(scope, event, snapshot.ids,
            player.currentMediaItemIndex, requestedId, targetIndex, reason)
    }
}
