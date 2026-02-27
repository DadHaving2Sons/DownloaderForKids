package com.example.downloaderforkids

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class DownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "DownloadChannel"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_VIDEO_ID = "extra_video_id"
        const val EXTRA_AUDIO_ID = "extra_audio_id"
        const val EXTRA_SAVE_URI = "extra_save_uri"

        // Broadcast Constants
        const val ACTION_DOWNLOAD_PROGRESS = "com.example.downloaderforkids.DOWNLOAD_PROGRESS"
        const val ACTION_DOWNLOAD_COMPLETE = "com.example.downloaderforkids.DOWNLOAD_COMPLETE"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_STATUS_MESSAGE = "extra_status_message"
        const val EXTRA_IS_DOWNLOADING = "extra_is_downloading"

        private val activeDownloadCount = AtomicInteger(0)

        fun isDownloadInProgress(): Boolean = activeDownloadCount.get() > 0
    }

    private val notificationIdCounter = AtomicInteger(100)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        val videoId = intent?.getStringExtra(EXTRA_VIDEO_ID)
        val audioId = intent?.getStringExtra(EXTRA_AUDIO_ID)
        val saveUriString = intent?.getStringExtra(EXTRA_SAVE_URI)

        if (url != null && videoId != null && audioId != null && saveUriString != null) {
            val saveUri = Uri.parse(saveUriString)
            val notificationId = notificationIdCounter.incrementAndGet()
            
            // Start foreground with the new notification to ensure service stays alive
            // We use the unique ID so each download gets its own notification slot
            val notification = createNotification(getString(R.string.download_preparing), 0, true)
            startForeground(notificationId, notification)
            
            activeDownloadCount.incrementAndGet()
            startDownload(url, videoId, audioId, saveUri, notificationId)
        } else {
            // Only stop if no downloads are active
            if (activeDownloadCount.get() == 0) {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startDownload(url: String, videoId: String, audioId: String, saveUri: Uri, notificationId: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            val tempDirName = "temp_dl_${System.currentTimeMillis()}_$notificationId"
            val tempDir = File(externalCacheDir, tempDirName)
            var fileName = "video.mp4"
            var completionStatusMessage = getString(R.string.unknown_error)

            try {
                if (!tempDir.exists()) tempDir.mkdirs()

                val request = YoutubeDLRequest(url)
                
                if (videoId == "none") {
                    request.addOption("-f", audioId)
                } else {
                    request.addOption("-f", "$videoId+$audioId")
                }
                
                request.addOption("-o", "${tempDir.absolutePath}/%(title)s.%(ext)s")
                request.addOption("--force-overwrites")

                YoutubeDL.getInstance().execute(request) { progress, eta, _ ->
                    val progressText = getString(R.string.download_progress, "$progress% (ETA: $eta s)")
                    updateNotification(notificationId, progressText, progress.toInt(), true)
                    
                    val intent = Intent(ACTION_DOWNLOAD_PROGRESS).apply {
                        putExtra(EXTRA_PROGRESS, progress.toInt())
                        putExtra(EXTRA_STATUS_MESSAGE, progressText)
                        putExtra(EXTRA_IS_DOWNLOADING, true)
                        setPackage(packageName)
                    }
                    sendBroadcast(intent)
                }

                val downloadedFile = tempDir.listFiles()?.firstOrNull()
                    ?: throw IOException(getString(R.string.file_creation_fail))
                fileName = downloadedFile.name

                val targetDir = DocumentFile.fromTreeUri(applicationContext, saveUri)!!
                val mimeType = if (fileName.endsWith(".m4a") || fileName.endsWith(".mp3") || fileName.endsWith(".webm") && videoId == "none") "audio/*" else "video/mp4"

                val destFile = targetDir.findFile(fileName)?.let {
                    it.delete()
                    targetDir.createFile(mimeType, fileName)
                } ?: targetDir.createFile(mimeType, fileName)

                if (destFile == null) throw IOException(getString(R.string.save_fail))

                contentResolver.openOutputStream(destFile.uri)?.use { output ->
                    FileInputStream(downloadedFile).use { input ->
                        input.copyTo(output)
                    }
                }

                withContext(Dispatchers.Main) {
                    showCompletionNotification(notificationId, getString(R.string.download_complete_title), getString(R.string.download_complete_content, fileName))
                }
                completionStatusMessage = getString(R.string.download_success, fileName)

            } catch (e: Exception) {
                Log.e("DownloadService", "Error", e)
                withContext(Dispatchers.Main) {
                    showCompletionNotification(notificationId, getString(R.string.download_fail_title), e.message ?: getString(R.string.unknown_error))
                }
                completionStatusMessage = getString(R.string.error_prefix, e.message)
            } finally {
                tempDir.deleteRecursively()

                val remaining = activeDownloadCount.decrementAndGet()
                val intent = Intent(ACTION_DOWNLOAD_COMPLETE).apply {
                    putExtra(EXTRA_STATUS_MESSAGE, completionStatusMessage)
                    putExtra(EXTRA_IS_DOWNLOADING, remaining > 0)
                    setPackage(packageName)
                }
                sendBroadcast(intent)

                if (remaining == 0) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String, progress: Int, ongoing: Boolean): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setContentIntent(pendingIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(notificationId: Int, content: String, progress: Int, ongoing: Boolean) {
        val notification = createNotification(content, progress, ongoing)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    private fun showCompletionNotification(notificationId: Int, title: String, content: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }
}
