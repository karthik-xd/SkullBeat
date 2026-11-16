package com.skullbeat.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.skullbeat.app.R
import com.skullbeat.app.data.extractor.YouTubeExtractor
import com.skullbeat.app.data.extractor.StreamCache
import com.skullbeat.app.di.DatabaseModule
import com.skullbeat.app.domain.models.DownloadedTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

class DownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_ID = "skullbeat_downloads"
        const val KEY_VIDEO_ID = "video_id"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        const val KEY_ALBUM = "album"
        const val KEY_DURATION_SEC = "duration_sec"
        const val KEY_DURATION_FORMATTED = "duration_formatted"
        const val KEY_THUMBNAIL_URL = "thumbnail_url"
        const val KEY_STREAM_URL = "stream_url"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val videoId = inputData.getString(KEY_VIDEO_ID) ?: return@withContext Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "Audio"
        val artist = inputData.getString(KEY_ARTIST) ?: "SkullBeat"
        val album = inputData.getString(KEY_ALBUM) ?: "Single"
        val durationSec = inputData.getLong(KEY_DURATION_SEC, 0L)
        val durationFormatted = inputData.getString(KEY_DURATION_FORMATTED) ?: "0:00"
        val thumbnailUrl = inputData.getString(KEY_THUMBNAIL_URL) ?: ""
        val passedStreamUrl = inputData.getString(KEY_STREAM_URL)

        createNotificationChannel()
        val notificationId = videoId.hashCode()

        val foregroundInfo = createForegroundInfo(notificationId, title, 0, videoId)
        setForeground(foregroundInfo)

        val db = DatabaseModule.provideAppDatabase(context)
        val manager = DownloadedTracksManager.getInstance(
            context,
            db.downloadedTrackDao(),
            db.trackDao()
        )
        manager.markDownloadStarted(videoId)

        var tempLocalFile: File? = null

        try {
            // Optimized high-throughput client
            val okHttpClient = OkHttpClient.Builder()
                .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()

            val streamUrl = if (!passedStreamUrl.isNullOrBlank()) {
                passedStreamUrl
            } else {
                val streamCache = StreamCache()
                val extractor = YouTubeExtractor(okHttpClient, streamCache)
                extractor.resolveStreamUrl(videoId)
            }

            if (isStopped) {
                manager.markDownloadFinished(videoId)
                notificationManager.cancel(notificationId)
                return@withContext Result.failure()
            }

            val request = Request.Builder()
                .url(streamUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "*/*")
                .header("Connection", "keep-alive")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                manager.markDownloadFinished(videoId)
                return@withContext Result.failure()
            }

            val body = response.body ?: run {
                manager.markDownloadFinished(videoId)
                return@withContext Result.failure()
            }
            val contentLength = body.contentLength()
            val rawInputStream: InputStream = body.byteStream()

            // 1. Prepare dedicated app Music directory for fast direct IO
            val musicDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "SkullBeat").apply { mkdirs() }
            val cleanTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
            val finalLocalFile = File(musicDir, "${cleanTitle}_${videoId}.m4a")
            val tempFile = File(musicDir, "${cleanTitle}_${videoId}.m4a.tmp")
            tempLocalFile = tempFile

            // High-speed 128KB buffer with Buffered streams for maximum throughput
            val buffer = ByteArray(128 * 1024)
            var bytesWritten = 0L
            var bytesRead: Int
            var lastProgress = 0
            var lastNotificationTime = System.currentTimeMillis()

            BufferedInputStream(rawInputStream, 128 * 1024).use { bufferedInput ->
                BufferedOutputStream(FileOutputStream(tempFile), 128 * 1024).use { outputStream ->
                    while (bufferedInput.read(buffer).also { bytesRead = it } != -1) {
                        if (isStopped) {
                            outputStream.close()
                            tempFile.delete()
                            manager.markDownloadFinished(videoId)
                            notificationManager.cancel(notificationId)
                            return@withContext Result.failure()
                        }
                        outputStream.write(buffer, 0, bytesRead)
                        bytesWritten += bytesRead

                        if (contentLength > 0) {
                            val progress = ((bytesWritten * 100) / contentLength).toInt().coerceIn(0, 100)
                            val now = System.currentTimeMillis()
                            if ((progress - lastProgress >= 5 && now - lastNotificationTime >= 500L) || progress == 100) {
                                lastProgress = progress
                                lastNotificationTime = now
                                notificationManager.notify(
                                    notificationId,
                                    buildProgressNotification(title, progress, videoId).build()
                                )
                                setProgress(workDataOf("progress" to progress))
                            }
                        }
                    }
                    outputStream.flush()
                }
            }

            if (isStopped) {
                tempFile.delete()
                manager.markDownloadFinished(videoId)
                notificationManager.cancel(notificationId)
                return@withContext Result.failure()
            }

            // Atomic rename to final local file
            if (finalLocalFile.exists()) finalLocalFile.delete()
            tempFile.renameTo(finalLocalFile)

            // 2. Also register with MediaStore for system visibility
            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, "$cleanTitle.m4a")
                    put(MediaStore.Audio.Media.TITLE, title)
                    put(MediaStore.Audio.Media.ARTIST, artist)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/SkullBeat")
                    }
                }
                context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
            } catch (_: Exception) {}

            // 3. Register downloaded track in Room database & manager
            val downloadedTrack = DownloadedTrack(
                videoId = videoId,
                title = title,
                artist = artist,
                album = album,
                durationSec = durationSec,
                durationFormatted = durationFormatted,
                thumbnailUrl = thumbnailUrl,
                localFilePath = finalLocalFile.absolutePath,
                downloadedAt = System.currentTimeMillis()
            )
            manager.registerDownloadedTrack(downloadedTrack)

            // 4. Success notification
            notificationManager.notify(
                notificationId,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Download Complete")
                    .setContentText("$title - $artist")
                    .setAutoCancel(true)
                    .build()
            )

            Result.success()
        } catch (e: Exception) {
            tempLocalFile?.delete()
            manager.markDownloadFinished(videoId)
            if (!isStopped) {
                notificationManager.notify(
                    notificationId,
                    NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle("Download Failed")
                        .setContentText(title)
                        .setAutoCancel(true)
                        .build()
                )
            }
            Result.failure()
        }
    }

    private fun createForegroundInfo(
        notificationId: Int,
        title: String,
        progress: Int,
        videoId: String
    ): ForegroundInfo {
        val notification = buildProgressNotification(title, progress, videoId).build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun buildProgressNotification(
        title: String,
        progress: Int,
        videoId: String
    ): NotificationCompat.Builder {
        val cancelIntent = Intent(context, DownloadCancelReceiver::class.java).apply {
            putExtra(KEY_VIDEO_ID, videoId)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context,
            videoId.hashCode(),
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Downloading Track")
            .setContentText(title)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelPendingIntent
            )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SkullBeat Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }
}
