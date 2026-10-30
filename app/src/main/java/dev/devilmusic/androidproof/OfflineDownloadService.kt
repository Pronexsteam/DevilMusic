// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof

import android.app.*
import android.content.*
import android.os.*
import com.grack.nanojson.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal data class DownloadJob(val track:SavedTrack,val state:String,val percent:Int=0)
internal object DownloadJobs {
    @Volatile var live:DownloadJob?=null
    fun read(context:Context):DownloadJob?=live ?: runCatching {
        val f=File(context.filesDir,"music-state/download-job.json");if(!f.isFile || f.length()>16384)return null
        val o=JsonParser.`object`().from(f.readText());val id=o.getString("id","");if(!id.startsWith("youtube:") || YouTubeInput.parse(id.removePrefix("youtube:"))!=id.removePrefix("youtube:"))return null
        DownloadJob(SavedTrack(id,o.getString("title","Audio"),o.getString("artist",""),o.getString("artwork","").takeIf(NewPipeCatalog::validArtwork)),if(o.getString("state")=="running")"interrupted" else o.getString("state","interrupted"),o.getInt("percent",0))
    }.getOrNull()
    fun save(context:Context,job:DownloadJob){live=job;atomicWrite(File(context.filesDir,"music-state/download-job.json"),JsonWriter.string(JsonObject().apply {put("id",job.track.id);put("title",job.track.title.take(512));put("artist",job.track.artist.take(512));put("artwork",job.track.artwork);put("state",job.state);put("percent",job.percent)}).toByteArray())}
    fun start(context:Context,track:SavedTrack){context.startForegroundService(Intent(context,OfflineDownloadService::class.java).apply {putExtra("id",track.id);putExtra("title",track.title);putExtra("artist",track.artist);putExtra("artwork",track.artwork)})}
    fun cancel(context:Context){context.startService(Intent(context,OfflineDownloadService::class.java).setAction("cancel"))}
}
class OfflineDownloadService:Service() {
    private val worker=Executors.newSingleThreadExecutor();private val main=Handler(Looper.getMainLooper())
    private var task:Future<*>?=null;private var cancelled=false;private var closed=false
    private lateinit var notifications:NotificationManager
    override fun onCreate(){super.onCreate();notifications=getSystemService(NotificationManager::class.java);notifications.createNotificationChannel(NotificationChannel("offline","Offline music",NotificationManager.IMPORTANCE_LOW))}
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="cancel"){cancelled=true;task?.cancel(true);DownloadJobs.live?.let {DownloadJobs.save(this,it.copy(state="cancelled"))};stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY}
        if(task!=null)return START_NOT_STICKY
        val id=intent?.getStringExtra("id").orEmpty()
        if(!id.startsWith("youtube:") || YouTubeInput.parse(id.removePrefix("youtube:"))!=id.removePrefix("youtube:")){stopSelf();return START_NOT_STICKY}
        val track=SavedTrack(id,intent?.getStringExtra("title") ?: "Audio",intent?.getStringExtra("artist").orEmpty(),intent?.getStringExtra("artwork")?.takeIf(NewPipeCatalog::validArtwork))
        val job=DownloadJob(track,"running");DownloadJobs.save(this,job)
        startForeground(204,notification(job));cancelled=false
        task=worker.submit {
            var last=0L
            val result=runCatching {MusicStorage(this).download(track){bytes,total->val now=SystemClock.elapsedRealtime();if(now-last>500){last=now;val progress=job.copy(percent=(bytes*100/total).toInt());DownloadJobs.live=progress;main.post {if(!closed && !cancelled)notifications.notify(204,notification(progress))}}}}
            main.post {if(!closed){val finish=job.copy(state=if(cancelled)"cancelled" else if(result.isSuccess)"complete" else "failed",percent=if(result.isSuccess)100 else DownloadJobs.live?.percent ?: 0);DownloadJobs.save(this,finish);task=null;stopForeground(STOP_FOREGROUND_REMOVE);if(!cancelled)notifications.notify(205,notification(finish));stopSelf()}}
        }
        return START_NOT_STICKY
    }
    private fun notification(job:DownloadJob):Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b=Notification.Builder(this,"offline").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(job.track.title).setContentText(when(job.state){"running"->"Downloading ${job.percent}%";"complete"->"Saved offline";else->"Download interrupted · open the app to retry"}).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(job.state=="running").setAutoCancel(job.state!="running")
        if(job.state=="running"){b.setProgress(100,job.percent,job.percent==0);b.addAction(Notification.Action.Builder(null,"Cancel",PendingIntent.getService(this,1,Intent(this,OfflineDownloadService::class.java).setAction("cancel"),PendingIntent.FLAG_IMMUTABLE)).build())}
        return b.build()
    }
    override fun onTimeout(startId:Int,fgsType:Int){cancelled=true;task?.cancel(true);DownloadJobs.live?.let {DownloadJobs.save(this,it.copy(state="interrupted"))};stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    override fun onDestroy(){closed=true;task?.cancel(true);DownloadJobs.live?.takeIf {it.state=="running"}?.let {DownloadJobs.save(this,it.copy(state="interrupted"))};worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy()}
}
