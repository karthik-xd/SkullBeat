package com.skullbeat.app.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.WorkManager

class DownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val videoId = intent.getStringExtra(DownloadWorker.KEY_VIDEO_ID) ?: return
        Log.d("DownloadCancelReceiver", "Cancelling download for videoId: $videoId")
        WorkManager.getInstance(context).cancelAllWorkByTag("download_$videoId")
    }
}
